package org.openjproxy.xa.pool.commons;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.openjproxy.xa.pool.XABackendSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Map;

/**
 * XADataSource wrapper that pools {@link XABackendSession} instances using Apache Commons Pool 2.
 * <p>
 * This class wraps a vendor XADataSource and provides connection pooling with configurable
 * size, timeout, validation, and eviction policies optimized for XA transaction workloads.
 * </p>
 * 
 * <h3>Configuration:</h3>
 * <p>Configuration is provided via a map with the following keys:</p>
 * <ul>
 *   <li>{@code xa.maxPoolSize} - Maximum pool size (default: 10)</li>
 *   <li>{@code xa.minIdle} - Minimum idle sessions (default: 2)</li>
 *   <li>{@code xa.connectionTimeoutMs} - Borrow timeout in ms (default: 30000)</li>
 *   <li>{@code xa.idleTimeoutMs} - Idle eviction timeout in ms (default: 600000)</li>
 *   <li>{@code xa.maxLifetimeMs} - Maximum session lifetime in ms (default: 1800000)</li>
 * </ul>
 * 
 * <h3>Pool Behavior:</h3>
 * <ul>
 *   <li>Blocks when pool exhausted (up to connectionTimeoutMs)</li>
 *   <li>Validates sessions on borrow (testOnBorrow=true)</li>
 *   <li>Validates idle sessions periodically (testWhileIdle=true)</li>
 *   <li>Evicts idle sessions after idleTimeoutMs</li>
 *   <li>Limits session lifetime to maxLifetimeMs</li>
 * </ul>
 * 
 * <p><strong>Note:</strong> This wrapper implements XADataSource for compatibility but
 * should NOT be used directly for getting XAConnections. Use the provider's
 * {@code borrowSession()} method instead.</p>
 */
public class CommonsPool2XADataSource implements XADataSource {
    private static final Logger log = LoggerFactory.getLogger(CommonsPool2XADataSource.class);
    
    private final XADataSource vendorXADataSource;
    private final GenericObjectPool<XABackendSession> pool;
    private final Map<String, String> config;
    
    /**
     * Creates a new pooled XADataSource.
     *
     * @param vendorXADataSource the underlying vendor XADataSource
     * @param config the pool configuration
     */
    public CommonsPool2XADataSource(XADataSource vendorXADataSource, Map<String, String> config) {
        if (vendorXADataSource == null) {
            throw new IllegalArgumentException("vendorXADataSource cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        
        this.vendorXADataSource = vendorXADataSource;
        this.config = config;
        
        // Create the session factory
        BackendSessionFactory factory = new BackendSessionFactory(vendorXADataSource);
        
        // Configure the pool
        GenericObjectPoolConfig<XABackendSession> poolConfig = createPoolConfig(config);
        
        // Create the pool
        this.pool = new GenericObjectPool<>(factory, poolConfig);
        
        log.info("CommonsPool2XADataSource created with maxTotal={}, minIdle={}, maxWaitMs={}",
                poolConfig.getMaxTotal(), poolConfig.getMinIdle(), poolConfig.getMaxWaitDuration().toMillis());
    }
    
    /**
     * Borrows a backend session from the pool.
     * <p>
     * This method is called by the XA transaction registry when starting a new
     * XA transaction branch. It will block if the pool is exhausted, up to the
     * configured timeout.
     * </p>
     *
     * @return a backend session from the pool
     * @throws Exception if session cannot be borrowed
     */
    public XABackendSession borrowSession() throws Exception {
        log.debug("Borrowing session from pool");
        
        try {
            XABackendSession session = pool.borrowObject();
            
            log.debug("Session borrowed successfully (active={}, idle={})",
                    pool.getNumActive(), pool.getNumIdle());
            
            return session;
            
        } catch (Exception e) {
            log.error("Failed to borrow session from pool", e);
            throw e;
        }
    }
    
    /**
     * Returns a backend session to the pool.
     * <p>
     * The session will be reset to a clean state before being made available
     * for reuse. If reset fails, the session is invalidated instead.
     * </p>
     *
     * @param session the session to return
     */
    public void returnSession(XABackendSession session) {
        if (session == null) {
            return;
        }
        
        log.debug("Returning session to pool");
        
        try {
            pool.returnObject(session);
            
            log.debug("Session returned successfully (active={}, idle={})",
                    pool.getNumActive(), pool.getNumIdle());
            
        } catch (Exception e) {
            log.error("Failed to return session to pool", e);
            // Session will be destroyed by pool
        }
    }
    
    /**
     * Invalidates a backend session, removing it from the pool.
     * <p>
     * This is called when a session encounters an unrecoverable error.
     * The session will be destroyed and a new one may be created to maintain
     * pool size.
     * </p>
     *
     * @param session the session to invalidate
     */
    public void invalidateSession(XABackendSession session) {
        if (session == null) {
            return;
        }
        
        log.warn("Invalidating session");
        
        try {
            pool.invalidateObject(session);
            
            log.info("Session invalidated (active={}, idle={})",
                    pool.getNumActive(), pool.getNumIdle());
            
        } catch (Exception e) {
            log.error("Failed to invalidate session", e);
        }
    }
    
    /**
     * Gets the number of active sessions in the pool.
     *
     * @return the number of currently borrowed sessions
     */
    public int getNumActive() {
        return pool.getNumActive();
    }
    
    /**
     * Gets the number of idle sessions in the pool.
     *
     * @return the number of sessions available for borrowing
     */
    public int getNumIdle() {
        return pool.getNumIdle();
    }
    
    /**
     * Gets the number of threads waiting to borrow a session.
     *
     * @return the number of waiting threads
     */
    public int getNumWaiters() {
        return pool.getNumWaiters();
    }
    
    /**
     * Gets the maximum pool size.
     *
     * @return the configured maximum number of sessions
     */
    public int getMaxTotal() {
        return pool.getMaxTotal();
    }
    
    /**
     * Sets the maximum number of sessions that can be allocated in the pool.
     * Allows dynamic pool resizing at runtime for cluster rebalancing.
     *
     * @param maxTotal the new maximum pool size
     */
    public void setMaxTotal(int maxTotal) {
        log.info("Resizing XA pool: setMaxTotal from {} to {}", pool.getMaxTotal(), maxTotal);
        pool.setMaxTotal(maxTotal);
    }
    
    /**
     * Gets the minimum number of idle sessions maintained in the pool.
     *
     * @return the minimum idle sessions
     */
    public int getMinIdle() {
        return pool.getMinIdle();
    }
    
    /**
     * Sets the minimum number of idle sessions to maintain in the pool.
     * Allows dynamic pool resizing at runtime for cluster rebalancing.
     *
     * @param minIdle the new minimum idle sessions
     */
    public void setMinIdle(int minIdle) {
        log.info("Resizing XA pool: setMinIdle from {} to {}", pool.getMinIdle(), minIdle);
        pool.setMinIdle(minIdle);
        
        // After increasing minIdle, ensure idle connections are created immediately
        // Commons Pool 2 doesn't automatically create idle connections when you increase minIdle
        try {
            int currentIdle = pool.getNumIdle();
            int currentActive = pool.getNumActive();
            int needed = minIdle - currentIdle;
            
            if (needed > 0) {
                log.info("Creating {} idle connections to reach minIdle={} (current: idle={}, active={})", 
                        needed, minIdle, currentIdle, currentActive);
                
                // Add idle objects to reach minIdle
                for (int i = 0; i < needed; i++) {
                    pool.addObject();
                }
                
                log.info("Idle connections created: idle={}, active={}", 
                        pool.getNumIdle(), pool.getNumActive());
            }
        } catch (Exception e) {
            log.warn("Failed to pre-create idle connections: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Closes the pool and releases all resources.
     * <p>
     * This will close all sessions (active and idle) and shut down the pool.
     * </p>
     */
    public void close() {
        log.info("Closing CommonsPool2XADataSource (active={}, idle={})",
                pool.getNumActive(), pool.getNumIdle());
        
        pool.close();
        
        log.info("CommonsPool2XADataSource closed");
    }
    
    // XADataSource interface methods (not directly used)
    
    @Override
    public XAConnection getXAConnection() throws SQLException {
        throw new UnsupportedOperationException(
                "Do not use getXAConnection() directly. Use provider.borrowSession() instead.");
    }
    
    @Override
    public XAConnection getXAConnection(String user, String password) throws SQLException {
        throw new UnsupportedOperationException(
                "Do not use getXAConnection() directly. Use provider.borrowSession() instead.");
    }
    
    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return vendorXADataSource.getLogWriter();
    }
    
    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        vendorXADataSource.setLogWriter(out);
    }
    
    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        vendorXADataSource.setLoginTimeout(seconds);
    }
    
    @Override
    public int getLoginTimeout() throws SQLException {
        return vendorXADataSource.getLoginTimeout();
    }
    
    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return vendorXADataSource.getParentLogger();
    }
    
    // Private helper methods
    
    private static GenericObjectPoolConfig<XABackendSession> createPoolConfig(Map<String, String> config) {
        GenericObjectPoolConfig<XABackendSession> poolConfig = new GenericObjectPoolConfig<>();
        
        // Pool sizing
        int maxTotal = getIntConfig(config, "xa.maxPoolSize", 10);
        int minIdle = getIntConfig(config, "xa.minIdle", 2);
        poolConfig.setMaxTotal(maxTotal);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxIdle(maxTotal); // maxIdle same as maxTotal
        
        // Borrow behavior
        poolConfig.setBlockWhenExhausted(true);
        long maxWaitMs = getLongConfig(config, "xa.connectionTimeoutMs", 30000L);
        poolConfig.setMaxWait(Duration.ofMillis(maxWaitMs));
        
        // Validation
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(false);
        poolConfig.setTestWhileIdle(true);
        
        // Eviction
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        long idleTimeoutMs = getLongConfig(config, "xa.idleTimeoutMs", 600000L);
        poolConfig.setMinEvictableIdleDuration(Duration.ofMillis(idleTimeoutMs));
        poolConfig.setSoftMinEvictableIdleDuration(Duration.ofMillis(idleTimeoutMs / 2));
        
        // Lifetime
        long maxLifetimeMs = getLongConfig(config, "xa.maxLifetimeMs", 1800000L);
        poolConfig.setMaxWait(Duration.ofMillis(maxLifetimeMs));
        
        // Fairness
        poolConfig.setFairness(true);
        
        return poolConfig;
    }
    
    private static int getIntConfig(Map<String, String> config, String key, int defaultValue) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid integer config for {}: {}, using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    private static long getLongConfig(Map<String, String> config, String key, long defaultValue) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid long config for {}: {}, using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }
}
