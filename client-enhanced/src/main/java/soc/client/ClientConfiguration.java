package soc.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Configuration properties for the socket client.
 * These values can be overridden in application.properties/yaml files.
 */
@Configuration
@ConfigurationProperties(prefix = "socket.client")
@Validated
public class ClientConfiguration {

    /**
     * Server hostname or IP address
     */
    @NotBlank(message = "Server hostname cannot be empty")
    private String hostname = "localhost";

    /**
     * Server port
     */
    @Min(value = 1, message = "Port must be greater than 0")
    @Max(value = 65535, message = "Port must be less than or equal to 65535")
    private int port = 8080;

    /**
     * Connection timeout in milliseconds
     */
    @Min(value = 1000, message = "Connection timeout must be at least 1000 ms")
    private int connectionTimeout = 5000;

    /**
     * Socket read timeout in milliseconds (0 for infinite)
     */
    @Min(value = 0, message = "Read timeout cannot be negative")
    private int readTimeout = 0;

    /**
     * Unique client identifier
     */
    @NotNull(message = "Client ID cannot be null")
    private String clientId = UUID.randomUUID().toString();

    /**
     * Maximum number of reconnection attempts
     */
    @Min(value = 0, message = "Max reconnection attempts cannot be negative")
    private int maxReconnectionAttempts = 10;

    /**
     * Use secure connection (TLS/SSL)
     */
    private boolean secure = false;

    /**
     * Path to the keystore for SSL/TLS (if secure=true)
     */
    private String keystorePath;

    /**
     * Password for the keystore (if secure=true)
     */
    private String keystorePassword;

    /**
     * Whether to use TCP keep-alive
     */
    private boolean keepAlive = true;

    /**
     * Whether to use TCP_NODELAY (disable Nagle's algorithm)
     */
    private boolean tcpNoDelay = true;

    /**
     * Socket buffer size in bytes
     */
    @Min(value = 1024, message = "Buffer size must be at least 1024 bytes")
    private int bufferSize = 8192;

    // Getters and setters

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public int getMaxReconnectionAttempts() {
        return maxReconnectionAttempts;
    }

    public void setMaxReconnectionAttempts(int maxReconnectionAttempts) {
        this.maxReconnectionAttempts = maxReconnectionAttempts;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public void setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    public void setTcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    @Override
    public String toString() {
        return "ClientConfiguration{" +
                "hostname='" + hostname + '\'' +
                ", port=" + port +
                ", connectionTimeout=" + connectionTimeout +
                ", readTimeout=" + readTimeout +
                ", clientId='" + clientId + '\'' +
                ", maxReconnectionAttempts=" + maxReconnectionAttempts +
                ", secure=" + secure +
                ", keepAlive=" + keepAlive +
                ", tcpNoDelay=" + tcpNoDelay +
                ", bufferSize=" + bufferSize +
                '}';
    }
}