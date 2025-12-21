# XA Transaction Flow Diagrams

**Date:** 2025-12-20  
**Author:** GitHub Copilot Analysis  
**Related:** XA_POOL_IMPLEMENTATION_ANALYSIS.md

## Overview

This document provides detailed flow diagrams showing the method call sequences for XA transactions in the OJP proxy server. It covers two scenarios:
1. Successful XA transaction commit (2PC)
2. XA transaction recovery after failure

---

## 1. Successful XA Transaction Commit Flow

### Scenario
A distributed transaction with 2-phase commit that completes successfully.

### Actors
- **TM**: Transaction Manager (e.g., Narayana, Atomikos)
- **Driver**: OJP JDBC Driver (XAResource implementation)
- **Proxy**: OJP Server (XATransactionRegistry)
- **Backend**: Database (Oracle, PostgreSQL, SQL Server with XA support)

### Flow Diagram

```
┌────────┐          ┌────────┐          ┌─────────────┐          ┌─────────┐
│   TM   │          │ Driver │          │    Proxy    │          │ Backend │
│        │          │(XARes) │          │ (Registry)  │          │   DB    │
└───┬────┘          └───┬────┘          └──────┬──────┘          └────┬────┘
    │                   │                       │                      │
    │ 1. start(xid, TMNOFLAGS)                 │                      │
    ├──────────────────>│                       │                      │
    │                   │ xaStart(XidKey, TMNOFLAGS)                  │
    │                   ├──────────────────────>│                      │
    │                   │                       │                      │
    │                   │    [Create TxContext] │                      │
    │                   │    state: NONEXISTENT→ACTIVE                │
    │                   │                       │                      │
    │                   │    [Borrow BackendSession from pool]        │
    │                   │      - BackendSessionPool.borrowObject()    │
    │                   │      - BackendSessionFactory.activateObject()│
    │                   │                       │                      │
    │                   │    [Store XidKey → BackendSession mapping]  │
    │                   │      - contexts.put(xidKey, txContext)       │
    │                   │                       │                      │
    │                   │    [Delegate to Backend XAResource]          │
    │                   │                       │ XAResource.start()   │
    │                   │                       ├─────────────────────>│
    │                   │                       │   (xid, TMNOFLAGS)   │
    │                   │                       │                      │
    │                   │                       │<─────────────────────┤
    │                   │                       │      Success         │
    │                   │<──────────────────────┤                      │
    │                   │      Success          │                      │
    │<──────────────────┤                       │                      │
    │   Success         │                       │                      │
    │                   │                       │                      │
    │ 2. Execute SQL statements (application work)                    │
    ├──────────────────>│                       │                      │
    │                   │ StatementRequest(sql, xid)                  │
    │                   ├──────────────────────>│                      │
    │                   │                       │                      │
    │                   │    [Route to correct session via XidKey]    │
    │                   │      - contexts.get(xidKey)                  │
    │                   │      - txContext.getBackendSession()         │
    │                   │                       │                      │
    │                   │    [Validate state == ACTIVE]                │
    │                   │                       │                      │
    │                   │    [Execute on backend session]              │
    │                   │                       │ session.execute(sql) │
    │                   │                       ├─────────────────────>│
    │                   │                       │                      │
    │                   │                       │<─────────────────────┤
    │                   │                       │   ResultSet          │
    │                   │<──────────────────────┤                      │
    │                   │   OpResult            │                      │
    │<──────────────────┤                       │                      │
    │   ResultSet       │                       │                      │
    │                   │                       │                      │
    │ 3. end(xid, TMSUCCESS)                    │                      │
    ├──────────────────>│                       │                      │
    │                   │ xaEnd(XidKey, TMSUCCESS)                    │
    │                   ├──────────────────────>│                      │
    │                   │                       │                      │
    │                   │    [Validate state == ACTIVE]                │
    │                   │    [Transition ACTIVE → ENDED]               │
    │                   │      - txContext.transitionTo(ENDED)         │
    │                   │                       │                      │
    │                   │    [Delegate to Backend XAResource]          │
    │                   │                       │ XAResource.end()     │
    │                   │                       ├─────────────────────>│
    │                   │                       │   (xid, TMSUCCESS)   │
    │                   │                       │                      │
    │                   │                       │<─────────────────────┤
    │                   │                       │      Success         │
    │                   │<──────────────────────┤                      │
    │                   │      Success          │                      │
    │<──────────────────┤                       │                      │
    │   Success         │                       │                      │
    │                   │                       │                      │
    │                   │    [Session NOT returned to pool - kept pinned]
    │                   │                       │                      │
    │ 4. prepare(xid)   │                       │                      │
    ├──────────────────>│                       │                      │
    │                   │ xaPrepare(XidKey)     │                      │
    │                   ├──────────────────────>│                      │
    │                   │                       │                      │
    │                   │    [Validate state == ENDED]                 │
    │                   │    [Transition ENDED → PREPARED]             │
    │                   │      - txContext.transitionTo(PREPARED)      │
    │                   │                       │                      │
    │                   │    [Delegate to Backend XAResource]          │
    │                   │                       │ XAResource.prepare() │
    │                   │                       ├─────────────────────>│
    │                   │                       │      (xid)           │
    │                   │                       │                      │
    │                   │                       │ [Backend writes to   │
    │                   │                       │  its transaction log]│
    │                   │                       │ [Backend persists    │
    │                   │                       │  PREPARED state]     │
    │                   │                       │                      │
    │                   │                       │<─────────────────────┤
    │                   │                       │   XA_OK or XA_RDONLY │
    │                   │<──────────────────────┤                      │
    │                   │   XA_OK or XA_RDONLY  │                      │
    │<──────────────────┤                       │                      │
    │   XA_OK/RDONLY    │                       │                      │
    │                   │                       │                      │
    │                   │    [If XA_RDONLY: transition to COMMITTED,  │
    │                   │     return session to pool, cleanup]         │
    │                   │    [If XA_OK: keep session pinned]           │
    │                   │                       │                      │
    │ 5. commit(xid, false) [2PC]               │                      │
    ├──────────────────>│                       │                      │
    │                   │ xaCommit(XidKey, onePhase=false)            │
    │                   ├──────────────────────>│                      │
    │                   │                       │                      │
    │                   │    [Validate state == PREPARED]              │
    │                   │    [Check idempotency - already COMMITTED?]  │
    │                   │                       │                      │
    │                   │    [Delegate to Backend XAResource]          │
    │                   │                       │ XAResource.commit()  │
    │                   │                       ├─────────────────────>│
    │                   │                       │  (xid, onePhase=false)│
    │                   │                       │                      │
    │                   │                       │ [Backend completes   │
    │                   │                       │  2PC commit]         │
    │                   │                       │ [Backend removes from│
    │                   │                       │  transaction log]    │
    │                   │                       │                      │
    │                   │                       │<─────────────────────┤
    │                   │                       │      Success         │
    │                   │                       │                      │
    │                   │    [Transition PREPARED → COMMITTED]         │
    │                   │      - txContext.transitionTo(COMMITTED)     │
    │                   │                       │                      │
    │                   │    [Return BackendSession to pool]           │
    │                   │      - BackendSessionFactory.passivateObject()│
    │                   │      - session.reset() (clear state)         │
    │                   │      - pool.returnObject(session)            │
    │                   │                       │                      │
    │                   │    [Remove TxContext from registry]          │
    │                   │      - contexts.remove(xidKey)               │
    │                   │                       │                      │
    │                   │<──────────────────────┤                      │
    │                   │      Success          │                      │
    │<──────────────────┤                       │                      │
    │   Success         │                       │                      │
    │                   │                       │                      │
```

### Method Call Sequence Summary

**Phase 1 - Transaction Start**
1. `TM.start(xid, TMNOFLAGS)` → `Driver.XAResource.start()`
2. `Driver` → `StatementServiceImpl.xaStart(XaStartRequest)`
3. `StatementServiceImpl` → `XATransactionRegistry.xaStart(XidKey, flags)`
4. `XATransactionRegistry`:
   - Creates `TxContext` with state `NONEXISTENT → ACTIVE`
   - `BackendSessionPool.borrowObject()` → `BackendSessionFactory.activateObject()`
   - Stores mapping: `contexts.put(xidKey, txContext)`
   - `BackendSession.getXAResource()` → `XAResource.start(xid, TMNOFLAGS)`

**Phase 2 - Execute SQL**
5. `Application.executeQuery()` → `Driver.Statement.execute()`
6. `Driver` → `StatementServiceImpl.executeQuery(StatementRequest with xid)`
7. `StatementServiceImpl`:
   - Looks up session: `XATransactionRegistry.getSessionForXid(xidKey)`
   - Validates state is `ACTIVE`
   - Routes to correct backend: `BackendSession.getConnection().execute()`

**Phase 3 - End Transaction**
8. `TM.end(xid, TMSUCCESS)` → `Driver.XAResource.end()`
9. `Driver` → `StatementServiceImpl.xaEnd(XaEndRequest)`
10. `StatementServiceImpl` → `XATransactionRegistry.xaEnd(XidKey, flags)`
11. `XATransactionRegistry`:
    - Validates state is `ACTIVE`
    - Transitions `ACTIVE → ENDED`: `txContext.transitionTo(ENDED)`
    - `BackendSession.getXAResource().end(xid, TMSUCCESS)`
    - Session remains pinned (NOT returned to pool)

**Phase 4 - Prepare**
12. `TM.prepare(xid)` → `Driver.XAResource.prepare()`
13. `Driver` → `StatementServiceImpl.xaPrepare(XaPrepareRequest)`
14. `StatementServiceImpl` → `XATransactionRegistry.xaPrepare(XidKey)`
15. `XATransactionRegistry`:
    - Validates state is `ENDED`
    - Transitions `ENDED → PREPARED`: `txContext.transitionTo(PREPARED)`
    - `BackendSession.getXAResource().prepare(xid)`
    - Backend database persists PREPARED state in its transaction log
    - Returns `XA_OK` or `XA_RDONLY`
    - If `XA_RDONLY`: cleanup and return session; if `XA_OK`: keep pinned

**Phase 5 - Commit**
16. `TM.commit(xid, false)` → `Driver.XAResource.commit()`
17. `Driver` → `StatementServiceImpl.xaCommit(XaCommitRequest)`
18. `StatementServiceImpl` → `XATransactionRegistry.xaCommit(XidKey, onePhase=false)`
19. `XATransactionRegistry`:
    - Validates state is `PREPARED`
    - Checks idempotency (already `COMMITTED`?)
    - `BackendSession.getXAResource().commit(xid, onePhase=false)`
    - Backend completes 2PC and removes from transaction log
    - Transitions `PREPARED → COMMITTED`: `txContext.transitionTo(COMMITTED)`
    - Returns session: `BackendSessionFactory.passivateObject()` → `session.reset()` → `pool.returnObject()`
    - Removes context: `contexts.remove(xidKey)`

---

## 2. XA Transaction Recovery Flow

### Scenario
OJP proxy node crashes while holding PREPARED transactions. After restart, Transaction Manager performs recovery to complete the transactions.

### Recovery Triggers
- TM timeout (transaction not completed within expected time)
- Explicit recovery call (e.g., admin-initiated recovery)
- Periodic recovery scan by TM

### Flow Diagram

```
┌────────┐          ┌────────┐          ┌─────────────┐          ┌─────────┐
│   TM   │          │ Driver │          │ Proxy Node 1│          │ Backend │
│(Recovery)         │(XARes) │          │ (Registry)  │          │   DB    │
└───┬────┘          └───┬────┘          └──────┬──────┘          └────┬────┘
    │                   │                       │                      │
    │                   │                       │                      │
    │ [Node crashed during PREPARED state]     │                      │
    │ [TxContext in-memory state lost]         │                      │
    │ [BackendSession still exists in pool]    │                      │
    │ [Database has PREPARED transaction in log]                      │
    │                   │                       │                      │
    │ [Node restarts]   │                       │                      │
    │                   │                       │                      │
    │                   │                [Proxy initialization]        │
    │                   │                [XATransactionRegistry created]
    │                   │                [contexts map is empty]       │
    │                   │                [BackendSessionPool created]  │
    │                   │                       │                      │
    │                   │                       │                      │
    │ 1. Recovery scan starts                   │                      │
    │    (TM periodic check or explicit)        │                      │
    │                   │                       │                      │
    │ 2. recover(TMSTARTRSCAN)                  │                      │
    ├──────────────────>│                       │                      │
    │                   │ xaRecover(XaRecoverRequest)                 │
    │                   ├──────────────────────>│                      │
    │                   │                       │                      │
    │                   │    [NO in-memory state to recover]           │
    │                   │    [Must query backend database]             │
    │                   │                       │                      │
    │                   │    [Borrow a BackendSession from pool]       │
    │                   │      - BackendSessionPool.borrowObject()     │
    │                   │                       │                      │
    │                   │    [Query backend XAResource for PREPARED]   │
    │                   │                       │ XAResource.recover() │
    │                   │                       ├─────────────────────>│
    │                   │                       │  (TMSTARTRSCAN)      │
    │                   │                       │                      │
    │                   │                       │ [Backend queries its │
    │                   │                       │  transaction log]    │
    │                   │                       │ [Returns array of    │
    │                   │                       │  PREPARED Xids]      │
    │                   │                       │                      │
    │                   │                       │<─────────────────────┤
    │                   │                       │  Xid[] {xid1, xid2}  │
    │                   │                       │                      │
    │                   │    [Convert Xid[] to XidKey[]]               │
    │                   │                       │                      │
    │                   │    [Return session to pool]                  │
    │                   │      - pool.returnObject(session)            │
    │                   │                       │                      │
    │                   │<──────────────────────┤                      │
    │                   │ XaRecoverResponse{xid1, xid2}                │
    │<──────────────────┤                       │                      │
    │ Xid[] {xid1, xid2}│                       │                      │
    │                   │                       │                      │
    │ 3. For each recovered Xid, decide action │                      │
    │    (commit or rollback based on TM log)  │                      │
    │                   │                       │                      │
    │ 4a. commit(xid1, false) [Complete prepared tx]                  │
    ├──────────────────>│                       │                      │
    │                   │ xaCommit(XidKey1, onePhase=false)           │
    │                   ├──────────────────────>│                      │
    │                   │                       │                      │
    │                   │    [Xid1 NOT in contexts map - recovery case]│
    │                   │    [Create temporary TxContext or direct call]│
    │                   │                       │                      │
    │                   │    [Borrow BackendSession from pool]         │
    │                   │      - BackendSessionPool.borrowObject()     │
    │                   │                       │                      │
    │                   │    [Delegate to Backend XAResource]          │
    │                   │                       │ XAResource.commit()  │
    │                   │                       ├─────────────────────>│
    │                   │                       │  (xid1, onePhase=false)│
    │                   │                       │                      │
    │                   │                       │ [Backend completes   │
    │                   │                       │  commit from log]    │
    │                   │                       │ [Backend removes xid1│
    │                   │                       │  from transaction log]│
    │                   │                       │                      │
    │                   │                       │<─────────────────────┤
    │                   │                       │      Success         │
    │                   │                       │                      │
    │                   │    [Return BackendSession to pool]           │
    │                   │      - pool.returnObject(session)            │
    │                   │                       │                      │
    │                   │<──────────────────────┤                      │
    │                   │      Success          │                      │
    │<──────────────────┤                       │                      │
    │   Success         │                       │                      │
    │                   │                       │                      │
    │ 4b. rollback(xid2) [Abort prepared tx]    │                      │
    ├──────────────────>│                       │                      │
    │                   │ xaRollback(XidKey2)   │                      │
    │                   ├──────────────────────>│                      │
    │                   │                       │                      │
    │                   │    [Similar flow to commit]                  │
    │                   │    [Borrow session, delegate rollback]       │
    │                   │                       │ XAResource.rollback()│
    │                   │                       ├─────────────────────>│
    │                   │                       │      (xid2)          │
    │                   │                       │                      │
    │                   │                       │ [Backend rolls back  │
    │                   │                       │  and removes from log]│
    │                   │                       │                      │
    │                   │                       │<─────────────────────┤
    │                   │                       │      Success         │
    │                   │                       │                      │
    │                   │    [Return session to pool]                  │
    │                   │                       │                      │
    │                   │<──────────────────────┤                      │
    │                   │      Success          │                      │
    │<──────────────────┤                       │                      │
    │   Success         │                       │                      │
    │                   │                       │                      │
    │ 5. recover(TMENDRSCAN)                    │                      │
    ├──────────────────>│                       │                      │
    │                   │ xaRecover(XaRecoverRequest)                 │
    │                   ├──────────────────────>│                      │
    │                   │                       │                      │
    │                   │    [Query backend again to confirm cleanup]  │
    │                   │                       │ XAResource.recover() │
    │                   │                       ├─────────────────────>│
    │                   │                       │  (TMENDRSCAN)        │
    │                   │                       │                      │
    │                   │                       │<─────────────────────┤
    │                   │                       │  Xid[] {} (empty)    │
    │                   │<──────────────────────┤                      │
    │                   │ XaRecoverResponse{}   │                      │
    │<──────────────────┤                       │                      │
    │   Xid[] {}        │                       │                      │
    │                   │                       │                      │
    │ Recovery complete │                       │                      │
    │                   │                       │                      │
```

### Method Call Sequence Summary

**Phase 1 - Node Crash**
- Transaction was in `PREPARED` state when node crashed
- In-memory `TxContext` lost
- `BackendSession` may still exist in pool (if pool survives restart) or is recreated
- Backend database has PREPARED transaction in its XA transaction log

**Phase 2 - Node Restart**
1. Proxy node starts up
2. `XATransactionRegistry` initialized with empty `contexts` map
3. `BackendSessionPool` created (new pool or existing sessions)

**Phase 3 - Recovery Scan**
4. `TM.recover(TMSTARTRSCAN)` → `Driver.XAResource.recover()`
5. `Driver` → `StatementServiceImpl.xaRecover(XaRecoverRequest)`
6. `StatementServiceImpl` → `XATransactionRegistry.xaRecover(flags)`
7. `XATransactionRegistry`:
   - Recognizes recovery case (contexts map empty or Xid not found)
   - `BackendSessionPool.borrowObject()` (temporary session for query)
   - `BackendSession.getXAResource().recover(TMSTARTRSCAN)`
   - Backend queries its transaction log and returns `Xid[]` of PREPARED transactions
   - Converts to `XidKey[]`
   - Returns session to pool
   - Returns list of recovered Xids to caller

**Phase 4 - Complete Recovered Transactions**

For each recovered Xid, TM decides to commit or rollback based on its own log:

**Commit Path:**
8. `TM.commit(xid1, false)` → `Driver.XAResource.commit()`
9. `Driver` → `StatementServiceImpl.xaCommit(XaCommitRequest)`
10. `StatementServiceImpl` → `XATransactionRegistry.xaCommit(XidKey1, onePhase=false)`
11. `XATransactionRegistry`:
    - Detects Xid not in contexts map (recovery case)
    - May create temporary TxContext or handle directly
    - `BackendSessionPool.borrowObject()` (get session for commit)
    - `BackendSession.getXAResource().commit(xid1, onePhase=false)`
    - Backend completes commit from its transaction log
    - Backend removes Xid from transaction log
    - Returns session to pool
    - No TxContext cleanup needed (wasn't in map)

**Rollback Path:**
12. Similar to commit but calls `XAResource.rollback(xid)`

**Phase 5 - End Recovery Scan**
13. `TM.recover(TMENDRSCAN)` → verify all recovered Xids are processed
14. Backend returns empty `Xid[]` confirming all transactions completed

---

## 3. Multinode Recovery Flow (Phase 2)

### Scenario
Multiple OJP proxy nodes. Node 1 crashes with PREPARED transactions. TM broadcasts recovery to all nodes.

### Flow Diagram

```
┌────────┐     ┌────────┐     ┌─────────┐     ┌─────────┐     ┌─────────┐
│   TM   │     │ Driver │     │  Node 1 │     │  Node 2 │     │ Backend │
│(Recovery)    │(XARes) │     │ (DOWN)  │     │(RUNNING)│     │   DB    │
└───┬────┘     └───┬────┘     └─────────┘     └────┬────┘     └────┬────┘
    │              │                                │               │
    │              │          [Node 1 crashed]      │               │
    │              │          [Had PREPARED txns]   │               │
    │              │                                │               │
    │ 1. recover(TMSTARTRSCAN)                     │               │
    ├─────────────>│                                │               │
    │              │                                │               │
    │              │ [Broadcast to all known nodes] │               │
    │              │                                │               │
    │              │ xaRecover(XaRecoverRequest)    │               │
    │              ├───────────────────────────────>│               │
    │              │                                │               │
    │              │                [Node 2 queries its backend]    │
    │              │                                │ recover()     │
    │              │                                ├──────────────>│
    │              │                                │               │
    │              │                [Backend returns empty - no     │
    │              │                 PREPARED txns on this node]    │
    │              │                                │<──────────────┤
    │              │                                │ Xid[] {}      │
    │              │<───────────────────────────────┤               │
    │              │ XaRecoverResponse{} (empty)    │               │
    │              │                                │               │
    │              │ [Node 1 is down - no response] │               │
    │              │ [OR Node 1 restarted and has   │               │
    │              │  no in-memory state]           │               │
    │              │                                │               │
    │              │ [Aggregate responses]          │               │
    │<─────────────┤ Xid[] {} (if Node 1 down)      │               │
    │ Xid[] {}     │                                │               │
    │              │                                │               │
    │ [TM may need to query backup log or          │               │
    │  database directly for orphaned txns]        │               │
    │              │                                │               │
```

**Note**: In multinode scenario with broadcast:
- If node holding PREPARED transaction is down, that node cannot respond
- TM must have independent record of which transactions were prepared
- TM may need to query backend database directly or use backup transaction log
- Alternative: Shared durable store (rejected in favor of simplicity)

**Implication**: Manual intervention may be required if node crashes and cannot be restarted before recovery completes.

---

## 4. Key Observations

### Session Lifecycle
1. **Borrow**: On `xaStart(TMNOFLAGS)` - session allocated to Xid
2. **Pinned**: From `xaStart` through `xaEnd` and `xaPrepare` - session NOT returned to pool
3. **Still Pinned**: After `xaPrepare(XA_OK)` - session held until commit/rollback
4. **Return**: Only after `xaCommit` or `xaRollback` - session reset and returned to pool

### State Persistence
- **Proxy**: In-memory only (`TxContext` with state machine)
- **Backend**: Durable (transaction log persists PREPARED state)
- **Recovery**: Proxy queries backend to rebuild state after crash

### Idempotency
- `xaCommit` and `xaRollback` must be idempotent
- If Xid not in contexts map during recovery, still attempt backend operation
- Backend XAResource handles duplicate commit/rollback gracefully

### Error Handling
- Pool exhaustion during `xaStart`: Return `XAER_RMERR`
- Invalid state transition: Return `XAER_PROTO`
- Backend connection failure: Return `XAER_RMFAIL`
- Unknown Xid (except during recovery): Return `XAER_NOTA`

---

## 5. Performance Characteristics

### Successful Commit (No Crash)
- **Pool Operations**: 2 (borrow on start, return on commit)
- **Backend XA Calls**: 4 (start, end, prepare, commit)
- **State Transitions**: 4 (NONEXISTENT→ACTIVE→ENDED→PREPARED→COMMITTED)
- **Durability Writes**: 0 on proxy, 1 on backend (prepare writes to DB transaction log)

### Recovery
- **Pool Operations**: 1 per recover call + 1 per commit/rollback
- **Backend XA Calls**: 1 recover + 1 per Xid (commit or rollback)
- **No proxy-side I/O**: All state read from backend database

---

## Appendix: Method Signatures

### XATransactionRegistry
```java
public void xaStart(XidKey xid, int flags) throws XAException
public void xaEnd(XidKey xid, int flags) throws XAException
public int xaPrepare(XidKey xid) throws XAException
public void xaCommit(XidKey xid, boolean onePhase) throws XAException
public void xaRollback(XidKey xid) throws XAException
public List<XidKey> xaRecover(int flags) throws XAException
```

### BackendSessionPool
```java
public BackendSession borrowObject() throws Exception
public void returnObject(BackendSession session)
public void invalidateObject(BackendSession session)
```

### BackendSession
```java
public XAResource getXAResource()
public Connection getConnection()
public void reset() throws SQLException
public boolean isHealthy()
```

### Backend XAResource (Database)
```java
public void start(Xid xid, int flags) throws XAException
public void end(Xid xid, int flags) throws XAException
public int prepare(Xid xid) throws XAException
public void commit(Xid xid, boolean onePhase) throws XAException
public void rollback(Xid xid) throws XAException
public Xid[] recover(int flag) throws XAException
```

---

**End of Document**
