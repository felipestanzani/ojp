# XA Connection Pool Provider Implementation Guide

## Overview

This guide provides step-by-step instructions for implementing a custom XA connection pool provider for Open-J-Proxy.

## Prerequisites

- Java 21 or higher
- Understanding of XA/2PC protocols
- Familiarity with connection pooling concepts
- Knowledge of the target pooling library (e.g., HikariCP, Oracle UCP, etc.)

## Step 1: Implement the XAConnectionPoolProvider Interface

```java
package com.example.myprovider;

import org.openjproxy.xa.pool.*;
import javax.sql.XADataSource;
import java.util.Map;

public class MyXAPoolProvider implements XAConnectionPoolProvider {
    
    @Override
    public String getName() {
        return "MyXAPoolProvider";
    }
    
    @Override
    public int getPriority() {
        // Higher values = higher priority
        // Default provider (CommonsPool2XAProvider) uses 0
        // Oracle UCP provider uses 50
        return 10;
    }
    
    @Override
    public boolean supports(String databaseUrl) {
        // Return true if this provider should handle the given database URL
        // Example: return databaseUrl.contains(":oracle:");
        return true; // Support all databases
    }
    
    @Override
    public XADataSource createPooledXADataSource(Map<String, String> config) throws Exception {
        // 1. Extract configuration
        String xaDataSourceClassName = config.get("xa.datasource.className");
        String url = config.get("xa.url");
        String username = config.get("xa.username");
        String password = config.get("xa.password");
        int maxPoolSize = Integer.parseInt(config.getOrDefault("xa.maxPoolSize", "10"));
        
        // 2. Create underlying XADataSource using reflection
        Class<?> xaDataSourceClass = Class.forName(xaDataSourceClassName);
        XADataSource underlyingXADataSource = (XADataSource) xaDataSourceClass.getDeclaredConstructor().newInstance();
        
        // 3. Configure the XADataSource (database-specific)
        configureXADataSource(underlyingXADataSource, url, username, password);
        
        // 4. Wrap with your pooling implementation
        return new MyPooledXADataSource(underlyingXADataSource, maxPoolSize);
    }
    
    @Override
    public BackendSession borrowSession(XADataSource pooledDataSource) throws Exception {
        if (!(pooledDataSource instanceof MyPooledXADataSource)) {
            throw new IllegalArgumentException("Unsupported XADataSource type");
        }
        
        MyPooledXADataSource myPool = (MyPooledXADataSource) pooledDataSource;
        return myPool.borrowSession();
    }
    
    private void configureXADataSource(XADataSource xaDataSource, String url, 
                                       String username, String password) throws Exception {
        // Use reflection to set properties
        Class<?> clazz = xaDataSource.getClass();
        
        // Set URL (method name varies by database)
        try {
            clazz.getMethod("setURL", String.class).invoke(xaDataSource, url);
        } catch (NoSuchMethodException e) {
            try {
                clazz.getMethod("setUrl", String.class).invoke(xaDataSource, url);
            } catch (NoSuchMethodException e2) {
                // Handle database-specific URL setting
            }
        }
        
        // Set credentials
        if (username != null) {
            clazz.getMethod("setUser", String.class).invoke(xaDataSource, username);
        }
        if (password != null) {
            clazz.getMethod("setPassword", String.class).invoke(xaDataSource, password);
        }
    }
}
```

## Step 2: Implement the Pooled XADataSource Wrapper

```java
package com.example.myprovider;

import javax.sql.XADataSource;
import javax.sql.XAConnection;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.logging.Logger;

public class MyPooledXADataSource implements XADataSource {
    private final XADataSource underlyingDataSource;
    private final MyConnectionPool pool;
    
    public MyPooledXADataSource(XADataSource underlyingDataSource, int maxPoolSize) {
        this.underlyingDataSource = underlyingDataSource;
        this.pool = new MyConnectionPool(underlyingDataSource, maxPoolSize);
    }
    
    @Override
    public XAConnection getXAConnection() throws SQLException {
        throw new UnsupportedOperationException(
            "Use borrowSession() from XAConnectionPoolProvider instead");
    }
    
    @Override
    public XAConnection getXAConnection(String user, String password) throws SQLException {
        throw new UnsupportedOperationException(
            "Use borrowSession() from XAConnectionPoolProvider instead");
    }
    
    public BackendSession borrowSession() throws Exception {
        return pool.borrowSession();
    }
    
    // Implement remaining XADataSource methods...
    
    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return underlyingDataSource.getLogWriter();
    }
    
    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        underlyingDataSource.setLogWriter(out);
    }
    
    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        underlyingDataSource.setLoginTimeout(seconds);
    }
    
    @Override
    public int getLoginTimeout() throws SQLException {
        return underlyingDataSource.getLoginTimeout();
    }
    
    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("MyPooledXADataSource");
    }
}
```

## Step 3: Implement the Connection Pool

```java
package com.example.myprovider;

import org.openjproxy.xa.pool.BackendSession;
import org.openjproxy.xa.pool.BackendSessionImpl;
import javax.sql.XADataSource;
import javax.sql.XAConnection;
import java.sql.SQLException;
import java.util.concurrent.*;

public class MyConnectionPool {
    private final XADataSource xaDataSource;
    private final BlockingQueue<BackendSession> pool;
    private final int maxPoolSize;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    
    public MyConnectionPool(XADataSource xaDataSource, int maxPoolSize) {
        this.xaDataSource = xaDataSource;
        this.maxPoolSize = maxPoolSize;
        this.pool = new LinkedBlockingQueue<>();
    }
    
    public BackendSession borrowSession() throws Exception {
        // Try to get from pool
        BackendSession session = pool.poll();
        
        if (session != null) {
            // Validate session
            if (isValid(session)) {
                return session;
            } else {
                // Session invalid, close and create new
                closeSession(session);
            }
        }
        
        // Create new session if under limit
        if (activeCount.get() < maxPoolSize) {
            activeCount.incrementAndGet();
            XAConnection xaConn = xaDataSource.getXAConnection();
            return new BackendSessionImpl(xaConn);
        }
        
        // Wait for available session (with timeout)
        session = pool.poll(30, TimeUnit.SECONDS);
        if (session == null) {
            throw new SQLException("Pool exhausted - no sessions available");
        }
        
        return session;
    }
    
    public void returnSession(BackendSession session) {
        if (session != null && isValid(session)) {
            pool.offer(session);
        } else {
            activeCount.decrementAndGet();
            closeSession(session);
        }
    }
    
    private boolean isValid(BackendSession session) {
        try {
            // Validate connection is still alive
            return session.getConnection().isValid(5);
        } catch (Exception e) {
            return false;
        }
    }
    
    private void closeSession(BackendSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                // Log error
            }
        }
    }
    
    public void shutdown() {
        BackendSession session;
        while ((session = pool.poll()) != null) {
            closeSession(session);
        }
    }
}
```

## Step 4: Register via ServiceLoader

Create file: `src/main/resources/META-INF/services/org.openjproxy.xa.pool.XAConnectionPoolProvider`

```
com.example.myprovider.MyXAPoolProvider
```

## Step 5: Configuration

Add provider-specific configuration support:

```java
public XADataSource createPooledXADataSource(Map<String, String> config) throws Exception {
    // Standard configuration
    int maxPoolSize = Integer.parseInt(config.getOrDefault("xa.maxPoolSize", "10"));
    int minIdle = Integer.parseInt(config.getOrDefault("xa.minIdle", "2"));
    long maxWaitMillis = Long.parseLong(config.getOrDefault("xa.maxWaitMillis", "30000"));
    
    // Provider-specific configuration
    boolean enableJMX = Boolean.parseBoolean(config.getOrDefault("xa.myprovider.enableJMX", "false"));
    int validationInterval = Integer.parseInt(config.getOrDefault("xa.myprovider.validationInterval", "30000"));
    
    // Create and configure pool
    MyPoolConfig poolConfig = new MyPoolConfig();
    poolConfig.setMaxPoolSize(maxPoolSize);
    poolConfig.setMinIdle(minIdle);
    poolConfig.setMaxWaitMillis(maxWaitMillis);
    poolConfig.setEnableJMX(enableJMX);
    poolConfig.setValidationInterval(validationInterval);
    
    return createPooledDataSource(poolConfig);
}
```

## Step 6: Testing

```java
@Test
public void testMyXAPoolProvider() throws Exception {
    // 1. Create configuration
    Map<String, String> config = new HashMap<>();
    config.put("xa.datasource.className", "org.postgresql.xa.PGXADataSource");
    config.put("xa.url", "postgresql://localhost:5432/testdb");
    config.put("xa.username", "testuser");
    config.put("xa.password", "testpass");
    config.put("xa.maxPoolSize", "5");
    
    // 2. Create provider
    MyXAPoolProvider provider = new MyXAPoolProvider();
    
    // 3. Create pooled datasource
    XADataSource xaDataSource = provider.createPooledXADataSource(config);
    
    // 4. Borrow session
    BackendSession session = provider.borrowSession(xaDataSource);
    assertNotNull(session);
    assertNotNull(session.getXAConnection());
    assertNotNull(session.getConnection());
    assertNotNull(session.getXAResource());
    
    // 5. Test XA operations
    Xid xid = new MyXid();
    session.getXAResource().start(xid, XAResource.TMNOFLAGS);
    // ... execute SQL ...
    session.getXAResource().end(xid, XAResource.TMSUCCESS);
    session.getXAResource().prepare(xid);
    session.getXAResource().commit(xid, false);
    
    // 6. Return session
    session.close();
}
```

## Best Practices

### 1. Connection Validation

Always validate connections before returning from pool:

```java
private boolean validateConnection(Connection conn) {
    try {
        return conn != null && !conn.isClosed() && conn.isValid(5);
    } catch (SQLException e) {
        return false;
    }
}
```

### 2. Resource Cleanup

Ensure proper cleanup in all error paths:

```java
public BackendSession borrowSession() throws Exception {
    XAConnection xaConn = null;
    try {
        xaConn = createXAConnection();
        return new BackendSessionImpl(xaConn);
    } catch (Exception e) {
        if (xaConn != null) {
            try { xaConn.close(); } catch (Exception ignored) {}
        }
        throw e;
    }
}
```

### 3. Thread Safety

Make all pool operations thread-safe:

```java
private final ConcurrentHashMap<BackendSession, Long> activeSessions = new ConcurrentHashMap<>();
private final ReentrantLock poolLock = new ReentrantLock();

public BackendSession borrowSession() throws Exception {
    poolLock.lock();
    try {
        BackendSession session = pool.take();
        activeSessions.put(session, System.currentTimeMillis());
        return session;
    } finally {
        poolLock.unlock();
    }
}
```

### 4. Metrics and Monitoring

Expose pool metrics:

```java
public interface PoolMetrics {
    int getActiveConnections();
    int getIdleConnections();
    int getTotalConnections();
    long getAverageWaitTime();
    long getMaxWaitTime();
}
```

### 5. Configuration Validation

Validate configuration on startup:

```java
private void validateConfig(Map<String, String> config) {
    if (!config.containsKey("xa.datasource.className")) {
        throw new IllegalArgumentException("xa.datasource.className is required");
    }
    
    int maxPoolSize = Integer.parseInt(config.getOrDefault("xa.maxPoolSize", "10"));
    int minIdle = Integer.parseInt(config.getOrDefault("xa.minIdle", "2"));
    
    if (minIdle > maxPoolSize) {
        throw new IllegalArgumentException("minIdle cannot exceed maxPoolSize");
    }
}
```

## Common Pitfalls

### 1. Not Handling Xid Object Identity

PostgreSQL and some other databases compare Xid by object identity (`==`), not by value. Always reuse the same Xid object:

```java
// WRONG - creates new Xid for each operation
xaResource.start(xid.toXid(), XAResource.TMNOFLAGS);
xaResource.end(xid.toXid(), XAResource.TMSUCCESS);  // Different object!

// CORRECT - reuse same Xid object
Xid actualXid = xid.toXid();
xaResource.start(actualXid, XAResource.TMNOFLAGS);
xaResource.end(actualXid, XAResource.TMSUCCESS);
```

### 2. Returning Sessions to Pool Too Early

Sessions must stay bound to OJP Session for multiple transactions:

```java
// WRONG - return after each transaction
xaCommit() {
    // ... commit ...
    returnSessionToPool(session);  // BAD!
}

// CORRECT - return only on session termination
terminate() {
    if (backendSession != null) {
        returnSessionToPool(backendSession);
    }
}
```

### 3. Not Implementing Proper State Machine

XA requires strict state transitions:

```java
public enum TxState {
    NONEXISTENT,
    ACTIVE,      // After start()
    ENDED,       // After end()
    PREPARED,    // After prepare()
    COMMITTED,   // After commit()
    ROLLEDBACK   // After rollback()
}
```

## Performance Optimization

### 1. Connection Pre-Warming

Pre-create minimum idle connections:

```java
public void warmUp() {
    for (int i = 0; i < minIdle; i++) {
        try {
            BackendSession session = createSession();
            pool.offer(session);
        } catch (Exception e) {
            log.warn("Failed to warm up connection", e);
        }
    }
}
```

### 2. Connection Eviction

Remove idle connections periodically:

```java
private void scheduleEviction() {
    scheduledExecutor.scheduleAtFixedRate(() -> {
        long now = System.currentTimeMillis();
        pool.removeIf(session -> {
            Long created = sessionTimestamps.get(session);
            return created != null && (now - created) > maxIdleTimeMillis;
        });
    }, 60, 60, TimeUnit.SECONDS);
}
```

### 3. Statement Caching

Cache prepared statements per session:

```java
public class BackendSessionImpl implements BackendSession {
    private final Map<String, PreparedStatement> statementCache = new ConcurrentHashMap<>();
    
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return statementCache.computeIfAbsent(sql, 
            s -> connection.prepareStatement(s));
    }
}
```

## Next Steps

- Review [Oracle UCP Example](./ORACLE_UCP_EXAMPLE.md) for a complete implementation
- See [API Reference](./API_REFERENCE.md) for detailed interface documentation
- Check [Configuration Reference](./CONFIGURATION.md) for all configuration options
- Read [Troubleshooting Guide](./TROUBLESHOOTING.md) for common issues

## Support

For questions or issues:
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- Discussions: https://github.com/Open-J-Proxy/ojp/discussions
