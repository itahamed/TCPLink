package soc.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SocketClientService {
    private static final Logger logger = LoggerFactory.getLogger(SocketClientService.class);
    private final ClientConfiguration clientConfig;
    private final ExecutorService clientExecutor;
    private ExecutorService requestProcessorPool;
    private ScheduledExecutorService heartbeatScheduler;
    private BlockingQueue<String> outgoingMessageQueue;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicLong messageIdGenerator = new AtomicLong(0);
    private final ObjectMapper objectMapper;
    private final Map<String, CompletableFuture<String>> pendingResponses = new ConcurrentHashMap<>();
    
    @Value("${socket.client.reconnect.interval:5000}")
    private long reconnectInterval;
    
    @Value("${socket.client.heartbeat.interval:30000}")
    private long heartbeatInterval;
    
    @Value("${socket.client.thread.pool.size:10}")
    private int threadPoolSize;
    
    @Value("${socket.client.queue.capacity:1000}")
    private int queueCapacity;

    public SocketClientService(ClientConfiguration clientConfig, ObjectMapper objectMapper) {
        this.clientConfig = clientConfig;
        this.objectMapper = objectMapper;
        this.clientExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "socket-client-main");
            t.setDaemon(false);
            return t;
        });
        initialize();
    }

    private void initialize() {
        // Initialize thread pools with custom thread factory for better monitoring
        ThreadFactory processorThreadFactory = new ThreadFactory() {
            private final AtomicLong counter = new AtomicLong(0);
            
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "socket-processor-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        
        this.requestProcessorPool = new ThreadPoolExecutor(
            threadPoolSize / 2,
            threadPoolSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            processorThreadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "socket-heartbeat");
            t.setDaemon(true);
            return t;
        });
        
        this.outgoingMessageQueue = new LinkedBlockingQueue<>(queueCapacity);
        
        logger.info("Initialized socket client with thread pool size {} and queue capacity {}", 
                threadPoolSize, queueCapacity);
    }

    @PostConstruct
    public void startClient() {
        if (isRunning.compareAndSet(false, true)) {
            clientExecutor.submit(this::runClientWithReconnect);
            logger.info("Socket client service started");
        } else {
            logger.warn("Attempt to start already running client service");
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down socket client service");
        isRunning.set(false);
        
        shutdownExecutor(heartbeatScheduler, "Heartbeat scheduler");
        shutdownExecutor(requestProcessorPool, "Request processor pool");
        shutdownExecutor(clientExecutor, "Client executor");
        
        pendingResponses.forEach((id, future) -> 
            future.completeExceptionally(new RuntimeException("Client service shutdown"))
        );
        pendingResponses.clear();
        
        logger.info("Socket client service shutdown complete");
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("{} did not terminate", name);
                    }
                }
                logger.debug("{} shutdown complete", name);
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                logger.warn("{} shutdown interrupted", name);
            }
        }
    }

    private void runClientWithReconnect() {
        while (isRunning.get()) {
            try {
                runClient();
            } catch (Exception e) {
                if (isRunning.get()) {
                    logger.error("Client connection error: {}", e.getMessage(), e);
                    try {
                        Thread.sleep(reconnectInterval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.info("Reconnect sleep interrupted");
                        break;
                    }
                    logger.info("Attempting to reconnect...");
                }
            }
        }
        logger.info("Client reconnect loop terminated");
    }

    private void runClient() {
        Socket socket = null;
        PrintWriter out = null;
        BufferedReader in = null;
        Thread messageConsumer = null;
        ScheduledFuture<?> heartbeatTask = null;
        Scanner scanner = null;
        
        try {
            // Establish connection
            socket = new Socket(clientConfig.getHostname(), clientConfig.getPort());
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0); // Infinite timeout for reads
            
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            scanner = new Scanner(System.in);
            
            logger.info("Connected to server on port {}", clientConfig.getPort());
            System.out.println("Connected to server. Type messages (or 'exit' to quit):");
            
            // Start the server message listener
            final PrintWriter finalOut = out;
            final Socket finalSocket = socket;
            
            // Start message consumer thread
            messageConsumer = new Thread(() -> consumeOutgoingMessages(finalOut), "message-consumer");
            messageConsumer.setDaemon(true);
            messageConsumer.start();
            
            // Start server message listener thread
            BufferedReader finalIn = in;
            Thread serverListener = new Thread(() -> listenForServerMessages(finalIn, finalSocket), "server-listener");
            serverListener.setDaemon(true);
            serverListener.start();
            
            // Start heartbeat task
            heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(finalOut), 
                heartbeatInterval, 
                heartbeatInterval, 
                TimeUnit.MILLISECONDS
            );
            
            // Process user input in the main thread
            String userInput;
            while (isRunning.get() && (userInput = scanner.nextLine()) != null) {
                if ("exit".equalsIgnoreCase(userInput)) {
                    isRunning.set(false);
                    break;
                }
                
                // Queue the message rather than processing immediately
                if (!outgoingMessageQueue.offer(userInput)) {
                    logger.warn("Outgoing message queue full, message discarded");
                    System.out.println("System busy, please try again later.");
                }
            }
            
        } catch (IOException e) {
            if (isRunning.get()) {
                throw new RuntimeException("Connection error", e);
            }
        } finally {
            // Clean up resources
            if (heartbeatTask != null) {
                heartbeatTask.cancel(true);
            }
            
            if (messageConsumer != null) {
                messageConsumer.interrupt();
            }
            
            closeQuietly(scanner);
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(socket);
            
            logger.info("Disconnected from server");
        }
    }
    
    private void consumeOutgoingMessages(PrintWriter out) {
        try {
            while (isRunning.get()) {
                String message = outgoingMessageQueue.poll(100, TimeUnit.MILLISECONDS);
                if (message != null) {
                    processAndSendRequest(message, out);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Message consumer thread interrupted");
        } catch (Exception e) {
            logger.error("Error in message consumer thread", e);
        }
    }

    private void listenForServerMessages(BufferedReader in, Socket socket) {
        try {
            String serverMessage;
            while (isRunning.get() && !socket.isClosed() && (serverMessage = in.readLine()) != null) {
                final String message = serverMessage;
                requestProcessorPool.execute(() -> processServerMessage(message));
            }
        } catch (SocketException e) {
            if (isRunning.get()) {
                logger.error("Socket error while listening for server messages: {}", e.getMessage());
            }
        } catch (IOException e) {
            if (isRunning.get() && !socket.isClosed()) {
                logger.error("Error reading from server: {}", e.getMessage(), e);
            }
        } catch (Exception e) {
            if (isRunning.get()) {
                logger.error("Unexpected error in server listener: {}", e.getMessage(), e);
            }
        }
    }

    private void processServerMessage(String message) {
        try {
            logger.debug("Received server message: {}", message);
            
            // Parse message
            @SuppressWarnings("unchecked")
            Map<String, Object> messageMap = objectMapper.readValue(message, Map.class);
            
            // Check if message is a response to a request
            String correlationId = (String) messageMap.get("correlationId");
            if (correlationId != null && pendingResponses.containsKey(correlationId)) {
                CompletableFuture<String> future = pendingResponses.remove(correlationId);
                if (future != null) {
                    future.complete(message);
                    return;
                }
            }
            
            // Process based on message type
            String messageType = (String) messageMap.get("type");
            if ("heartbeat".equals(messageType)) {
                logger.debug("Received heartbeat from server");
                return;
            }
            
            // Process regular message
            logger.info("Processing server message: {}", messageMap);
            
            // Display the processed message
            String displayMessage = formatDisplayMessage(messageMap);
            System.out.println("SERVER: " + displayMessage);
            
        } catch (Exception e) {
            logger.error("Error processing server message: {}", e.getMessage(), e);
        }
    }

    private String formatDisplayMessage(Map<String, Object> messageMap) {
        try {
            if (messageMap.containsKey("content")) {
                return (String) messageMap.get("content");
            }
            return objectMapper.writeValueAsString(messageMap);
        } catch (Exception e) {
            logger.warn("Error formatting message for display", e);
            return "Unparseable message received";
        }
    }

    private void processAndSendRequest(String request, PrintWriter out) {
        try {
            logger.debug("Processing outgoing request: {}", request);
            
            // Generate message ID
            String messageId = String.valueOf(messageIdGenerator.incrementAndGet());
            
            // Create structured message
            Map<String, Object> message = new ConcurrentHashMap<>();
            message.put("id", messageId);
            message.put("content", request);
            message.put("timestamp", System.currentTimeMillis());
            
            // Serialize message
            String jsonMessage = objectMapper.writeValueAsString(message);
            
            // Send message
            out.println(jsonMessage);
            logger.debug("Sent message: {}", jsonMessage);
            
            // Add to pending responses if needed
            // CompletableFuture<String> responseFuture = new CompletableFuture<>();
            // pendingResponses.put(messageId, responseFuture);
            
            // Example of how to wait for response with timeout
            // String response = responseFuture.get(30, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            logger.error("Error processing outgoing request: {}", e.getMessage(), e);
        }
    }

    private void sendHeartbeat(PrintWriter out) {
        try {
            Map<String, Object> heartbeat = new ConcurrentHashMap<>();
            heartbeat.put("type", "heartbeat");
            heartbeat.put("timestamp", System.currentTimeMillis());
            heartbeat.put("clientId", clientConfig.getClientId());
            
            String heartbeatJson = objectMapper.writeValueAsString(heartbeat);
            out.println(heartbeatJson);
            logger.debug("Sent heartbeat");
        } catch (Exception e) {
            logger.warn("Failed to send heartbeat: {}", e.getMessage());
        }
    }
    
    /**
     * Send a request and wait for response asynchronously
     * 
     * @param request The request message
     * @return CompletableFuture that will be completed with the response
     */
    public CompletableFuture<String> sendRequestAsync(String request) {
        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        
        try {
            // Generate message ID
            String messageId = String.valueOf(messageIdGenerator.incrementAndGet());
            
            // Create structured message
            Map<String, Object> message = new ConcurrentHashMap<>();
            message.put("id", messageId);
            message.put("correlationId", messageId);
            message.put("content", request);
            message.put("timestamp", System.currentTimeMillis());
            
            // Register for response
            pendingResponses.put(messageId, responseFuture);
            
            // Add timeout handler
            scheduleCancellation(responseFuture, messageId, 30, TimeUnit.SECONDS);
            
            // Queue message
            String jsonMessage = objectMapper.writeValueAsString(message);
            boolean queued = outgoingMessageQueue.offer(jsonMessage);
            
            if (!queued) {
                pendingResponses.remove(messageId);
                responseFuture.completeExceptionally(new RuntimeException("Request queue full"));
            }
            
        } catch (Exception e) {
            responseFuture.completeExceptionally(e);
        }
        
        return responseFuture;
    }
    
    private void scheduleCancellation(CompletableFuture<String> future, String messageId, long timeout, TimeUnit unit) {
        heartbeatScheduler.schedule(() -> {
            if (pendingResponses.containsKey(messageId)) {
                pendingResponses.remove(messageId);
                future.completeExceptionally(new TimeoutException("Response timeout after " + timeout + " " + unit.name().toLowerCase()));
            }
        }, timeout, unit);
    }
    
    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.debug("Error closing resource: {}", e.getMessage());
            }
        }
    }
}