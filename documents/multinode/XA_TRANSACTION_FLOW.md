# XA Transaction Flow in OJP

This document describes the complete flow of a simple XA transaction through the OJP JDBC driver and OJP server, including interactions with PostgreSQL's XA implementation.

> **Note on XA Backend Session Pooling**: As of the unified connection model implementation, XA connections use backend session pooling with Apache Commons Pool 2. This document describes the transaction flow; for details on pool management, session lifecycle, and configuration, see [XA Management Guide](./XA_MANAGEMENT.md).

## Transaction ID (Xid) Formation

**Where:** Client-side (OJP JDBC Driver test code)
**Who Creates It:** Application/Transaction Manager (in tests: `PostgresXAIntegrationTest.createXid()`)

```java
// Client creates Xid with:
// - formatId: 1 (application-defined)
// - globalTransactionId: "global-tx-1" (unique across all transactions)
// - branchQualifier: "branch-1" (unique per resource in distributed transaction)
```

The Xid is then passed through all XA operations to identify the transaction.

## Complete XA Transaction Flow

### Phase 0: Setup (Before XA Transaction)

#### Step 1: Create XA DataSource
**Location:** Client - `OjpXADataSource`
```
Application → OjpXADataSource(url, user, password)
```
- Stores connection parameters
- No server communication yet

#### Step 2: Get XA Connection
**Location:** Client → Server gRPC call
```
Application → OjpXADataSource.getXAConnection()
  → OjpXAConnection.<init>()
    → StatementServiceGrpcClient.connect(url, user, password, isXA=true)
      → [gRPC] StatementService.connect()
        → StatementServiceImpl.connect() [SERVER]
          → handleXAConnectionWithPooling() // XA backend session pooling
            → XATransactionRegistry.getOrCreatePoolForServer()
            → CommonsPool2XADataSource.borrowSession() // Pool operation
              → BackendSessionFactory.makeObject()
                → Creates PGXADataSource
                → xaDataSource.getXAConnection(user, password) [POSTGRESQL XA]
                → Wraps in BackendSessionImpl
            → BackendSession.open() // Initialize connection
              → connection = xaConnection.getConnection() [POSTGRESQL XA]
              → connection.setAutoCommit(true) // For non-XA operations
            → Returns BackendSession (pooled)
          → sessionManager.createXASession(sessionUUID, backendSession)
          → Returns SessionInfo(sessionUUID, isXA=true)
```

**Backend Session Pooling:**
- XA connections now use Apache Commons Pool 2 for connection pooling
- Each server maintains independent pool (configured via `ojp.xa.connection.pool.*`)
- Sessions borrowed from pool on connect, returned when OJP session closes
- Pools dynamically resize during multinode failover/recovery
- See [XA Management Guide](./XA_MANAGEMENT.md) for pool lifecycle details

**Why Both XAConnection and Regular Connection?**

The JDBC XA specification requires both:

1. **XAConnection** (`javax.sql.XAConnection`):
   - Provides access to the `XAResource` for transaction coordination
   - Used by the transaction manager to control XA operations (start, end, prepare, commit, rollback)
   - Obtained from `XADataSource.getXAConnection()`

2. **Regular Connection** (`java.sql.Connection`):
   - Obtained from `xaConnection.getConnection()` per JDBC spec
   - Used for executing SQL statements (SELECT, INSERT, UPDATE, DELETE)
   - Associated with the XA transaction but doesn't expose XA control methods

**Key Point**: In JDBC XA architecture, you need BOTH objects because:
- **XAResource** (from XAConnection) = Transaction control plane
- **Connection** (from XAConnection.getConnection()) = Data plane for SQL operations

This separation ensures proper transaction boundaries - SQL statements execute through the Connection while the transaction manager coordinates via XAResource, preventing applications from directly calling commit/rollback on the Connection (which would conflict with XA protocol).

**Server State Created:**
- Session object with:
  - `sessionUUID`: Unique identifier
  - `isXA`: true
  - `backendSession`: Pooled BackendSession wrapper containing:
    - `xaConnection`: PostgreSQL XAConnection instance (pooled)
    - `connection`: Regular JDBC connection from XAConnection
    - `xaResource`: PostgreSQL XAResource instance
    - `serverEndpoint`: Server identity for multinode coordination
    - Pool state: borrowed (tracked by Commons Pool 2)

**Info Stored:**
- Client: sessionInfo (UUID, isXA flag), statementService reference
- Server: Session in SessionManager map, keyed by UUID
- Pool: BackendSession marked as "active" (borrowed) in pool metrics
- XA Registry: Transaction contexts mapped by Xid (created on xaStart)

#### Step 3: Get Connection from XA Connection
**Location:** Client-side only
```
Application → xaConnection.getConnection()
  → Returns OjpXALogicalConnection(sessionInfo, statementService, dbName)
```
- Creates logical wrapper around XA session
- No server communication
- Prevents direct commit/rollback (must use XA protocol)

#### Step 4: Get XA Resource
**Location:** Client-side only
```
Application → xaConnection.getXAResource()
  → Returns OjpXAResource(sessionInfo, statementService)
```
- Provides XA control operations
- No server communication

---

### Phase 1: XA Transaction Start

#### Step 5: Start XA Transaction
**Location:** Client → Server gRPC call
```
Application → xaResource.start(xid, TMNOFLAGS)
  → OjpXAResource.start()
    → StatementServiceGrpcClient.xaStart(sessionInfo, xid, flags)
      → [gRPC] StatementService.xaStart()
        → StatementServiceImpl.xaStart() [SERVER]
          → session = sessionManager.getSession(sessionUUID)
          → xidImpl = convertXid(xidProto) // Convert protobuf to Xid
          → XATransactionRegistry.registerTransaction(xid, backendSession, ojpSession)
            → Creates TxContext with state machine (NONEXISTENT → ACTIVE)
            → Tracks ojpSessionId for dual-condition lifecycle
          → session.getXaResource().start(xidImpl, flags) [POSTGRESQL XA]
```

**PostgreSQL XA Behavior:**
- Internally calls `XAConnection.getConnection()` if not already obtained
- Associates the Xid with the connection
- Sets connection to XA transaction mode (auto-commit disabled)
- Stores Xid in internal PostgreSQL XA state

**OJP Behavior (XA Registry):**
- Creates TxContext to track transaction state
- Registers Xid → BackendSession mapping for transaction lifecycle
- Tracks ojpSessionId to prevent cross-session session leaks
- State machine: NONEXISTENT → ACTIVE

**Transaction State:**
- PostgreSQL: Xid → Connection mapping active
- Connection: auto-commit OFF, in XA transaction
- XA Registry: TxContext tracking transaction (state=ACTIVE, transactionComplete=false)
- Backend Session: Still borrowed from pool, bound to transaction

---

### Phase 2: Execute SQL Operations

#### Step 6: Execute SQL in XA Transaction
**Location:** Client → Server gRPC call
```
Application → connection.createStatement().executeUpdate("INSERT INTO...")
  → OjpXALogicalConnection.createStatement()
    → Inherits from Connection class
      → Statement.executeUpdate(sql)
        → StatementServiceGrpcClient.executeUpdate(sessionInfo, sql, params,...)
          → [gRPC] StatementService.executeUpdate()
            → StatementServiceImpl.executeUpdate() [SERVER]
              → session = sessionManager.getSession(sessionUUID)
              → connection = session.getConnection()
              → connection.createStatement().executeUpdate(sql) [POSTGRESQL]
```

**PostgreSQL Behavior:**
- Executes SQL within XA transaction context
- Changes are NOT committed (transaction still open)
- Changes are visible only to this connection
- PostgreSQL tracks changes internally for the Xid

**OJP Behavior:**
- Routes SQL to the XA session's connection
- No awareness of XA transaction state

---

### Phase 3: End XA Transaction Branch

#### Step 7: End XA Transaction
**Location:** Client → Server gRPC call
```
Application → xaResource.end(xid, TMSUCCESS)
  → OjpXAResource.end()
    → StatementServiceGrpcClient.xaEnd(sessionInfo, xid, flags)
      → [gRPC] StatementService.xaEnd()
        → StatementServiceImpl.xaEnd() [SERVER]
          → session = sessionManager.getSession(sessionUUID)
          → xidImpl = convertXid(xidProto)
          → session.getXaResource().end(xidImpl, flags) [POSTGRESQL XA]
```

**PostgreSQL XA Behavior:**
- Marks the transaction branch as complete
- Transaction still open, but no more work can be done
- Prepares for two-phase commit
- Stores state: "transaction branch ended, ready for prepare"

**OJP Behavior:**
- Delegates to PostgreSQL XA

---

### Phase 4: Prepare (First Phase of 2PC)

#### Step 8: Prepare XA Transaction
**Location:** Client → Server gRPC call
```
Application → xaResource.prepare(xid)
  → OjpXAResource.prepare()
    → StatementServiceGrpcClient.xaPrepare(sessionInfo, xid)
      → [gRPC] StatementService.xaPrepare()
        → StatementServiceImpl.xaPrepare() [SERVER]
          → session = sessionManager.getSession(sessionUUID)
          → xidImpl = convertXid(xidProto)
          → result = session.getXaResource().prepare(xidImpl) [POSTGRESQL XA]
          → Returns XA_OK or XA_RDONLY
```

**PostgreSQL XA Behavior:**
- Flushes all changes to disk
- Creates prepared transaction in PostgreSQL
- Transaction ID stored in pg_prepared_xacts
- Transaction can survive crashes
- Returns XA_OK if changes present, XA_RDONLY if read-only

**OJP Behavior:**
- Passes through result code

**Transaction State:**
- PostgreSQL: Transaction in "prepared" state
- Can be committed or rolled back even after server restart

---

### Phase 5: Commit (Second Phase of 2PC)

#### Step 9: Commit XA Transaction
**Location:** Client → Server gRPC call
```
Application → xaResource.commit(xid, false) // false = two-phase
  → OjpXAResource.commit()
    → StatementServiceGrpcClient.xaCommit(sessionInfo, xid, onePhase)
      → [gRPC] StatementService.xaCommit()
        → StatementServiceImpl.xaCommit() [SERVER]
          → session = sessionManager.getSession(sessionUUID)
          → xidImpl = convertXid(xidProto)
          → session.getXaResource().commit(xidImpl, onePhase) [POSTGRESQL XA]
          → XATransactionRegistry.markTransactionComplete(xid)
            → TxContext: transactionComplete = true
            → State machine: PREPARED → COMMITTED
            → Backend session NOT returned to pool yet (dual-condition lifecycle)
```

**PostgreSQL XA Behavior:**
- Commits the prepared transaction
- Makes all changes permanent
- Removes entry from pg_prepared_xacts
- Transaction complete

**OJP Behavior (XA Registry):**
- Marks transaction as complete in TxContext
- Updates state machine: PREPARED → COMMITTED
- Backend session remains bound to OJP session (NOT returned to pool)
- Session will be returned when BOTH conditions met:
  1. ✅ Transaction complete (commit/rollback called)
  2. ⏳ XAConnection closed (OJP session terminated)

**Transaction State:**
- PostgreSQL: Transaction committed, Xid no longer tracked
- XA Registry: TxContext marked complete (transactionComplete=true)
- Backend Session: Still borrowed, bound to OJP session
- Pool: Session still tracked as "active" (not yet returned)

---

### Alternative: Rollback Instead of Commit

#### Step 9 (Alternative): Rollback XA Transaction
**Location:** Client → Server gRPC call
```
Application → xaResource.rollback(xid)
  → OjpXAResource.rollback()
    → StatementServiceGrpcClient.xaRollback(sessionInfo, xid)
      → [gRPC] StatementService.xaRollback()
        → StatementServiceImpl.xaRollback() [SERVER]
          → session = sessionManager.getSession(sessionUUID)
          → xidImpl = convertXid(xidProto)
          → session.getXaResource().rollback(xidImpl) [POSTGRESQL XA]
```

**PostgreSQL XA Behavior:**
- Rolls back the transaction
- Discards all changes
- Removes entry from pg_prepared_xacts
- Transaction complete, no changes committed

---

### Phase 6: Cleanup

#### Step 10: Close Connection (Dual-Condition Lifecycle)
**Location:** Client → Server gRPC call
```
Application → connection.close()
  → OjpXALogicalConnection.close()
    → Calls super.close()
      → Connection.close()
        → StatementServiceGrpcClient.terminateSession(sessionInfo)
          → [gRPC] StatementService.terminateSession()
            → StatementServiceImpl.terminateSession() [SERVER]
              → session = sessionManager.getSession(sessionUUID)
              → session.terminate()
                → FOR XA BACKEND SESSIONS (pooled):
                  → NO cleanup here (pool manages lifecycle)
                  → XATransactionRegistry.returnCompletedSessions(ojpSessionId)
                    → Finds all TxContext where:
                      - ojpSessionId matches
                      - transactionComplete = true
                    → For each completed transaction:
                      → poolProvider.returnSession(backendSession)
                        → Commons Pool 2: pool.returnObject(backendSession)
                        → BackendSessionFactory.passivateObject()
                          → backendSession.reset() // Clean state
                          → Connection stays open (recycled for next use)
                        → Pool metrics: active--, idle++
              → sessionManager.removeSession(sessionUUID)
```

**Backend Session Pooling Behavior:**
- Backend session returned to pool (NOT closed)
- Commons Pool 2 calls passivateObject() → reset() to clean state
- Physical PostgreSQL XAConnection stays open for reuse
- Pool metrics updated: session moves from "active" to "idle"
- Next XA connection request reuses this session from idle pool

**Dual-Condition Lifecycle:**
Both conditions must be met before session returned to pool:
1. ✅ Transaction complete (commit/rollback called) - met in Step 9
2. ✅ XAConnection closed (OJP session terminated) - met in Step 10

This ensures:
- Sessions not returned mid-transaction
- Multiple sequential transactions can use same backend session
- Proper resource cleanup only when OJP session ends
- XA spec compliance (connection properties persist across transactions)

**What Gets Closed:**
- ❌ PostgreSQL XAConnection: NO (kept open, returned to pool)
- ✅ OJP Session: YES (removed from SessionManager)
- ✅ gRPC Channel state: YES (client-side cleanup)

**Pool State After:**
- Backend session: idle in pool, ready for reuse
- Pool size unchanged (session recycled, not destroyed)
- Physical PostgreSQL connection: open, connected

---

## Why New Connection Classes Were Needed

### OjpXAConnection

**Purpose**: Implements `javax.sql.XAConnection` interface to provide XA functionality.

**Why Needed:**
- **JDBC XA Spec Requirement**: XA-capable data sources must provide `XAConnection` objects
- **Interface Incompatibility**: `XAConnection` is NOT a subclass of `Connection` - it's a separate interface
- **Dual Responsibilities**: Must provide both:
  - `getXAResource()` → For transaction control by transaction manager
  - `getConnection()` → For SQL execution by application code

**Why Existing Connection Can't Be Reused:**
- Existing `Connection` class doesn't implement `XAConnection` interface
- Existing `Connection` class has no concept of `XAResource`
- XA connections need special session creation with `isXA=true` flag
- Need to manage XA-specific lifecycle (XAConnection vs logical Connection)

### OjpXALogicalConnection

**Purpose**: Wraps the actual Connection returned by `XAConnection.getConnection()` to enforce XA rules.

**Why Needed:**
- **Prevent Direct Transaction Control**: Must block `commit()`, `rollback()`, `setAutoCommit()` calls
  - In XA mode, ONLY the `XAResource` can control transactions
  - Application calling `connection.commit()` would bypass 2PC protocol
- **Enforce XA Invariants**: 
  - `getAutoCommit()` must always return `false` for XA connections
  - Transaction boundaries controlled exclusively via XAResource
- **Proper Resource Cleanup**: Ensure connection closure doesn't interfere with ongoing XA transactions

**Why Existing Connection Can't Be Reused:**
- No mechanism to block commit/rollback methods
- No way to enforce XA transaction rules
- Would allow applications to violate XA protocol by calling commit directly

### Architecture Comparison

**Regular (Non-XA) Flow:**
```
Application → Connection (OJP Connection) → Server Session → Database Connection
```

**XA Flow:**
```
Transaction Manager → XAResource (OjpXAResource) → Server XA Session → Database XAResource
Application → Logical Connection (OjpXALogicalConnection) → Server XA Session → Database Connection
                                                                 ↓ (same session)
```

**Key Difference**: In XA mode, transaction control and SQL execution use the SAME server session but different client interfaces to enforce proper XA protocol.

---

## Key Points

### OJP's Role
- **Backend Session Pooling**: OJP manages XA connection pools using Apache Commons Pool 2
- **Dual-Condition Lifecycle**: Sessions returned to pool when BOTH transaction complete AND XAConnection closed
- **Delegation Pattern**: OJP doesn't implement XA logic, it delegates to PostgreSQL XAResource
- **Session Management**: OJP manages the OJP session lifecycle and routes operations to pooled backend sessions
- **Protocol Translation**: Converts between gRPC and JDBC/XA interfaces
- **Pool Coordination**: Coordinates pool sizing across multinode servers during failover/recovery

### Backend Session Pool's Role
- **Connection Reuse**: Physical XAConnections recycled across multiple OJP sessions
- **State Management**: BackendSession.reset() cleans state between uses while keeping connection open
- **Resource Efficiency**: Eliminates connection establishment overhead (50-200ms per transaction)
- **Dynamic Resizing**: Pools grow/shrink based on multinode cluster health
- **Lifecycle Control**: Pool factory methods (makeObject, passivateObject, destroyObject) manage full lifecycle

### PostgreSQL XA's Role
- **Transaction Management**: Implements all XA protocol logic
- **Persistence**: Handles prepared transactions, crash recovery
- **State Tracking**: Maintains Xid → Connection mappings

### Transaction ID (Xid)
- **Created By**: Application/Transaction Manager (client-side)
- **Travels Through**: Client → gRPC (protobuf) → Server → PostgreSQL XA
- **Converted At**: Server (StatementServiceImpl.convertXid())
- **Used By**: PostgreSQL XA for transaction identification

### Where State is Stored

#### Client Side:
- `sessionInfo`: Contains UUID and isXA flag
- `statementService`: Reference for gRPC communication
- No transaction state
- No pool state

#### Server Side (OJP):
- `Session` object: Maps UUID → BackendSession (pooled)
- `SessionManager`: Maps UUID → Session
- `XATransactionRegistry`: Maps Xid → TxContext (transaction state machine)
- `TxContext`: Tracks transaction state (ACTIVE/ENDED/PREPARED/COMMITTED/ROLLEDBACK)
- `CommonsPool2XADataSource`: Pool per server endpoint (active, idle, maxTotal metrics)
- No PostgreSQL transaction state (delegated to database)

#### PostgreSQL Side:
- XA transaction state: Xid → Connection mapping
- Prepared transactions: In pg_prepared_xacts
- Transaction log: For crash recovery

### Auto-commit Behavior
1. **Initial**: `setAutoCommit(true)` when XA connection created
2. **During xaStart()**: PostgreSQL automatically sets auto-commit OFF
3. **After commit/rollback**: PostgreSQL restores previous auto-commit state

This allows DDL operations before XA transactions while ensuring XA control during transactions.
