# Cluster Health and Session Invalidation Analysis: XA vs Non-XA

## Investigation Summary

**Date**: 2025-12-23  
**Purpose**: Verify that XA and non-XA sessions are invalidated identically when cluster nodes fail  
**Status**: ✅ **CONFIRMED** - Both XA and non-XA sessions use the same cluster health mechanism

## Key Findings

### 1. Unified Cluster Health Processing

Both XA and non-XA connections use **the same** cluster health processing mechanism on the OJP server side.

**Code Location**: `StatementServiceImpl.java`

The `processClusterHealth()` method is called for **every** request that includes SessionInfo with cluster health data:

```java
// Called in multiple places for both XA and non-XA
private void processClusterHealth(SessionInfo sessionInfo) {
    String clusterHealth = sessionInfo.getClusterHealth();
    String connHash = sessionInfo.getConnHash();
    
    if (clusterHealth != null && !clusterHealth.isEmpty()) {
        ConnectionPoolConfigurer.processClusterHealth(
            connHash, clusterHealth, clusterHealthTracker, hikariDataSource);
    }
}
```

**Called in**:
- Line 608: During execute operations
- Line 741: During executeQuery operations
- Line 798: During executeUpdate operations
- Line 1224: During fetch operations
- Line 1263: During callResource operations
- Line 1292: During readLob operations
- Line 1319: During writeLob operations

### 2. Cluster Health Tracking

**Component**: `ClusterHealthTracker.java`

This component:
- **Does NOT distinguish** between XA and non-XA sessions
- Tracks cluster health per `connHash` (connection hash)
- Detects changes in cluster health status
- Triggers rebalancing when health changes

**Format**: `"server1:1059(UP);server2:1059(DOWN);server3:1059(UP)"`

**Key Method**:
```java
public boolean hasHealthChanged(String connHash, String currentClusterHealth) {
    // Atomic check-and-update using compute()
    // Returns true if health has changed
    // Triggers pool rebalancing
}
```

### 3. Pool Rebalancing Mechanism

**Component**: `ConnectionPoolConfigurer.processClusterHealth()`

When cluster health changes:
1. **Count healthy servers**: `countHealthyServers(clusterHealth)`
2. **Update pool coordinator**: `poolCoordinator.updateHealthyServers(connHash, healthyServerCount)`
3. **Apply new pool sizes**: Dynamically resize HikariCP pools

**For HikariCP (Non-XA)**:
```java
if (healthChanged) {
    int healthyServerCount = clusterHealthTracker.countHealthyServers(clusterHealth);
    poolCoordinator.updateHealthyServers(connHash, healthyServerCount);
    
    if (dataSource != null) {
        applyPoolSizeChanges(connHash, dataSource);
    }
}
```

**For XA Connections**:
- Uses the same `MultinodePoolCoordinator` mechanism
- Pool sizes are adjusted based on healthy server count
- XA transaction limits are divided among healthy servers

### 4. Client-Side Cluster Health Reporting

**Component**: `MultinodeConnectionManager.java` (JDBC Driver)

The client-side reports cluster health to the server with **every request**:

```java
SessionInfo sessionInfo = SessionInfo.newBuilder()
    .setSessionUUID(sessionUUID)
    .setConnHash(connHash)
    .setClusterHealth(getClusterHealthString()) // Sent with every request
    .build();
```

The cluster health string includes all servers and their current health status:
- `UP`: Server is healthy
- `DOWN`: Server has failed health checks

### 5. Session Invalidation on Node Failure

**Client-Side** (`MultinodeConnectionManager.java`):

When a node fails:
1. **Health check detects failure**: `healthCheckValidator.checkHealth()`
2. **Mark server unhealthy**: `server.setHealthy(false)`
3. **Invalidate sessions**: `invalidateSessionsAndConnectionsForFailedServer(failedServer)`

**Code Location**: Lines 349-395

```java
private void invalidateSessionsAndConnectionsForFailedServer(ServerEndpoint failedServer) {
    String failedAddress = failedServer.getAddress();
    log.warn("Invalidating sessions and connections for failed server: {}", failedAddress);
    
    // Iterate through all session bindings
    sessionToServerMap.entrySet().removeIf(entry -> {
        if (entry.getValue().equals(failedServer)) {
            String sessionUUID = entry.getKey();
            log.info("Invalidating session {} bound to failed server {}", 
                    sessionUUID, failedAddress);
            
            // Unregister from SessionTracker
            sessionTracker.unregisterSession(sessionUUID);
            return true; // Remove this entry
        }
        return false;
    });
    
    // Also invalidate XA connections if they exist
    if (xaConnectionRedistributor != null) {
        xaConnectionRedistributor.invalidateConnectionsForServer(failedServer);
    }
}
```

**This applies to BOTH XA and non-XA sessions** because:
- All sessions are stored in `sessionToServerMap` regardless of type
- SessionTracker tracks both XA and non-XA sessions
- XA connections get additional invalidation through `xaConnectionRedistributor`

### 6. Server-Side Session Management

**Important**: The server does NOT actively invalidate sessions when a node fails.

The server relies on:
1. **Client-side detection**: Client health checks detect failures
2. **Client-side invalidation**: Client removes sessions from its tracking
3. **Server-side cleanup**: Server sessions are cleaned up when:
   - Client stops sending requests for that session
   - Session timeout occurs (if implemented)
   - Client explicitly closes the session

This is **identical for both XA and non-XA** sessions.

## Comparison: XA vs Non-XA Cluster Health Behavior

| Aspect | Non-XA | XA | Notes |
|--------|--------|-----|-------|
| **Cluster Health Reporting** | ✓ Same | ✓ Same | Both use `SessionInfo.clusterHealth` field |
| **Health Change Detection** | ✓ Same | ✓ Same | Both use `ClusterHealthTracker.hasHealthChanged()` |
| **Pool Rebalancing** | ✓ HikariCP dynamic resize | ✓ XA pool limit adjustment | Same trigger, different pool type |
| **Client-Side Health Checks** | ✓ Same | ✓ Same | Both use `HealthCheckValidator` |
| **Session Invalidation** | ✓ Same | ✓ Same | Both call `invalidateSessionsAndConnectionsForFailedServer()` |
| **SessionTracker Updates** | ✓ Yes | ✓ Yes | Both unregister sessions on failure |
| **Server-Side Processing** | ✓ Same | ✓ Same | Both use `processClusterHealth()` |

## Detailed Flow: Node Failure Scenario

### When Server2 Goes Down

**1. Client-Side Detection (JDBC Driver)**:
```
Time T0: Health check runs every 30 seconds (default)
Time T1: Health check to Server2 fails
         - Mark Server2 as unhealthy
         - Set lastFailureTime
         - Log: "Server server2:1059 marked as unhealthy"

Time T2: Next health check confirms Server2 still down
         - Call invalidateSessionsAndConnectionsForFailedServer(server2)
         - Remove all sessions bound to Server2
         - Unregister from SessionTracker
         - Update cluster health string: "server1:1059(UP);server2:1059(DOWN);server3:1059(UP)"
```

**2. Client Reports to All Servers**:
```
Time T3: Client makes next request to Server1
         - SessionInfo includes: clusterHealth="server1:1059(UP);server2:1059(DOWN);server3:1059(UP)"
         - Server1 processes cluster health
         - ClusterHealthTracker detects change
         - Pool coordinator updates healthy server count: 3 -> 2
         - HikariCP pool resizes (if applicable)
         
Time T4: Client makes request to Server3
         - Same cluster health string sent
         - Server3 also processes health change
         - Pool sizes adjusted on Server3
```

**3. Applies to BOTH XA and Non-XA**:
- Non-XA connections: HikariCP pools resize on Server1 and Server3
- XA connections: XA transaction limits recalculate on Server1 and Server3
- Both: Failed sessions are removed from tracking
- Both: New connections route only to healthy servers (Server1, Server3)

## Confirmation: Identical Behavior

### Evidence 1: Same Code Path

```java
// StatementServiceImpl.java - Line 206
private void processClusterHealth(SessionInfo sessionInfo) {
    // This method does NOT check if session is XA or non-XA
    // It processes ALL sessions identically
}
```

### Evidence 2: Same Health Tracker

```java
// ClusterHealthTracker.java - Line 82
public boolean hasHealthChanged(String connHash, String currentClusterHealth) {
    // Does NOT distinguish between XA and non-XA
    // Only cares about connHash (connection hash)
}
```

### Evidence 3: Same Client-Side Invalidation

```java
// MultinodeConnectionManager.java - Line 367
private void invalidateSessionsAndConnectionsForFailedServer(ServerEndpoint failedServer) {
    // Iterates through sessionToServerMap which contains ALL sessions
    // Does NOT filter by XA vs non-XA
    sessionToServerMap.entrySet().removeIf(entry -> {
        if (entry.getValue().equals(failedServer)) {
            sessionTracker.unregisterSession(sessionUUID); // Updates SessionTracker
            return true;
        }
        return false;
    });
    
    // Additional XA-specific cleanup
    if (xaConnectionRedistributor != null) {
        xaConnectionRedistributor.invalidateConnectionsForServer(failedServer);
    }
}
```

## Additional XA-Specific Handling

While the cluster health mechanism is identical, XA connections have **one additional layer** of cleanup:

**XAConnectionRedistributor** (Line 389):
```java
if (xaConnectionRedistributor != null) {
    xaConnectionRedistributor.invalidateConnectionsForServer(failedServer);
}
```

This ensures:
- XA Connection objects are marked for closure
- XA Resources are cleaned up
- Connection pool is notified to remove failed connections

**But this is ADDITIVE**, not a replacement. XA sessions still go through the same:
1. Health check detection
2. Session invalidation in `sessionToServerMap`
3. SessionTracker unregistration
4. Cluster health reporting to servers

## Conclusion

### ✅ CONFIRMED: XA and Non-XA Have Identical Cluster Health Behavior

1. **Same Detection**: Both use client-side health checks
2. **Same Reporting**: Both send cluster health with every request
3. **Same Processing**: Server processes cluster health identically
4. **Same Rebalancing**: Both trigger pool/transaction limit adjustments
5. **Same Invalidation**: Both remove failed sessions from tracking

### Differences (Non-Functional)

The only differences are **implementation details**, not behavior:

| Aspect | Non-XA | XA | Impact |
|--------|--------|-----|---------|
| Pool Type | HikariCP connections | XA transactions | Different resources, same mechanism |
| Redistribution | ConnectionRedistributor | XAConnectionRedistributor | Different cleanup, same trigger |
| Additional Cleanup | None | XAConnection invalidation | Extra step for XA, but uses same base logic |

### Why This Matters for Unified Mode

With unified mode (both XA and non-XA connecting to all servers):
- ✅ Cluster health will work correctly for both
- ✅ Node failures will be detected identically
- ✅ Session invalidation will work for both
- ✅ Pool rebalancing will trigger for both
- ✅ No special handling needed

The only change needed was adding `targetServer` to SessionInfo responses, which has been implemented.

## Recommendations

### 1. No Further Server-Side Changes Needed

The cluster health and session invalidation mechanisms are already unified and work correctly for both XA and non-XA.

### 2. Monitor After Deployment

When deploying unified mode:
- Monitor that XA sessions are invalidated when nodes fail
- Verify pool sizes adjust correctly
- Check that new XA connections only route to healthy servers

### 3. Consider Active Server-Side Invalidation (Future Enhancement)

Currently, the server relies on clients to stop sending requests for invalidated sessions. Consider:
- Server-side session timeout mechanism
- Proactive cleanup of orphaned sessions
- Metrics for session lifecycle

But this is **orthogonal** to XA vs non-XA and would benefit both equally.

---

**Investigation Complete**: 2025-12-23  
**Conclusion**: ✅ XA and non-XA sessions use identical cluster health and invalidation mechanisms  
**Required Changes**: None - behavior is already unified on the server side
