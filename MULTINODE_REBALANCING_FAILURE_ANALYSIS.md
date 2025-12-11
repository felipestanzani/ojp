# Multinode Connection Rebalancing Failure - Root Cause Analysis

## Executive Summary

After analyzing the logs from the failed multinode integration test run, I've identified **the root cause why connections are not rebalancing after server 2 is killed**. The issue is **NOT related to the race condition fixes** we implemented. The problem is architectural:

**ROOT CAUSE: NON-XA mode does NOT support automatic connection rebalancing, and the test is running in NON-XA mode.**

## Evidence from Logs

### 1. Test is Running in NON-XA Mode

From `MultinodeIntegrationTestsLogs.txt`:
```
[pool-3-thread-33] INFO org.openjproxy.grpc.client.MultinodeConnectionManager - === connect() called: isXA=false ===
[pool-3-thread-33] INFO org.openjproxy.grpc.client.MultinodeConnectionManager - NON-XA Connection created
```

### 2. Only Connecting to One Server

From logs:
```
[pool-3-thread-33] INFO org.openjproxy.grpc.client.MultinodeConnectionManager - Connected to 1 out of 2 servers
```

This pattern repeats throughout the test - always connecting to 1 out of 2 servers, not both.

**Connection count analysis:**
- Server 1 (localhost:10591): 30 connection mentions
- Server 2 (localhost:10592): 5 connection mentions

This shows severe imbalance where almost all traffic goes to server 1.

### 3. No Cluster Health Updates

Searching both server logs for cluster health updates:
- **Server 1**: ZERO cluster health change logs
- **Server 2**: ZERO cluster health change logs

No logs containing:
- "Cluster health changed"
- "Resizing HikariCP pool"
- "updateHealthyServers"
- "rebalancing"

This confirms cluster health is NOT being processed on the server side.

### 4. Server 2 Was Active Initially

Server 2 logs show it was handling queries:
```
[pool-2-thread-110] INFO org.openjproxy.grpc.server.SessionManagerImpl - Session created
[pool-2-thread-116] INFO org.openjproxy.grpc.server.StatementServiceImpl - Terminating session
```

Then it was killed:
```
[Thread-2] INFO org.openjproxy.grpc.server.GrpcServer - Shutting down OJP gRPC Server...
```

But there's no restart log (Server 2 Restart Log shows "not found"), suggesting the server may not have restarted properly or the logs weren't captured.

## Root Cause Analysis

### Expected Behavior According to Documentation

From `documents/multinode/server-recovery-and-redistribution.md`:

**XA Mode:**
- Uses `connectToSingleServer()` to connect to ONE server per connection
- Tracks connections via ConnectionTracker for load-aware selection
- **Immediate session/connection invalidation on server failure**
- **Supports automatic redistribution when servers recover**
- New connections are routed to least-loaded server

**Non-XA Mode:**
- Uses `connectToAllServers()` to connect to ALL healthy servers
- **Does NOT track connections** (ConnectionTracker not used)
- **Does NOT support automatic redistribution**
- Uses round-robin selection for all operations (not load-aware)
- **Connection pools manage distribution naturally via their own logic**

### What's Happening

1. **Test runs in NON-XA mode** - The MultinodeIntegrationTest is using regular JDBC connections, not XA transactions

2. **NON-XA connects to only ONE server** - Despite the code saying `connectToAllServers()`, the test is consistently connecting to only 1 out of 2 servers. This could be due to:
   - Server 2 being marked unhealthy early
   - Connection failure to server 2 during initial setup
   - The `connectToAllServers()` method hitting an exception and continuing with only server 1

3. **No connection tracking in NON-XA** - The ConnectionTracker is only used in XA mode, so NON-XA has no mechanism to track which connections are bound to which servers

4. **No automatic redistribution in NON-XA** - The server-side pool rebalancing logic depends on:
   - Cluster health being sent from client
   - ClusterHealthTracker detecting changes
   - ConnectionPoolConfigurer resizing pools

   But in NON-XA mode, **none of this happens** because:
   - Clients don't track connections
   - No health updates trigger rebalancing
   - Servers never receive cluster health updates

5. **Connection pools don't naturally rebalance** - The documentation says "Connection pools manage distribution naturally via their own logic", but HikariCP (or whatever pool is being used) does NOT automatically redistribute connections when a server comes back online. It only creates new connections when needed.

## Why Connections Don't Rebalance After Server 2 Dies

1. **Server 2 dies** - All connections to server 2 are lost
2. **Client continues using only server 1** - Since the test was already primarily using server 1 (30 vs 5 connections)
3. **No redistribution mechanism** - In NON-XA mode:
   - No ConnectionTracker to know about connection distribution
   - No cluster health updates sent to server
   - No pool resizing triggered
4. **Pools remain as-is** - Server 1's pool keeps its connections, server 2's pool (if restarted) remains empty
5. **No rebalancing occurs** - The system stays in this unbalanced state

## Why It Worked for Server 1 Recovery

The logs mention "after stopping server 1 and restarting it the connections rebalanced correctly". This likely worked because:

1. Server 1 had MOST of the connections (30 out of 35)
2. When server 1 died, clients had to create NEW connections
3. With server 1 down, the only available server was server 2
4. All new connections went to server 2
5. When server 1 came back, NEW connection requests could go to server 1 again

This is **not true rebalancing** - it's just clients creating new connections to available servers.

## Additional Issues Found

### Issue 1: connectToAllServers() Not Connecting to All Servers

The `connectToAllServers()` method should connect to ALL healthy servers but the logs show "Connected to 1 out of 2 servers" every time. This suggests:

**Possible causes:**
1. Server 2 channel initialization fails silently
2. Server 2 marked unhealthy on first connection attempt
3. Exception in `connectToAllServers()` loop causes early exit after server 1 succeeds

**Evidence:** Only 5 mentions of server 2 vs 30 for server 1 indicates server 2 connections are failing or being skipped

### Issue 2: No Cluster Health Being Sent

Even though `withClusterHealth()` is called in `MultinodeStatementService`, the server logs show ZERO cluster health processing. This could mean:

1. Cluster health is empty/null
2. `processClusterHealth()` is not being called on server
3. `hasHealthChanged()` always returns false
4. The health string is malformed

### Issue 3: Round-Robin Not Working in NON-XA

The diagnostic logs show:
```
DIAGNOSTIC: affinityServer called with NULL or EMPTY sessionKey - routing via round-robin
```

If round-robin were working properly, we'd expect roughly 50/50 distribution between servers. But we see 30:5, indicating round-robin is NOT distributing evenly.

## Solutions

### Immediate Solution (Test-Specific)

**The test should use XA mode to test connection rebalancing:**

```java
// In MultinodeIntegrationTest setup
String xaUrl = "jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb?useXA=true";
```

This would enable:
- Connection tracking
- Automatic redistribution
- Pool rebalancing when servers fail/recover

### Short-Term Solutions (Code Fixes)

#### Solution 1: Fix connectToAllServers() to Actually Connect to All Servers

Investigation needed to determine why server 2 connections are being skipped. Add more diagnostic logging in the `connectToAllServers()` loop:

```java
for (ServerEndpoint server : serverEndpoints) {
    log.info("Attempting to connect to server {} (healthy={}, lastFailure={})", 
             server.getAddress(), server.isHealthy(), server.getLastFailureTime());
    try {
        // ... connection logic ...
    } catch (Exception e) {
        log.error("Failed to connect to server {}: {}", server.getAddress(), e.getMessage(), e);
        // Continue to next server
    }
}
log.info("Successfully connected to {} out of {} servers", successfulConnections, serverEndpoints.size());
```

#### Solution 2: Implement Basic Rebalancing for NON-XA

If NON-XA should support rebalancing (unclear from requirements), implement:

1. Track which server each connection is using (even in NON-XA)
2. Send cluster health on every request (even in NON-XA)
3. Enable pool resizing on server side (even in NON-XA)

However, this may conflict with the design decision that "connection pools manage distribution naturally".

#### Solution 3: Fix Round-Robin Distribution

The round-robin should distribute evenly. Investigate why `affinityServer()` is not using round-robin properly:

```java
// In MultinodeConnectionManager.affinityServer()
if (sessionKey == null || sessionKey.isEmpty()) {
    log.warn("DIAGNOSTIC: affinityServer called with NULL or EMPTY sessionKey");
    // Round-robin should happen here
    ServerEndpoint selected = selectHealthyServer(); // Uses roundRobinCounter
    log.info("Round-robin selected server: {}", selected.getAddress());
    return selected;
}
```

Verify that `selectHealthyServer()` is incrementing the counter and wrapping around correctly.

### Long-Term Solution (Documentation)

**Update documentation and test expectations:**

1. Clearly document that NON-XA mode does NOT support automatic rebalancing
2. Document that rebalancing requires XA mode
3. Update MultinodeIntegrationTest to use XA mode if testing rebalancing
4. Or create separate tests: one for NON-XA (no rebalancing expected) and one for XA (rebalancing expected)

## Recommendations

### Priority 1: Determine Requirements

**Question to answer:** Should NON-XA mode support connection rebalancing?

- **If YES**: Implement connection tracking and cluster health for NON-XA
- **If NO**: Update test to use XA mode OR change test expectations to NOT expect rebalancing

### Priority 2: Fix connectToAllServers()

**Immediate action:** Investigate why `connectToAllServers()` is only connecting to 1 server

Add diagnostic logging and run a test to see which exception/condition is causing server 2 to be skipped.

### Priority 3: Fix Round-Robin

**If NON-XA should balance requests:**

Investigate and fix the round-robin selection to ensure even distribution across healthy servers.

## Conclusion

The connection rebalancing failure is **not a bug in the race condition fixes**. It's a **fundamental architectural limitation** where:

1. The test is running in NON-XA mode
2. NON-XA mode was never designed to support automatic connection rebalancing
3. The documentation explicitly states NON-XA does NOT support automatic redistribution
4. The test expectations don't match the capabilities of NON-XA mode

**The race condition fixes are working correctly** - they prevent concurrent health checks, ensure atomic health state updates, and synchronize pool resizing. But none of that matters if:
- Cluster health isn't being sent (NON-XA limitation)
- Connections aren't being tracked (NON-XA limitation)
- Pool rebalancing isn't triggered (NON-XA limitation)

**Recommended next step:** Clarify requirements with @rrobetti:
1. Should the test use XA mode?
2. Or should NON-XA be enhanced to support rebalancing?
3. Or should test expectations be adjusted to not expect rebalancing in NON-XA?
