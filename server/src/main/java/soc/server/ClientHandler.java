package soc.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class ClientHandler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (InputStream input = socket.getInputStream();

                 Scanner scanner = new Scanner(System.in);
                 OutputStream output = socket.getOutputStream();
                 PrintWriter writer = new PrintWriter(output, true)) {

                String text;
                while ((text = scanner.nextLine()) != null) {
                    logger.info("User input {}: {}", socket.getLocalPort(), text);
                    writer.println("Message sent to port " + socket.getLocalPort() + ": " + text);
                }
            } catch (IOException ex) {
                logger.error("Client handler exception: {}", ex.getMessage(), ex);
            } finally {
                try {
                    socket.close();
                    logger.info("Client disconnected from port {}", socket.getLocalPort());
                } catch (IOException ex) {
                    logger.error("Error closing socket: {}", ex.getMessage(), ex);
                }
            }
        }
    }