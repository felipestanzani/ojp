# XA Pool SPI API Reference

## Overview

This document provides detailed API documentation for all interfaces and classes in the XA Pool SPI.

## Core Interfaces

### XAConnectionPoolProvider

Main SPI interface for implementing XA connection pool providers.

```java
package org.openjproxy.xa.pool;

import javax.sql.XADataSource;
import java.util.Map;

public interface XAConnectionPoolProvider {
    /**
     * Get the name of this provider.
     * 
     * @return provider name (e.g., "CommonsPool2XAProvider")
     */
    String getName();
    
    /**
     * Get the priority of this provider.
     * Higher values indicate higher priority.
     * 
     * @return priority value (default provider uses 0)
     */
    int getPriority();
    
    /**
     * Check if this provider supports the given database URL.
     * 
     * @param databaseUrl JDBC URL to check
     * @return true if this provider can handle the database
     */
    boolean supports(String databaseUrl);
    
    /**
     * Create a pooled XADataSource.
     * 
     * @param config configuration map with properties like:
     *               - xa.datasource.className: XADataSource class name
     *               - xa.url: database URL
     *               - xa.username: database username
     *               - xa.password: database password
     *               - xa.maxPoolSize: maximum pool size
     *               - xa.minIdle: minimum idle connections
     *               - xa.maxWaitMillis: max wait time for connection
     * @return pooled XADataSource implementation
     * @throws Exception if pool creation fails
     */
    XADataSource createPooledXADataSource(Map<String, String> config) throws Exception;
    
    /**
     * Borrow a session from the pool.
     * 
     * @param pooledDataSource the pooled XADataSource
     * @return BackendSession wrapping an XAConnection
     * @throws Exception if session cannot be borrowed
     */
    BackendSession borrowSession(XADataSource pooledDataSource) throws Exception;
}
```

### BackendSession

Interface representing a poolable XA connection session.

```java
package org.openjproxy.xa.pool;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;

public interface BackendSession extends AutoCloseable {
    /**
     * Get the underlying XAConnection.
     * 
     * @return XAConnection instance
     */
    XAConnection getXAConnection();
    
    /**
     * Get the JDBC Connection.
     * 
     * @return Connection instance
     */
    Connection getConnection();
    
    /**
     * Get the XAResource for transaction management.
     * 
     * @return XAResource instance
     */
    XAResource getXAResource();
    
    /**
     * Close this session and return it to the pool.
     * 
     * @throws Exception if close fails
     */
    @Override
    void close() throws Exception;
}
```

## Core Classes

### XATransactionRegistry

Manages XA transaction lifecycle and session associations.

```java
package org.openjproxy.xa.pool;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;

public class XATransactionRegistry {
    /**
     * Register an existing BackendSession with a new XA transaction.
     * Used when BackendSession is allocated eagerly (during connect).
     * 
     * @param xid transaction ID
     * @param session BackendSession to register
     * @param flags XA flags (must be XAResource.TMNOFLAGS for new transaction)
     * @throws XAException if registration fails
     */
    public void registerExistingSession(XidKey xid, BackendSession session, int flags) 
            throws XAException;
    
    /**
     * Start an XA transaction.
     * Borrows a BackendSession from pool if not already allocated.
     * 
     * @param xid transaction ID
     * @param flags XA flags
     * @throws XAException if start fails
     */
    public void xaStart(XidKey xid, int flags) throws XAException;
    
    /**
     * End an XA transaction.
     * 
     * @param xid transaction ID
     * @param flags XA flags
     * @throws XAException if end fails
     */
    public void xaEnd(XidKey xid, int flags) throws XAException;
    
    /**
     * Prepare an XA transaction (phase 1 of 2PC).
     * 
     * @param xid transaction ID
     * @return XAResource.XA_OK or XAResource.XA_RDONLY
     * @throws XAException if prepare fails
     */
    public int xaPrepare(XidKey xid) throws XAException;
    
    /**
     * Commit an XA transaction (phase 2 of 2PC).
     * 
     * @param xid transaction ID
     * @param onePhase true for one-phase commit, false for two-phase
     * @throws XAException if commit fails
     */
    public void xaCommit(XidKey xid, boolean onePhase) throws XAException;
    
    /**
     * Rollback an XA transaction.
     * 
     * @param xid transaction ID
     * @throws XAException if rollback fails
     */
    public void xaRollback(XidKey xid) throws XAException;
    
    /**
     * Get the BackendSession associated with a transaction.
     * 
     * @param xid transaction ID
     * @return BackendSession or null if not found
     */
    public BackendSession getSessionForTransaction(XidKey xid);
    
    /**
     * Get the pooled XADataSource.
     * 
     * @return pooled XADataSource
     */
    public XADataSource getPooledXADataSource();
}
```

### TxContext

Transaction context maintaining state and session association.

```java
package org.openjproxy.xa.pool;

import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

public class TxContext {
    /**
     * Get the transaction ID.
     * 
     * @return XidKey
     */
    public XidKey getXid();
    
    /**
     * Get current transaction state.
     * 
     * @return TxState enum value
     */
    public TxState getState();
    
    /**
     * Get the BackendSession associated with this transaction.
     * 
     * @return BackendSession or null
     */
    public BackendSession getSession();
    
    /**
     * Get the actual Xid object (for reuse across XA operations).
     * 
     * @return Xid object or null
     */
    public Xid getActualXid();
    
    /**
     * Set the actual Xid object.
     * 
     * @param actualXid Xid to store
     */
    public void setActualXid(Xid actualXid);
    
    /**
     * Transition to ACTIVE state.
     * 
     * @param session BackendSession to associate
     * @throws XAException if transition is invalid
     */
    public synchronized void transitionToActive(BackendSession session) throws XAException;
    
    /**
     * Transition to ENDED state.
     * 
     * @throws XAException if transition is invalid
     */
    public synchronized void transitionToEnded() throws XAException;
    
    /**
     * Transition to PREPARED state.
     * 
     * @throws XAException if transition is invalid
     */
    public synchronized void transitionToPrepared() throws XAException;
    
    /**
     * Transition to COMMITTED state.
     * 
     * @throws XAException if transition is invalid
     */
    public synchronized void transitionToCommitted() throws XAException;
    
    /**
     * Transition to ROLLEDBACK state.
     * 
     * @throws XAException if transition is invalid
     */
    public synchronized void transitionToRolledBack() throws XAException;
}
```

### TxState

Enum representing XA transaction states.

```java
package org.openjproxy.xa.pool;

public enum TxState {
    /**
     * Transaction does not exist yet.
     */
    NONEXISTENT,
    
    /**
     * Transaction is active (after start, before end).
     */
    ACTIVE,
    
    /**
     * Transaction has ended (after end, before prepare).
     */
    ENDED,
    
    /**
     * Transaction is prepared (after prepare, before commit/rollback).
     */
    PREPARED,
    
    /**
     * Transaction has been committed.
     */
    COMMITTED,
    
    /**
     * Transaction has been rolled back.
     */
    ROLLEDBACK
}
```

### XidKey

Immutable wrapper for Xid with proper hashCode/equals.

```java
package org.openjproxy.xa.pool;

import javax.transaction.xa.Xid;

public class XidKey {
    /**
     * Create XidKey from Xid.
     * 
     * @param xid XA transaction ID
     * @return XidKey instance
     */
    public static XidKey from(Xid xid);
    
    /**
     * Get format ID.
     * 
     * @return format identifier
     */
    public int getFormatId();
    
    /**
     * Get global transaction ID.
     * 
     * @return byte array of gtrid
     */
    public byte[] getGtrid();
    
    /**
     * Get branch qualifier.
     * 
     * @return byte array of bqual
     */
    public byte[] getBqual();
    
    /**
     * Convert to Xid.
     * 
     * @return Xid instance
     */
    public Xid toXid();
    
    /**
     * Check equality based on formatId, gtrid, and bqual.
     * 
     * @param obj object to compare
     * @return true if equal
     */
    @Override
    public boolean equals(Object obj);
    
    /**
     * Hash code based on formatId, gtrid, and bqual.
     * 
     * @return hash code
     */
    @Override
    public int hashCode();
    
    /**
     * Simple Xid implementation for creating Xid instances.
     */
    public static class SimpleXid implements Xid {
        public SimpleXid(int formatId, byte[] gtrid, byte[] bqual);
        
        @Override
        public int getFormatId();
        
        @Override
        public byte[] getGlobalTransactionId();
        
        @Override
        public byte[] getBranchQualifier();
    }
}
```

### XAPoolConfig

Configuration holder for XA pool settings.

```java
package org.openjproxy.xa.pool;

public class XAPoolConfig {
    /**
     * Get XADataSource class name.
     * 
     * @return class name
     */
    public String getXADataSourceClassName();
    
    /**
     * Get database URL.
     * 
     * @return JDBC URL
     */
    public String getUrl();
    
    /**
     * Get database username.
     * 
     * @return username or null
     */
    public String getUsername();
    
    /**
     * Get database password.
     * 
     * @return password or null
     */
    public String getPassword();
    
    /**
     * Get maximum pool size.
     * 
     * @return max connections
     */
    public int getMaxPoolSize();
    
    /**
     * Get minimum idle connections.
     * 
     * @return min idle
     */
    public int getMinIdle();
    
    /**
     * Get maximum wait time for connection (milliseconds).
     * 
     * @return max wait millis
     */
    public long getMaxWaitMillis();
    
    /**
     * Get idle timeout (minutes).
     * 
     * @return idle timeout
     */
    public int getIdleTimeoutMinutes();
    
    /**
     * Get maximum connection lifetime (minutes).
     * 
     * @return max lifetime
     */
    public int getMaxLifetimeMinutes();
}
```

## Implementation Classes

### BackendSessionImpl

Default implementation of BackendSession.

```java
package org.openjproxy.xa.pool;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.SQLException;

public class BackendSessionImpl implements BackendSession {
    /**
     * Create BackendSession from XAConnection.
     * 
     * @param xaConnection XA connection
     * @throws SQLException if connection retrieval fails
     */
    public BackendSessionImpl(XAConnection xaConnection) throws SQLException;
    
    @Override
    public XAConnection getXAConnection();
    
    @Override
    public Connection getConnection();
    
    @Override
    public XAResource getXAResource();
    
    @Override
    public void close() throws Exception;
}
```

### CommonsPool2XAProvider

Default XA pool provider using Apache Commons Pool 2.

```java
package org.openjproxy.xa.pool;

import javax.sql.XADataSource;
import java.util.Map;

public class CommonsPool2XAProvider implements XAConnectionPoolProvider {
    @Override
    public String getName();
    
    @Override
    public int getPriority();
    
    @Override
    public boolean supports(String databaseUrl);
    
    @Override
    public XADataSource createPooledXADataSource(Map<String, String> config) throws Exception;
    
    @Override
    public BackendSession borrowSession(XADataSource pooledDataSource) throws Exception;
}
```

## Exception Handling

### XAException Error Codes

The SPI preserves XA error codes for proper error handling:

| Error Code | Description |
|------------|-------------|
| `XAException.XAER_RMERR` | Resource manager error |
| `XAException.XAER_NOTA` | Unknown XID (transaction not found) |
| `XAException.XAER_INVAL` | Invalid arguments |
| `XAException.XAER_PROTO` | Protocol error (invalid state transition) |
| `XAException.XAER_RMFAIL` | Resource manager unavailable |
| `XAException.XAER_DUPID` | Duplicate XID |
| `XAException.XA_RBROLLBACK` | Transaction rolled back |

### Error Handling Example

```java
try {
    registry.xaCommit(xid, false);
} catch (XAException e) {
    switch (e.errorCode) {
        case XAException.XAER_NOTA:
            // Transaction not found - may have already committed
            log.warn("Transaction not found: " + xid);
            break;
        case XAException.XAER_INVAL:
            // Invalid arguments
            log.error("Invalid commit parameters", e);
            throw e;
        case XAException.XAER_PROTO:
            // Wrong state
            log.error("Invalid transaction state for commit", e);
            throw e;
        default:
            log.error("XA commit failed", e);
            throw e;
    }
}
```

## Thread Safety

All core classes are thread-safe:

- **XATransactionRegistry**: Uses ConcurrentHashMap for transaction contexts
- **TxContext**: Synchronized state transitions
- **BackendSessionImpl**: Immutable after creation
- **XidKey**: Immutable

## ServiceLoader Integration

Providers are discovered via Java's ServiceLoader mechanism:

1. Create implementation of `XAConnectionPoolProvider`
2. Register in `META-INF/services/org.openjproxy.xa.pool.XAConnectionPoolProvider`
3. Provider with highest priority is selected for each database URL

## Best Practices

### 1. Always Preserve Error Codes

```java
// GOOD - rethrow XAException to preserve error code
catch (XAException e) {
    log.error("XA operation failed", e);
    throw e;
}

// BAD - wraps and loses error code
catch (XAException e) {
    throw new XAException(XAException.XAER_RMERR);
}
```

### 2. Reuse Xid Objects

```java
// GOOD - reuse same Xid object
Xid actualXid = xidKey.toXid();
xaResource.start(actualXid, XAResource.TMNOFLAGS);
xaResource.end(actualXid, XAResource.TMSUCCESS);

// BAD - creates new Xid for each operation
xaResource.start(xidKey.toXid(), XAResource.TMNOFLAGS);
xaResource.end(xidKey.toXid(), XAResource.TMSUCCESS);
```

### 3. Validate State Transitions

```java
// Always validate state before transition
if (currentState != TxState.ENDED) {
    throw new XAException(XAException.XAER_PROTO);
}
txContext.transitionToPrepared();
```

### 4. Clean Up on Error

```java
BackendSession session = null;
try {
    session = borrowSession(xaDataSource);
    // ... use session ...
} catch (Exception e) {
    if (session != null) {
        try { session.close(); } catch (Exception ignored) {}
    }
    throw e;
}
```

## Related Documentation

- [Implementation Guide](./IMPLEMENTATION_GUIDE.md) - How to implement a provider
- [Oracle UCP Example](./ORACLE_UCP_EXAMPLE.md) - Complete Oracle implementation
- [Configuration Reference](./CONFIGURATION.md) - All configuration options
- [Troubleshooting Guide](./TROUBLESHOOTING.md) - Common issues

## Support

For API questions or issues:
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- API Javadocs: (coming soon)
