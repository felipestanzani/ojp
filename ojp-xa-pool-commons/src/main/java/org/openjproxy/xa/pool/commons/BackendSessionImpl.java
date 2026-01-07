package org.openjproxy.xa.pool.commons;

import org.openjproxy.xa.pool.XABackendSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Default implementation of {@link XABackendSession} that wraps an {@link XAConnection}.
 * <p>
 * This class provides the poolable session abstraction over a JDBC XA connection.
 * It handles lifecycle operations (open, close, reset, health checks) and provides
 * access to the underlying XAResource for XA transaction operations.
 * </p>
 * 
 * <h3>Reset Semantics:</h3>
 * <p>
 * The {@link #reset()} method cleans session state to prepare for reuse:
 * </p>
 * <ul>
 *   <li>Rolls back any open local transaction (should not happen in XA mode)</li>
 *   <li>Clears warnings</li>
 *   <li>Restores auto-commit to true</li>
 *   <li>Does NOT close the physical connection</li>
 * </ul>
 * 
 * <p><strong>Critical Invariant:</strong> reset() is ONLY called after transaction
 * completion (COMMITTED or ROLLEDBACK state), never while in PREPARED state.</p>
 */
public class BackendSessionImpl implements XABackendSession {
    private static final Logger log = LoggerFactory.getLogger(BackendSessionImpl.class);
    
    private final XAConnection xaConnection;
    private final String sessionId;
    private final Integer defaultTransactionIsolation;
    private XAResource xaResource;
    private Connection connection;
    private volatile boolean closed = false;
    
    /**
     * Creates a new backend session wrapping an XAConnection.
     *
     * @param xaConnection the XA connection to wrap
     */
    public BackendSessionImpl(XAConnection xaConnection) {
        this(xaConnection, null);
    }
    
    /**
     * Creates a new backend session wrapping an XAConnection with transaction isolation reset support.
     *
     * @param xaConnection the XA connection to wrap
     * @param defaultTransactionIsolation the default transaction isolation level to reset to, or null to not reset
     */
    public BackendSessionImpl(XAConnection xaConnection, Integer defaultTransactionIsolation) {
        if (xaConnection == null) {
            throw new IllegalArgumentException("xaConnection cannot be null");
        }
        this.xaConnection = xaConnection;
        this.defaultTransactionIsolation = defaultTransactionIsolation;
        this.sessionId = "session-" + System.currentTimeMillis() + "-" + 
                         Integer.toHexString(System.identityHashCode(this));
    }
    
    @Override
    public void open() throws SQLException {
        if (closed) {
            throw new IllegalStateException("Session is already closed");
        }
        
        // Obtain Connection and XAResource from XAConnection
        this.connection = xaConnection.getConnection();
        this.xaResource = xaConnection.getXAResource();
        
        // Set default transaction isolation level if configured
        // This is called when session is first created, establishing the initial state
        if (defaultTransactionIsolation != null) {
            try {
                int currentIsolation = connection.getTransactionIsolation();
                if (currentIsolation != defaultTransactionIsolation) {
                    log.debug("Setting initial transaction isolation to default {} (was {})", 
                            defaultTransactionIsolation, currentIsolation);
                    connection.setTransactionIsolation(defaultTransactionIsolation);
                }
            } catch (SQLException e) {
                log.warn("Error setting default transaction isolation in open(): {}", e.getMessage());
                // Don't throw - session is still usable even if isolation set fails
            }
        }
        
        log.debug("Backend session opened");
    }
    
    @Override
    public void close() throws SQLException {
        if (closed) {
            return; // Idempotent
        }
        
        closed = true;
        
        // Close the logical connection first
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Error closing connection", e);
            }
            connection = null;
        }
        
        // Close the XAConnection (returns physical connection to pool or closes it)
        try {
            xaConnection.close();
        } catch (SQLException e) {
            log.error("Error closing XAConnection", e);
            throw e;
        }
        
        xaResource = null;
        
        log.debug("Backend session closed");
    }
    
    @Override
    public boolean isHealthy() {
        if (closed) {
            return false;
        }
        
        if (connection == null) {
            return false;
        }
        
        try {
            // Check if connection is valid (with 5 second timeout)
            return connection.isValid(5);
        } catch (SQLException e) {
            log.warn("Health check failed", e);
            return false;
        }
    }
    
    @Override
    public void reset() throws SQLException {
        if (closed) {
            throw new IllegalStateException("Cannot reset closed session");
        }
        
        if (connection == null) {
            throw new IllegalStateException("Session not opened");
        }
        
        try {
            // CRITICAL: Get a fresh connection handle before resetting state
            // The previous logical connection may have been closed by the client,
            // and we need an active handle to properly reset the physical connection state.
            // xaConnection.getConnection() automatically closes the previous logical connection
            // and returns a new handle to the SAME physical connection.
            this.connection = xaConnection.getConnection();
            
            // Roll back any uncommitted local transaction
            // (should not happen in XA mode, but defensive programming)
            if (!connection.getAutoCommit()) {
                try {
                    connection.rollback();
                } catch (SQLException e) {
                    log.warn("Error rolling back during reset", e);
                }
            }
            
            // Clear warnings
            try {
                connection.clearWarnings();
            } catch (SQLException e) {
                log.warn("Error clearing warnings during reset", e);
            }
            
            // Restore auto-commit to true
            try {
                if (!connection.getAutoCommit()) {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                log.error("Error restoring auto-commit during reset", e);
                throw e;
            }
            
            // Reset transaction isolation level if configured
            // NOW this will work because we have a fresh, active connection handle
            if (defaultTransactionIsolation != null) {
                try {
                    int currentIsolation = connection.getTransactionIsolation();
                    if (currentIsolation != defaultTransactionIsolation) {
                        log.debug("Resetting transaction isolation from {} to default {}", 
                                currentIsolation, defaultTransactionIsolation);
                        connection.setTransactionIsolation(defaultTransactionIsolation);
                    }
                } catch (SQLException e) {
                    log.warn("Error resetting transaction isolation during reset", e);
                    // Don't throw - continue with reset even if isolation reset fails
                }
            }
            
            log.debug("Backend session reset completed");
            
        } catch (SQLException e) {
            log.error("Failed to reset session", e);
            throw e;
        }
    }
    
    @Override
    public void sanitizeAfterTransaction() throws SQLException {
        if (closed) {
            throw new IllegalStateException("Cannot sanitize closed session");
        }
        
        log.debug("Sanitizing backend session after transaction: {}", sessionId);
        
        try {
            // Get a fresh logical connection from the XAConnection
            // According to JDBC spec, calling getConnection() on an XAConnection
            // automatically closes the previous logical connection and returns a new handle
            // to the SAME physical connection. This resets the XA state to IDLE in most drivers.
            this.connection = xaConnection.getConnection();
            
            // The XAResource should remain the same (from the XAConnection)
            // No need to re-obtain it - it's tied to the XAConnection, not the logical connection
            
            // Clear warnings on the new connection
            try {
                connection.clearWarnings();
            } catch (SQLException e) {
                log.warn("Error clearing warnings after sanitization: {}", e.getMessage());
            }
            
            // NOTE: Transaction isolation will be reset in reset() method during passivation
            // when the connection is returned to the pool. We don't reset it here because
            // the client may continue using this connection for more XA transactions before
            // calling close().
            
            log.debug("Backend session sanitized successfully, fresh logical connection obtained");
            
        } catch (SQLException e) {
            log.error("Failed to sanitize session: {}", e.getMessage(), e);
            throw e;
        }
    }
    
    @Override
    public XAResource getXAResource() {
        if (xaResource == null) {
            throw new IllegalStateException("Session not opened");
        }
        return xaResource;
    }
    
    @Override
    public Connection getConnection() {
        if (connection == null) {
            throw new IllegalStateException("Session not opened");
        }
        return connection;
    }
    
    @Override
    public XAConnection getXAConnection() {
        return xaConnection;
    }
    
    @Override
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * Checks if this session is closed.
     *
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }
}
