package soc.client;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class SocketClientService {
    private static final Logger logger = LoggerFactory.getLogger(SocketClientService.class);
    private final ClientConfiguration clientConfig;
    private final ExecutorService clientExecutor;
    private ExecutorService requestProcessorPool;

    public SocketClientService(ClientConfiguration clientConfig) {
        this.clientConfig = clientConfig;
        this.clientExecutor = Executors.newSingleThreadExecutor();
        initialize();
    }

    public void initialize() {
        // Create a thread pool for processing requests
        requestProcessorPool = Executors.newFixedThreadPool(5);
        logger.info("Initialized request processor thread pool with 5 threads");
    }

    @PostConstruct
    public void startClient() {
        clientExecutor.submit(this::runClient);
        logger.info("Client service started");
    }

    @PreDestroy
    public void shutdown() {
        shutdownThreadPool();
        if (!clientExecutor.isShutdown()) {
            clientExecutor.shutdownNow();
        }
        logger.info("Client service shut down");
    }

    private void runClient() {
        if (requestProcessorPool == null || requestProcessorPool.isShutdown()) {
            initialize();
        }
        
        try {
            Socket socket = new Socket(clientConfig.getHostname(), clientConfig.getPort());
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            logger.info("Connected to server on port {}", clientConfig.getPort());
            System.out.println("Type messages (or 'exit' to quit):");
            
            // Create a separate thread to continuously listen for server messages
            Thread serverListener = new Thread(() -> {
                try {
                    String serverMessage;
                    while ((serverMessage = in.readLine()) != null) {
                        final String message = serverMessage;
                        // Process each incoming server message in a thread from the pool
                        requestProcessorPool.submit(() -> processAndSendRequest(message, out));
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        logger.error("Error reading from server: {}", e.getMessage(), e);
                    }
                }
            });
            
            // Start server listener thread as daemon (will terminate when main thread exits)
            serverListener.setDaemon(true);
            serverListener.start();
            
            logger.info("Disconnecting from server");
        } catch (IOException e) {
            logger.error("Error connecting to server: {}", e.getMessage(), e);
        }
    }

    private void processServerMessage(String message) {
        try {
            logger.info("Processing server message: {}", message);
            // Add your processing logic here
            
            // For example, parse JSON, XML, or process commands
            // JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            
            // Display the processed message
            System.out.println("SERVER: " + message);
        } catch (Exception e) {
            logger.error("Error processing server message: {}", e.getMessage(), e);
        }
    }

    private void processAndSendRequest(String request, PrintWriter out) {
        try {
            logger.info("Processing outgoing request: {}", request);
            // Add your request processing logic here
            
            // For example, format the request, add timestamps, etc.
            String processedRequest = request;
            
            // Send the processed request to the server
            out.println(processedRequest);
        } catch (Exception e) {
            logger.error("Error processing request: {}", e.getMessage(), e);
        }
    }

    private void shutdownThreadPool() {
        if (requestProcessorPool != null && !requestProcessorPool.isShutdown()) {
            try {
                requestProcessorPool.shutdown();
                if (!requestProcessorPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    requestProcessorPool.shutdownNow();
                }
                logger.info("Request processor thread pool shut down");
            } catch (InterruptedException e) {
                requestProcessorPool.shutdownNow();
                Thread.currentThread().interrupt();
                logger.warn("Thread pool shutdown interrupted", e);
            }
        }
    }
}