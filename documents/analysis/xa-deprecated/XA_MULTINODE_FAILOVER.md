# XA Transaction Retry and Failover in Multinode Deployments

## Overview

This document describes the XA transaction retry and proactive failover implementation for multinode OJP deployments. When one or more OJP servers fail in a multinode cluster, the XA transaction infrastructure automatically handles failures to maintain transaction availability.

## The Challenge

In distributed XA transactions, connections are bound to specific resource managers (OJP servers). The XA protocol has strict constraints:

- **Pre-Prepare**: Transactions can migrate between servers (safe to retry)
- **Post-Prepare**: Transactions CANNOT migrate between servers (XA protocol constraint)

When an OJP server fails:
1. XA connections bound to that server become unusable
2. First transaction attempt fails with connection error
3. Atomikos pool contains orphaned connections to failed server
4. New transactions may attempt to use these orphaned connections

## Solution Architecture

The implementation uses a two-phase approach:

### Phase 1: XA Start Retry Logic

Automatically retries `xaStart()` operations when the bound server is unavailable, recreating the session on a healthy server.

**Why Safe**: No transaction state exists before `xaStart()`, so migration to a different server is safe.

### Phase 2: Proactive Connection Cleanup

Monitors server health and proactively closes connections bound to failed servers, allowing Atomikos to create fresh connections to healthy servers.

**Why Needed**: Prevents connection pool exhaustion from orphaned connections and reduces failure window.

## Implementation Details

### Phase 1: XA Start Retry

#### Components Modified

**OjpXAResource** - XA transaction control
- Added retry loop in `start()` method
- Detects connection-level errors vs database-level errors
- Recreates session on different server via `OjpXAConnection.recreateSession()`
- Retries up to number of healthy servers available

**OjpXAConnection** - XA connection management
- Added `recreateSession()` method
- Clears existing session and creates new one
- Round-robin selection automatically chooses different server
- Updates `XAResource` with new session

#### Error Detection

Connection-level errors trigger retry (server failures):
- `UNAVAILABLE` - Server not reachable
- `DEADLINE_EXCEEDED` - Request timeout
- `CANCELLED` - Connection cancelled
- `UNKNOWN` with "connection" in message

Database-level errors fail fast (no retry):
- SQL syntax errors
- Constraint violations
- Permission denied
- Table not found

#### Retry Logic

```java
// In OjpXAResource.start()
int maxRetries = getMaxRetries(); // Number of healthy servers
int attempt = 0;

while (attempt < maxRetries) {
    try {
        // Attempt xaStart
        XaStartRequest request = XaStartRequest.newBuilder()
                .setSession(sessionInfo)
                .setXid(toXidProto(xid))
                .setFlags(flags)
                .build();
        XaResponse response = statementService.xaStart(request);
        return; // Success
        
    } catch (Exception e) {
        if (!isConnectionLevelError(e)) {
            throw; // Database error - don't retry
        }
        
        attempt++;
        if (attempt < maxRetries) {
            // Recreate session on different server
            this.sessionInfo = xaConnection.recreateSession();
        }
    }
}
```

#### Retry Limits

- Maximum retries = number of healthy servers in cluster
- Minimum retries = 1 (always try at least once)
- For single-server deployments: 1 retry (current server)
- For 3-server cluster: Up to 3 retries

### Phase 2: Proactive Connection Cleanup

#### Components Created

**ServerHealthListener** (new interface)
```java
public interface ServerHealthListener {
    void onServerUnhealthy(ServerEndpoint endpoint, Exception exception);
    void onServerRecovered(ServerEndpoint endpoint);
}
```

**MultinodeConnectionManager** - Server health monitoring
- Maintains list of registered `ServerHealthListener` instances
- Calls `notifyServerUnhealthy()` when server marked unhealthy
- Calls `notifyServerRecovered()` when server recovers
- Thread-safe listener registration/notification

**OjpXAConnection** - Implements health monitoring
- Implements `ServerHealthListener` interface
- Tracks which server it's bound to via `boundServerAddress`
- Closes itself when bound server becomes unhealthy
- Atomikos pool automatically removes closed connections

**OjpXADataSource** - Listener registration
- Registers each `XAConnection` as health listener when created
- Only for multinode deployments (checks if `StatementService` is `MultinodeStatementService`)

#### Health Notification Flow

```
Server Failure Detected
    ↓
MultinodeConnectionManager.handleServerFailure()
    ↓
notifyServerUnhealthy(endpoint, exception)
    ↓
For each registered ServerHealthListener:
    ↓
listener.onServerUnhealthy(endpoint, exception)
    ↓
OjpXAConnection checks if bound to failed server
    ↓
If bound: xaConnection.close()
    ↓
Atomikos pool removes closed connection
    ↓
Next transaction attempt creates new connection
    ↓
New connection binds to healthy server
```

#### Server Health Detection

Servers are marked unhealthy for:
- gRPC UNAVAILABLE status
- gRPC DEADLINE_EXCEEDED status
- gRPC CANCELLED status
- Network connection errors
- Timeout errors

Servers are NOT marked unhealthy for:
- Database-level errors
- SQL syntax errors
- Constraint violations
- Permission errors

## Benefits

### Improved Availability
- **First Transaction Survives**: `xaStart()` failures automatically retry on different servers
- **Subsequent Transactions Succeed**: Proactive cleanup ensures healthy connections
- **Minimal Downtime**: Window between failure and recovery reduced significantly

### Connection Pool Health
- **No Orphaned Connections**: Failed connections removed proactively
- **Efficient Resource Use**: Pool contains only healthy connections
- **Automatic Recovery**: System self-heals as servers recover

### XA Protocol Compliance
- **Respects Prepare Boundary**: Only `xaStart()` retries; prepared transactions fail fast
- **No Unsafe Migration**: Post-prepare transactions stay with original server
- **Transaction Integrity**: Distributed transaction guarantees maintained

## Usage

### Configuration

No configuration changes required - the functionality is automatic for multinode deployments.

### Connection String

Use multinode URL format:
```
jdbc:ojp[host1:port1,host2:port2,host3:port3]_postgresql://dbhost/dbname
```

### Code Example

Standard XA code works unchanged:

```java
// Create XA DataSource
OjpXADataSource xaDataSource = new OjpXADataSource();
xaDataSource.setUrl("jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost/mydb");
xaDataSource.setUser("username");
xaDataSource.setPassword("password");

// Get XA Connection (automatically registered for health monitoring)
XAConnection xaConnection = xaDataSource.getXAConnection();
XAResource xaResource = xaConnection.getXAResource();
Connection connection = xaConnection.getConnection();

// Transaction automatically retries if server fails during xaStart
Xid xid = createXid();
xaResource.start(xid, XAResource.TMNOFLAGS);

// Execute SQL
Statement stmt = connection.createStatement();
stmt.executeUpdate("INSERT INTO accounts VALUES (...)");

// Complete transaction
xaResource.end(xid, XAResource.TMSUCCESS);
int result = xaResource.prepare(xid);
xaResource.commit(xid, false);

// Clean up (triggers health listener deregistration)
connection.close();
xaConnection.close();
```

## Behavior During Failures

### Scenario 1: Server Fails Before xaStart

```
1. Transaction manager calls xaResource.start(xid)
2. Request sent to bound server (Server A)
3. Server A is down - connection error
4. OjpXAResource detects connection-level error
5. Calls xaConnection.recreateSession()
6. New session created on healthy server (Server B)
7. Retry xaStart on Server B
8. Success - transaction proceeds
```

**Result**: Transaction succeeds, application unaware of failure.

### Scenario 2: Server Fails After xaStart, Before xaPrepare

```
1. xaStart succeeds on Server A
2. Application executes SQL statements
3. Server A fails
4. Transaction manager calls xaResource.end(xid)
5. Connection error detected
6. No retry (transaction state exists)
7. XAException thrown
8. Transaction manager rolls back
```

**Result**: Transaction fails, requires retry by application/transaction manager.

### Scenario 3: Server Fails After xaPrepare

```
1. Transaction prepared on Server A
2. Server A fails
3. Transaction manager calls xaResource.commit(xid)
4. Connection error detected
5. No retry (transaction in prepared state)
6. XAException thrown
7. Transaction remains in prepared state in database
8. Requires manual recovery or Server A restart
```

**Result**: Transaction suspended, requires recovery. This is inherent to XA protocol.

### Scenario 4: Proactive Cleanup

```
1. Multiple XA connections exist, some bound to Server A
2. Server A fails
3. MultinodeConnectionManager detects failure
4. notifyServerUnhealthy() called
5. All XAConnections bound to Server A close themselves
6. Atomikos pool removes closed connections
7. Next transaction attempt:
   - Atomikos creates new XAConnection
   - Binds to healthy server (Server B or C)
   - Transaction proceeds normally
```

**Result**: Clean pool, no orphaned connections, fast recovery.

## Limitations

### Unavoidable Limitations (XA Protocol)

1. **Post-Prepare Failures**: Transactions that have been prepared cannot migrate servers
   - **Why**: XA prepared state is tied to specific resource manager
   - **Impact**: Must wait for server recovery or perform manual recovery
   - **Mitigation**: None - this is XA protocol requirement

2. **Mid-Transaction Failures**: Transactions with work done cannot retry
   - **Why**: Transaction state (uncommitted changes) exists on failed server
   - **Impact**: Transaction must rollback
   - **Mitigation**: Application/TM must retry entire transaction

3. **First Transaction May Fail**: Very first transaction on orphaned connection may fail
   - **Why**: Time window between server failure and connection cleanup
   - **Impact**: One transaction failure before recovery
   - **Mitigation**: Proactive cleanup minimizes this window

### Implementation Limitations

1. **Retry Only for xaStart**: Other XA operations don't retry
   - **Rationale**: Only safe operation to retry without transaction state
   - **Impact**: Failures during end/prepare/commit require application retry
   
2. **Requires Multinode URL**: Only works with multinode connection strings
   - **Rationale**: Single-server deployments have no failover target
   - **Impact**: Single-server failures still cause downtime

## Performance Impact

### Overhead Introduced

1. **Health Listener Registration**:
   - Per-connection overhead: ~1 object reference
   - Memory impact: Negligible (few bytes per connection)
   
2. **Retry Logic**:
   - Only executed on connection failures
   - Adds ~1-2 seconds per retry (network timeout + reconnect)
   - Zero overhead for successful operations

3. **Proactive Cleanup**:
   - Triggered only on server failure
   - Connection close overhead: Milliseconds
   - Zero overhead during normal operation

### Performance Benefits

1. **Reduced Failure Window**: Faster recovery from server failures
2. **Clean Connection Pool**: No orphaned connections consuming resources
3. **Better Resource Distribution**: Connections automatically rebalance to healthy servers

## Monitoring and Debugging

### Log Messages

**Phase 1 - Retry Logic**:
```
WARN  o.o.jdbc.xa.OjpXAResource - xaStart failed with connection error (attempt 1/3): UNAVAILABLE. Attempting to recreate session...
INFO  o.o.jdbc.xa.OjpXAConnection - Recreating XA session (previous session: abc-123)
INFO  o.o.jdbc.xa.OjpXAResource - Session recreated successfully on attempt 1
INFO  o.o.jdbc.xa.OjpXAResource - xaStart succeeded on retry attempt 1
```

**Phase 2 - Proactive Cleanup**:
```
WARN  o.o.g.c.MultinodeConnectionManager - Marked server server1:1059 as unhealthy due to connection-level error: UNAVAILABLE
WARN  o.o.jdbc.xa.OjpXAConnection - XA connection bound to unhealthy server server1:1059, closing connection proactively
INFO  o.o.g.c.MultinodeConnectionManager - Successfully recovered server server1:1059
DEBUG o.o.jdbc.xa.OjpXAConnection - Server server1:1059 recovered
```

### Metrics to Monitor

1. **Connection Retry Count**: Track `xaStart()` retry attempts
2. **Server Health Changes**: Count of unhealthy/recovery events
3. **Connection Pool Size**: Monitor Atomikos pool size for leaks
4. **Transaction Failure Rate**: Track XA transaction failures
5. **Server Availability**: Monitor OJP server uptime

### Debugging Tips

1. **Enable Debug Logging**:
   ```properties
   logging.level.org.openjproxy.jdbc.xa=DEBUG
   logging.level.org.openjproxy.grpc.client=DEBUG
   ```

2. **Check Server Health**:
   - Look for "Marked server as unhealthy" messages
   - Verify servers recover with "Successfully recovered server" messages

3. **Verify Retry Logic**:
   - Look for "xaStart failed" with retry attempt numbers
   - Confirm "xaStart succeeded on retry attempt" messages

4. **Monitor Connection Cleanup**:
   - Watch for "closing connection proactively" messages
   - Verify Atomikos pool size doesn't grow unbounded

## Testing

### Unit Tests

Tests are included in `OjpXAResourceTest`:

```bash
mvn test -Dtest=OjpXAResourceTest -pl ojp-jdbc-driver
```

Tests cover:
- Retry logic for connection-level errors
- No retry for database-level errors
- Session recreation
- Maximum retry limits

### Integration Testing

To test multinode failover:

```bash
# Start 3 OJP servers
./start-ojp-server.sh server1 1059
./start-ojp-server.sh server2 1060
./start-ojp-server.sh server3 1061

# Run XA transaction test
mvn test -DmultinodeXATestsEnabled=true

# During test, kill one server
kill -9 <server1-pid>

# Observe:
# - First xaStart retries and succeeds
# - Subsequent transactions use healthy servers
# - Connections to failed server cleaned up
```

### Manual Testing Scenarios

1. **Test Retry Logic**:
   - Start transaction
   - Kill server before xaStart
   - Verify transaction succeeds via retry

2. **Test Proactive Cleanup**:
   - Create multiple connections
   - Kill one server
   - Verify bound connections close
   - Verify new connections use healthy servers

3. **Test Post-Prepare Failure**:
   - Prepare transaction
   - Kill server
   - Verify commit fails
   - Verify transaction in pg_prepared_xacts

## Comparison to Alternatives

### Why This Approach

**Alternative 1: No Retry, Fail Fast**
- ❌ Every server failure causes transaction failures
- ❌ Poor user experience
- ✅ Simple implementation

**Alternative 2: Retry All XA Operations**
- ❌ Violates XA protocol (unsafe migration after prepare)
- ❌ Risk of data corruption
- ✅ Better availability

**Alternative 3: Server-Side Session Migration**
- ❌ Very complex implementation
- ❌ Requires distributed transaction log
- ❌ Significant architectural changes
- ✅ Complete high availability

**Our Approach: Selective Retry + Proactive Cleanup**
- ✅ Safe (respects XA boundaries)
- ✅ Simple implementation
- ✅ Works within Atomikos lifecycle
- ✅ Significant availability improvement
- ✅ Minimal code changes

## Rationale and Design Decisions

### Why Retry Only xaStart?

**Decision**: Only `xaStart()` operation retries on server failure.

**Rationale**:
1. **No Transaction State**: Before `xaStart()`, no transaction state exists on the server
2. **Safe to Migrate**: Connection can safely bind to different server
3. **XA Protocol Compliance**: No violation of XA specification
4. **Maximum Impact**: Most common failure point is connection establishment

**Why Not Retry Others**:
- `xaEnd()`: Transaction branch has state on original server
- `xaPrepare()`: Critical phase, must not migrate
- `xaCommit()`: Transaction prepared on specific server
- `xaRollback()`: Transaction state tied to server

### Why Proactive Cleanup?

**Decision**: Close connections when bound server fails, rather than wait for Atomikos detection.

**Rationale**:
1. **Faster Recovery**: Immediate cleanup vs waiting for next borrow attempt
2. **Prevent Pool Exhaustion**: Failed connections don't consume pool slots
3. **Better Resource Distribution**: Connections rebalance to healthy servers
4. **Improved UX**: Reduces "window of failure" for users

**Alternative Considered**: Connection validation on borrow
- ❌ Slower - adds latency to every transaction
- ❌ Reactive - failure still occurs once before detection
- ✅ Proactive approach is superior

### Why Not Server-Side Session Migration?

**Decision**: Don't implement server-side transaction state migration.

**Rationale**:
1. **Complexity**: Would require distributed transaction log, coordination protocol
2. **XA Limitations**: Post-prepare transactions cannot migrate (protocol constraint)
3. **Diminishing Returns**: Complex solution for small benefit
4. **Maintenance Burden**: Significantly more code to maintain

**When Would It Be Worth It**:
- Mission-critical 24/7 systems where ANY transaction failure is unacceptable
- Willing to invest in distributed transaction coordinator
- Have dedicated team for complex distributed systems

### Why Health Listener Pattern?

**Decision**: Use observer pattern with `ServerHealthListener` interface.

**Rationale**:
1. **Loose Coupling**: XA layer doesn't need to know about connection manager internals
2. **Extensibility**: Easy to add more listeners (e.g., metrics, logging)
3. **Clean Architecture**: Separation of concerns between layers
4. **Testability**: Easy to mock and test independently

## Future Enhancements

Possible improvements for future versions:

1. **Phase 3: Connection Validation**
   - Implement `XAConnection.isValid()` check before XA operations
   - Proactive detection before Atomikos borrows connection
   - Would further reduce failure window

2. **Configurable Retry Policy**
   - Allow configuration of retry limits
   - Exponential backoff between retries
   - Circuit breaker pattern integration

3. **Enhanced Monitoring**
   - Expose JMX metrics for retry counts
   - Health check endpoints
   - Real-time dashboard of server health

4. **Recovery Coordinator**
   - Automated prepared transaction recovery
   - Distributed recovery across servers
   - Admin UI for manual intervention

5. **More Database Support**
   - Extend retry logic to more databases
   - Database-specific optimizations
   - Custom error detection per database

## References

- [XA Specification](https://pubs.opengroup.org/onlinepubs/009680699/toc.pdf) - Open Group XA Standard
- [JTA Specification](https://jcp.org/en/jsr/detail?id=907) - Java Transaction API
- [Atomikos Documentation](https://www.atomikos.com/Documentation/) - Transaction Manager
- [XA_TRANSACTION_FLOW.md](XA_TRANSACTION_FLOW.md) - Detailed XA flow in OJP
- [XA_SUPPORT.md](XA_SUPPORT.md) - Basic XA support documentation
- [ATOMIKOS_XA_INTEGRATION.md](ATOMIKOS_XA_INTEGRATION.md) - Atomikos integration guide

## Support

For issues or questions:
- Open an issue on GitHub: https://github.com/Open-J-Proxy/ojp/issues
- Review test cases in `ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/xa/`
- Check logs for retry and cleanup messages

## License

Apache License 2.0 - Same as the OJP project
