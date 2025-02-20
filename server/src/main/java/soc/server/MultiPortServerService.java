package soc.server;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class MultiPortServerService {
    private static final Logger logger = LoggerFactory.getLogger(MultiPortServerService.class);
    private final ServerConfiguration serverConfiguration;
    private final ExecutorService executorService;

    public MultiPortServerService(ServerConfiguration serverConfiguration) {
        this.serverConfiguration = serverConfiguration;
        this.executorService = Executors.newCachedThreadPool();
    }

    @PostConstruct
    public void startServers() {
        for (int port : serverConfiguration.getPorts()) {
            executorService.submit(() -> startServer(port));
        }
    }

    private void startServer(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            logger.info("Server is listening on port {}", port);

            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();
                logger.info("New client connected on port {}", port);
                executorService.submit(new ClientHandler(socket));
            }
        } catch (IOException ex) {
            logger.error("Server exception on port {}: {}", port, ex.getMessage(), ex);
        }
    }

}