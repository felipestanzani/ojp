# Solution Design: Fix XA Connection State Management

## Problem Summary

After analyzing the code and reviewing session lifecycle requirements, the root cause is confirmed:

**Sessions are NOT being hibernated/reset after transaction completion**, causing the XAConnection to remain in ENDED state for subsequent transactions.

### Correct Session Lifecycle Understanding

Per the design and code comments:
- **Session does NOT go back to pool after commit/rollback** ✓
- **Session stays bound to OJP Session for multiple sequential transactions** ✓
- **Session is returned to pool ONLY when client closes connection** ✓ (via `Session.terminate()`)

This is correct behavior! The issue is NOT about pool return timing.

### Current Behavior

1. During `connect()`: XABackendSession is borrowed from pool and bound to OJP Session
2. During `xaStart(xid1, TMNOFLAGS)`: Transaction started on the same logical connection
3. After `xaCommit(xid1)`: 
   - TxContext removed ✓
   - Session stays bound to OJP Session ✓ (CORRECT - should not return to pool yet)
   - **NO hibernation/reset called between transactions** ✗ (PROBLEM!)
   - **XAConnection remains in ENDED state** ✗
4. During next `xaStart(xid2, TMNOFLAGS)`: 
   - Same XAConnection still in ENDED state from previous transaction
   - New transaction fails to start due to dirty XA state

### Why This Fails

PostgreSQL (and other databases) XAConnection has internal state that must be reset between transactions when the same physical connection/session is reused:

```
Session Lifecycle (Client keeps connection open):
  
  Transaction 1:
    start(xid1) → ACTIVE
    work...
    end(xid1) → ENDED  
    commit(xid1) → Should return to IDLE for connection reuse
    
  (Session stays bound, no pool return - CORRECT!)
  (But XA state not reset - PROBLEM!)
  
  Transaction 2:
    start(xid2) → ERROR: Connection still in ENDED state from xid1!
```

When native XA datasources work, it's because:
- The application gets a fresh logical connection for each transaction
- Or the XA driver automatically resets state when getting a connection from XAConnection
- OJP's eager allocation strategy reuses the same logical connection, exposing this issue

## Root Cause Analysis

### Issue 1: Missing XA Connection Hibernation Between Transactions

**Location**: `XATransactionRegistry.java` - `xaCommit()` and `xaRollback()` methods

After transaction completion, the code does:
```java
ctx.transitionToCommitted();
// NOTE: Do NOT return session to pool here - session stays bound to OJP Session
contexts.remove(xid);
```

The comment is correct - the session should NOT return to pool yet. However, it **never calls any hibernation/reset logic** on the session to prepare it for the next transaction!

**The session must be hibernated/reset BETWEEN transactions, not AFTER pool return.** These are two different concerns:
- **Hibernation**: Reset XA state between transactions while session stays bound to OJP Session
- **Pool Return**: Return session to pool when OJP Session terminates (client closes connection)

### Issue 2: Missing Flag Routing

**Location**: `StatementServiceImpl.java` - `handleXAStartWithPooling()` method

The method unconditionally calls `registerExistingSession()` which only accepts TMNOFLAGS:
```java
registry.registerExistingSession(xidKey, backendSession, request.getFlags());
```

This should route based on flags to support TMJOIN/TMRESUME.

## Solution Design

### Solution 1: Sanitize XA Session After Transaction (Primary Fix)

**Key Insight**: We need to **sanitize the session between transactions** (after commit/rollback) even though the session doesn't return to the pool. This is different from the `reset()` method which is for pool return.

The sanitization must:
1. Close the current logical connection (which has ENDED XA state)
2. Get a fresh logical connection from the XAConnection
3. This resets the XA state to IDLE for the next transaction

**Why we need a separate method**: The existing `reset()` method is designed for pool return and doesn't reset XA state. We need XA-specific sanitization that happens after each transaction while the session remains bound.

#### 1.1 Add `sanitizeAfterTransaction()` method to XABackendSession interface

```java
/**
 * Sanitizes the XA session after transaction completion.
 * <p>
 * This method prepares the session for the next transaction by refreshing
 * the logical connection to reset XA state. It is called after commit/rollback
 * while the session remains bound to the OJP Session (not returned to pool).
 * </p>
 * <p>
 * This is different from {@link #reset()} which is called when returning
 * the session to the pool. Sanitization happens BETWEEN transactions on the
 * same OJP Session, while reset happens AFTER the OJP Session terminates.
 * </p>
 * <p>
 * The method must:
 * <ul>
 *   <li>Close the current logical connection (which may be in ENDED XA state)</li>
 *   <li>Obtain a fresh logical connection from the XAConnection</li>
 *   <li>Reset the XA state to IDLE for the next transaction</li>
 * </ul>
 * </p>
 * <p>
 * <strong>CRITICAL:</strong> This method must ONLY be called after transaction
 * completion (COMMITTED or ROLLEDBACK state), never while in PREPARED state.
 * </p>
 * 
 * @throws SQLException if sanitization fails
 */
void sanitizeAfterTransaction() throws SQLException;
```

#### 1.2 Implement `sanitizeAfterTransaction()` in BackendSessionImpl

```java
@Override
public void sanitizeAfterTransaction() throws SQLException {
    if (closed) {
        throw new IllegalStateException("Cannot sanitize closed session");
    }
    
    log.debug("Sanitizing backend session after transaction: {}", sessionId);
    
    try {
        // Close the current logical connection
        // This tells the XAConnection we're done with this transaction context
        if (connection != null) {
            try {
                connection.close();
                log.debug("Closed logical connection during sanitization");
            } catch (SQLException e) {
                log.warn("Error closing logical connection during sanitization: {}", e.getMessage());
                // Continue anyway - we'll get a fresh one
            }
        }
        
        // Get a fresh logical connection from the XAConnection
        // This resets the XA state to IDLE in most XA drivers (PostgreSQL, MySQL, etc.)
        this.connection = xaConnection.getConnection();
        
        // The XAResource should remain the same (from the XAConnection)
        // No need to re-obtain it - it's tied to the XAConnection, not the logical connection
        
        // Clear warnings on the new connection
        try {
            connection.clearWarnings();
        } catch (SQLException e) {
            log.warn("Error clearing warnings after sanitization: {}", e.getMessage());
        }
        
        log.debug("Backend session sanitized successfully, fresh logical connection obtained");
        
    } catch (SQLException e) {
        log.error("Failed to sanitize session: {}", e.getMessage(), e);
        throw e;
    }
}
```

#### 1.3 Call `sanitizeAfterTransaction()` after commit/rollback

In `XATransactionRegistry.xaCommit()`:
```java
public void xaCommit(XidKey xid, boolean onePhase) throws XAException {
    // ... existing commit logic ...
    
    ctx.transitionToCommitted();
    
    // IMPORTANT: Sanitize the session to reset XA state for next transaction
    // The session stays bound to OJP Session (doesn't return to pool)
    // but needs XA state reset between transactions
    try {
        ctx.getSession().sanitizeAfterTransaction();
        log.debug("Session sanitized after commit for xid={}", xid);
    } catch (SQLException e) {
        log.warn("Failed to sanitize session after commit for xid={}: {}", xid, e.getMessage());
        // Don't throw - commit was successful, sanitization is best-effort
        // If sanitization fails, next transaction may have issues, but commit succeeded
    }
    
    // NOTE: Do NOT return session to pool here - session stays bound to OJP Session
    // for multiple transactions. Pool return happens when OJP Session terminates.
    contexts.remove(xid);
    
    log.info("XA transaction committed: xid={}, onePhase={}", xid, onePhase);
}
```

In `XATransactionRegistry.xaRollback()`:
```java
public void xaRollback(XidKey xid) throws XAException {
    // ... existing rollback logic ...
    
    ctx.transitionToRolledBack();
    
    // IMPORTANT: Sanitize the session to reset XA state for next transaction
    // The session stays bound to OJP Session (doesn't return to pool)
    // but needs XA state reset between transactions
    try {
        ctx.getSession().sanitizeAfterTransaction();
        log.debug("Session sanitized after rollback for xid={}", xid);
    } catch (SQLException e) {
        log.warn("Failed to sanitize session after rollback for xid={}: {}", xid, e.getMessage());
        // Don't throw - rollback was successful, sanitization is best-effort
    }
    
    // NOTE: Do NOT return session to pool here - session stays bound to OJP Session
    // for multiple transactions. Pool return happens when OJP Session terminates.
    contexts.remove(xid);
    
    log.info("XA transaction rolled back: xid={}", xid);
}
```

### Solution 2: Implement Flag-Based Routing (Secondary Fix)

In `StatementServiceImpl.handleXAStartWithPooling()`:

```java
private void handleXAStartWithPooling(com.openjproxy.grpc.XaStartRequest request, Session session, 
                                      StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) throws Exception {
    String connHash = session.getSessionInfo().getConnHash();
    XATransactionRegistry registry = xaRegistries.get(connHash);
    if (registry == null) {
        throw new SQLException("No XA registry found for connection hash: " + connHash);
    }
    
    // Convert proto Xid to XidKey
    XidKey xidKey = XidKey.from(convertXid(request.getXid()));
    int flags = request.getFlags();
    
    // Route based on flags
    if (flags == XAResource.TMNOFLAGS) {
        // New transaction: use existing session from OJP Session
        XABackendSession backendSession = (XABackendSession) session.getBackendSession();
        if (backendSession == null) {
            throw new SQLException("No XABackendSession found in session");
        }
        registry.registerExistingSession(xidKey, backendSession, flags);
        
    } else if (flags == XAResource.TMJOIN || flags == XAResource.TMRESUME) {
        // Join or resume existing transaction: delegate to xaStart
        // This requires the context to exist (from previous TMNOFLAGS start)
        registry.xaStart(xidKey, flags);
        
    } else {
        throw new SQLException("Unsupported XA flags: " + flags);
    }
    
    com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
            .setSession(session.getSessionInfo())
            .setSuccess(true)
            .setMessage("XA start successful (pooled)")
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
}
```

## Integration Test Design

### Test 1: Multiple Sequential Transactions on Same Session

This test validates that XA connections are properly hibernated between transactions:

```java
@Test
public void testMultipleSequentialTransactionsWithSessionReuse() throws Exception {
    XAResource xaResource = xaConnection.getXAResource();
    
    // Transaction 1: Insert data
    Xid xid1 = new TestXid(1, "gtrid-1".getBytes(), "bqual-1".getBytes());
    xaResource.start(xid1, XAResource.TMNOFLAGS);
    
    try (PreparedStatement ps = connection.prepareStatement("INSERT INTO test_table VALUES (?, ?)")) {
        ps.setInt(1, 1);
        ps.setString(2, "Transaction 1");
        ps.executeUpdate();
    }
    
    xaResource.end(xid1, XAResource.TMSUCCESS);
    xaResource.commit(xid1, true); // One-phase commit
    
    // Transaction 2: Read data (CRITICAL: This should work without errors)
    Xid xid2 = new TestXid(2, "gtrid-2".getBytes(), "bqual-2".getBytes());
    xaResource.start(xid2, XAResource.TMNOFLAGS); // Should NOT fail here!
    
    try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM test_table WHERE id = ?")) {
        ps.setInt(1, 1);
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("Transaction 1", rs.getString(2));
        }
    }
    
    xaResource.end(xid2, XAResource.TMSUCCESS);
    xaResource.commit(xid2, true);
    
    // Transaction 3: Update data (Triple test to ensure consistency)
    Xid xid3 = new TestXid(3, "gtrid-3".getBytes(), "bqual-3".getBytes());
    xaResource.start(xid3, XAResource.TMNOFLAGS);
    
    try (PreparedStatement ps = connection.prepareStatement("UPDATE test_table SET name = ? WHERE id = ?")) {
        ps.setString(1, "Transaction 3");
        ps.setInt(2, 1);
        ps.executeUpdate();
    }
    
    xaResource.end(xid3, XAResource.TMSUCCESS);
    xaResource.commit(xid3, true);
}
```

### Test 2: TMJOIN Flag Support

This test validates that TMJOIN flags are properly routed:

```java
@Test
public void testTMJOINFlagSupport() throws Exception {
    XAResource xaResource = xaConnection.getXAResource();
    
    // Start transaction
    Xid xid = new TestXid(1, "gtrid-join".getBytes(), "bqual-join".getBytes());
    xaResource.start(xid, XAResource.TMNOFLAGS);
    
    // Do some work
    try (PreparedStatement ps = connection.prepareStatement("INSERT INTO test_table VALUES (?, ?)")) {
        ps.setInt(1, 1);
        ps.setString(2, "Join Test");
        ps.executeUpdate();
    }
    
    // End with TMSUSPEND to allow re-joining
    xaResource.end(xid, XAResource.TMSUSPEND);
    
    // Join the same transaction (should be supported)
    xaResource.start(xid, XAResource.TMJOIN); // Should NOT throw XAER_INVAL!
    
    // Do more work
    try (PreparedStatement ps = connection.prepareStatement("INSERT INTO test_table VALUES (?, ?)")) {
        ps.setInt(1, 2);
        ps.setString(2, "After Join");
        ps.executeUpdate();
    }
    
    xaResource.end(xid, XAResource.TMSUCCESS);
    xaResource.commit(xid, true);
}
```

### Test 3: Two-Phase Commit with Session Reuse

This test validates hibernation works with 2PC:

```java
@Test
public void testTwoPhaseCommitWithSessionReuse() throws Exception {
    XAResource xaResource = xaConnection.getXAResource();
    
    // Transaction 1: 2PC
    Xid xid1 = new TestXid(1, "2pc-1".getBytes(), "branch-1".getBytes());
    xaResource.start(xid1, XAResource.TMNOFLAGS);
    
    try (PreparedStatement ps = connection.prepareStatement("INSERT INTO test_table VALUES (?, ?)")) {
        ps.setInt(1, 1);
        ps.setString(2, "2PC Test");
        ps.executeUpdate();
    }
    
    xaResource.end(xid1, XAResource.TMSUCCESS);
    int prepareResult = xaResource.prepare(xid1);
    assertEquals(XAResource.XA_OK, prepareResult);
    xaResource.commit(xid1, false); // Two-phase commit
    
    // Transaction 2: Should start cleanly after 2PC
    Xid xid2 = new TestXid(2, "2pc-2".getBytes(), "branch-2".getBytes());
    xaResource.start(xid2, XAResource.TMNOFLAGS); // Should work!
    
    try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM test_table")) {
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }
    
    xaResource.end(xid2, XAResource.TMSUCCESS);
    xaResource.commit(xid2, true);
}
```

## Implementation Priority

1. **High Priority**: Implement hibernate() method (Solution 1) - This fixes the core issue
2. **Medium Priority**: Implement flag-based routing (Solution 2) - This adds TMJOIN/TMRESUME support
3. **High Priority**: Add integration tests to validate both fixes

## Expected Outcomes

After implementing these fixes:

1. ✅ Multiple sequential transactions on the same XAConnection will work correctly
2. ✅ XA connection state will be properly reset between transactions
3. ✅ TMJOIN and TMRESUME flags will be supported
4. ✅ Spring Boot + Narayana integration will work correctly
5. ✅ The error "tried to call end without corresponding start call. state=ENDED" will be resolved

## Rollback Plan

If hibernation causes issues with certain databases:
1. Make hibernation configurable via property
2. Add database-specific hibernation strategies
3. Fall back to connection validation checks before xaStart
