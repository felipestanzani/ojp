package org.openjproxy.grpc.client;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents an OJP server endpoint with host and port information.
 * Tracks the health status of each server for failover purposes.
 * 
 * Thread-safety: Uses AtomicReference with immutable HealthState to ensure
 * atomic updates of health status and failure time together.
 */
public class ServerEndpoint {
    private final String host;
    private final int port;
    private final String dataSourceName;
    
    /**
     * Immutable holder for health state to ensure atomic reads/writes.
     * Package-private to allow MultinodeConnectionManager to use it.
     */
    static class HealthState {
        final boolean healthy;
        final long lastFailureTime;
        
        HealthState(boolean healthy, long lastFailureTime) {
            this.healthy = healthy;
            this.lastFailureTime = lastFailureTime;
        }
    }
    
    private final AtomicReference<HealthState> healthState = 
        new AtomicReference<>(new HealthState(true, 0));

    public ServerEndpoint(String host, int port) {
        this(host, port, "default");
    }

    public ServerEndpoint(String host, int port, String dataSourceName) {
        this.host = Objects.requireNonNull(host, "Host cannot be null");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        this.port = port;
        this.dataSourceName = dataSourceName != null ? dataSourceName : "default";
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public String getAddress() {
        return host + ":" + port;
    }

    public boolean isHealthy() {
        return healthState.get().healthy;
    }

    public void setHealthy(boolean healthy) {
        HealthState current = healthState.get();
        healthState.set(new HealthState(healthy, current.lastFailureTime));
    }

    public long getLastFailureTime() {
        return healthState.get().lastFailureTime;
    }

    public void setLastFailureTime(long lastFailureTime) {
        HealthState current = healthState.get();
        healthState.set(new HealthState(current.healthy, lastFailureTime));
    }

    /**
     * Marks this server as healthy.
     * Atomically sets both healthy=true and lastFailureTime=0.
     */
    public void markHealthy() {
        healthState.set(new HealthState(true, 0));
    }

    /**
     * Marks this server as unhealthy.
     * Atomically sets both healthy=false and lastFailureTime=current time.
     */
    public void markUnhealthy() {
        healthState.set(new HealthState(false, System.currentTimeMillis()));
    }
    
    /**
     * Gets the complete health state atomically.
     * Use this when you need to read both healthy and lastFailureTime together
     * to avoid race conditions.
     * 
     * @return immutable HealthState snapshot
     */
    HealthState getHealthState() {
        return healthState.get();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServerEndpoint that = (ServerEndpoint) o;
        return port == that.port && Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return getAddress();
    }
}
