# Transaction Isolation Handling in OJP

## Overview

This document explains how OJP handles transaction isolation levels to prevent connection state pollution between different client sessions.

## Problem Statement

When multiple clients share a connection pool, transaction isolation levels can cause state pollution if not properly managed:

1. **Client A** gets a connection from the pool (default isolation: `READ_COMMITTED`)
2. **Client A** changes isolation to `SERIALIZABLE` for a specific operation
3. **Client A** returns the connection to the pool
4. **Client B** gets the same connection from the pool
5. **Client B** unexpectedly has `SERIALIZABLE` isolation instead of `READ_COMMITTED`

This can lead to:
- Unexpected transaction behavior
- Performance degradation (stricter isolation levels can cause more locking)
- Subtle bugs that are difficult to diagnose

## Solution

OJP automatically detects and configures the default transaction isolation level for each datasource, ensuring that connections are reset to their default state when returned to the pool.

### How It Works

1. **Detection Phase**: When a datasource is first created, OJP:
   - Creates a temporary connection
   - Queries the database's default transaction isolation level
   - Stores this value for future use

2. **Configuration Phase**: OJP reconfigures the connection pool with:
   - The detected default transaction isolation level
   - Connection pool settings to reset isolation on connection return

3. **Runtime Behavior**:
   - Clients can change transaction isolation during their session
   - When a session terminates, the connection returns to the pool
   - The pool automatically resets the isolation to the default level
   - The next client gets a clean connection with default isolation

## Implementation Details

### Connection Pool Configuration

#### HikariCP (Default)

HikariCP is configured using the `transactionIsolation` property:

```java
hikariConfig.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
```

This tells HikariCP to reset connections to this isolation level when they return to the pool.

#### Apache Commons DBCP2

DBCP2 is configured using the `defaultTransactionIsolation` property:

```java
dataSource.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
```

### Code Flow

1. **StatementServiceImpl.registerClient()**:
   ```java
   // Create initial datasource
   DataSource ds = ConnectionPoolProviderRegistry.createDataSource(poolConfig);
   
   // Detect default transaction isolation
   try (Connection testConn = ds.getConnection()) {
       int defaultIsolation = testConn.getTransactionIsolation();
       
       // Recreate datasource with proper configuration
       poolConfig = PoolConfig.builder()
           .defaultTransactionIsolation(defaultIsolation)
           // ... other settings
           .build();
       
       ds = ConnectionPoolProviderRegistry.createDataSource(poolConfig);
   }
   ```

2. **PoolConfig**:
   ```java
   public class PoolConfig {
       private final Integer defaultTransactionIsolation;
       
       public Integer getDefaultTransactionIsolation() {
           return defaultTransactionIsolation;
       }
   }
   ```

3. **HikariConnectionPoolProvider**:
   ```java
   if (config.getDefaultTransactionIsolation() != null) {
       String isolationLevel = mapTransactionIsolationToString(
           config.getDefaultTransactionIsolation());
       hikariConfig.setTransactionIsolation(isolationLevel);
   }
   ```

## Session Pinning

### How Sessions Work

- Each client connection creates a **session** on the OJP server
- The session holds a reference to a physical database connection
- The connection remains pinned to the session until termination
- Multiple statements can be executed within the same session

### Session Lifecycle

```
Client connects → Session created → Connection acquired from pool
                      ↓
Client executes statements (connection remains pinned)
                      ↓
Client changes isolation → Connection isolation changed
                      ↓
Client closes connection → Session terminated
                      ↓
Connection returned to pool → Isolation reset to default
```

## Supported Isolation Levels

OJP supports all standard JDBC transaction isolation levels:

| Level | Value | Description |
|-------|-------|-------------|
| `TRANSACTION_NONE` | 0 | Transactions are not supported |
| `TRANSACTION_READ_UNCOMMITTED` | 1 | Dirty reads, non-repeatable reads, and phantom reads can occur |
| `TRANSACTION_READ_COMMITTED` | 2 | Dirty reads are prevented; non-repeatable reads and phantom reads can occur |
| `TRANSACTION_REPEATABLE_READ` | 4 | Dirty reads and non-repeatable reads are prevented; phantom reads can occur |
| `TRANSACTION_SERIALIZABLE` | 8 | Dirty reads, non-repeatable reads, and phantom reads are prevented |

## Configuration

### Automatic Detection (Default)

By default, OJP automatically detects the database's default transaction isolation level. No configuration required.

### Manual Configuration

You can explicitly set a default transaction isolation level via properties. This is useful when:
- You want all connections to use a specific isolation level regardless of database default
- Different applications sharing the same database need different isolation guarantees
- You want to enforce a stricter or more relaxed isolation policy

**Configuration Properties:**

For regular (non-XA) connections:
```properties
ojp.connection.pool.defaultTransactionIsolation=READ_COMMITTED
```

For XA (distributed transaction) connections:
```properties
ojp.xa.connection.pool.defaultTransactionIsolation=READ_COMMITTED
```

**Valid Values:**

You can specify isolation levels in multiple formats (case-insensitive):

| String Name | Constant Name | Numeric Value | Description |
|-------------|---------------|---------------|-------------|
| `NONE` | `TRANSACTION_NONE` | `0` | Transactions not supported |
| `READ_UNCOMMITTED` | `TRANSACTION_READ_UNCOMMITTED` | `1` | Lowest isolation - dirty reads allowed |
| `READ_COMMITTED` | `TRANSACTION_READ_COMMITTED` | `2` | Most common - prevents dirty reads |
| `REPEATABLE_READ` | `TRANSACTION_REPEATABLE_READ` | `4` | Prevents non-repeatable reads |
| `SERIALIZABLE` | `TRANSACTION_SERIALIZABLE` | `8` | Highest isolation - fully isolated |

**Examples:**

```properties
# Using string name (recommended - most readable)
ojp.connection.pool.defaultTransactionIsolation=READ_COMMITTED

# Using constant name
ojp.connection.pool.defaultTransactionIsolation=TRANSACTION_SERIALIZABLE

# Using numeric value
ojp.connection.pool.defaultTransactionIsolation=2

# For XA connections
ojp.xa.connection.pool.defaultTransactionIsolation=SERIALIZABLE
```

**Behavior:**

- **When configured**: All connections will be reset to this configured isolation level when returned to the pool
- **When not configured**: OJP auto-detects the database default and uses that for reset
- **Invalid values**: Logged as warning, auto-detection will be used instead
- **Per-datasource**: Configuration is per-datasource in multi-datasource setups

**Performance Note:** When a custom isolation level is configured, OJP creates the datasource only once (no double-creation needed), making it slightly more efficient than auto-detection.

## Testing

### Unit Tests

The implementation includes comprehensive unit tests in:
- `HikariConnectionPoolProviderTest.java`
- `DbcpConnectionPoolProviderTest.java` (if applicable)

Key test scenarios:
1. Configuration verification
2. Isolation reset on connection return
3. Multiple clients with different isolation levels
4. All supported isolation levels

### Integration Tests

Integration tests in:
- `TransactionIsolationResetTest.java`

These tests verify end-to-end behavior with actual OJP server and client connections.

## Performance Considerations

### Impact

The transaction isolation reset feature has minimal performance impact:

1. **One-time Detection**: Isolation level detection happens once when the datasource is created
2. **Datasource Recreation**: The datasource is closed and recreated once during initialization
3. **Connection Return**: HikariCP and DBCP2 handle isolation reset efficiently

### Benefits

- **Prevents State Pollution**: Ensures clean connections for each session
- **Predictable Behavior**: Clients always get connections with expected isolation
- **Safer Concurrent Access**: Multiple clients can safely use different isolation levels

## Best Practices

### For Application Developers

1. **Use Default Isolation**: Most applications should use the database's default isolation level
2. **Change Temporarily**: Only change isolation for specific transactions that require it
3. **Document Requirements**: Document why you're changing isolation levels
4. **Test Thoroughly**: Test your application with different isolation levels

### For OJP Administrators

1. **Monitor Logs**: Check server logs for isolation detection messages
2. **Verify Configuration**: Ensure datasources are configured correctly
3. **Performance Testing**: Test with realistic workloads to ensure proper behavior

## Troubleshooting

### Issue: Unexpected Isolation Level

**Symptom**: Client receives connection with unexpected transaction isolation level

**Possible Causes**:
1. Isolation reset not configured (pre-fix behavior)
2. Client changed isolation but didn't terminate session
3. Database doesn't support the requested isolation level

**Solution**:
- Verify OJP version includes isolation reset feature
- Check server logs for isolation detection messages
- Ensure clients properly close connections

### Issue: Performance Degradation

**Symptom**: Slower query performance after isolation level changes

**Possible Causes**:
1. Using stricter isolation level than needed (e.g., SERIALIZABLE)
2. Database locks more aggressively with higher isolation

**Solution**:
- Use the minimum isolation level required for your use case
- Review application logic to minimize isolation level changes
- Consider using READ_COMMITTED for most operations

## Migration Guide

### Upgrading from Pre-Fix Versions

If you're upgrading from a version without transaction isolation reset:

1. **No Configuration Required**: The feature is enabled automatically
2. **Verify Behavior**: Run your test suite to ensure no unexpected changes
3. **Monitor Logs**: Check for isolation detection messages during startup
4. **Clean Slate**: Existing connections will be cleaned when returned to pool

### Potential Issues

- Applications relying on isolation level "stickiness" will need updates
- Test cases expecting connection state persistence may fail
- Monitor for any application-specific isolation level assumptions

## Future Enhancements

Potential improvements for future versions:

1. **Configurable Detection**: Allow manual override of detected isolation level
2. **Per-DataSource Configuration**: Configure different isolation levels per datasource
3. **Metrics**: Track isolation level changes and reset operations
4. **Warnings**: Log warnings when clients change isolation frequently

## References

- [JDBC Specification - Transaction Isolation](https://docs.oracle.com/javase/tutorial/jdbc/basics/transactions.html)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
- [Apache Commons DBCP Configuration](https://commons.apache.org/proper/commons-dbcp/configuration.html)
- [Connection Pooling Best Practices](https://vladmihalcea.com/the-anatomy-of-connection-pooling/)
