# StatementServiceImpl Action Pattern Refactoring Guide

## Overview

This guide explains how to refactor `StatementServiceImpl` using the Action pattern. The `connect()` method has been implemented as a reference - use it as a template for refactoring the remaining 20 public methods.

**Reference Implementation**: [PR #214](https://github.com/Open-J-Proxy/ojp/pull/214)

## Why Refactor?

`StatementServiceImpl` is a 2,528-line God class with 21 public methods. The Action pattern splits this into:
- **StatementServiceImpl**: Thin orchestrator (~400 lines)
- **35+ Action classes**: Focused, testable units (~75 lines each)
- **ActionContext**: Centralized shared state holder

## Architecture Diagrams

### Class Diagram

```mermaid
classDiagram
    class StatementServiceImpl {
        -ActionContext actionContext
        +connect(ConnectionDetails, StreamObserver)
        +executeUpdate(StatementRequest, StreamObserver)
        +executeQuery(StatementRequest, StreamObserver)
        +startTransaction(SessionInfo, StreamObserver)
        +commitTransaction(SessionInfo, StreamObserver)
        +rollbackTransaction(SessionInfo, StreamObserver)
        +xaStart(XaStartRequest, StreamObserver)
        +createLob(StreamObserver) StreamObserver
        ...20+ more methods
    }

    class ActionContext {
        -Map~String,DataSource~ datasourceMap
        -Map~String,XADataSource~ xaDataSourceMap
        -Map~String,XATransactionRegistry~ xaRegistries
        -Map~String,UnpooledConnectionDetails~ unpooledConnectionDetailsMap
        -Map~String,DbName~ dbNameMap
        -Map~String,SlowQuerySegregationManager~ slowQuerySegregationManagers
        -XAConnectionPoolProvider xaPoolProvider
        -MultinodeXaCoordinator xaCoordinator
        -ClusterHealthTracker clusterHealthTracker
        -SessionManager sessionManager
        -CircuitBreaker circuitBreaker
        -ServerConfiguration serverConfiguration
        +getDatasourceMap() Map
        +getXaDataSourceMap() Map
        +getSessionManager() SessionManager
        ...getters for all fields
    }

    class Action~TRequest,TResponse~ {
        <<interface>>
        +execute(TRequest, StreamObserver~TResponse~) void
    }

    class StreamingAction~TRequest,TResponse~ {
        <<interface>>
        +execute(StreamObserver~TResponse~) StreamObserver~TRequest~
    }

    class InitAction {
        <<interface>>
        +execute() void
    }

    class ValueAction~TRequest,TResult~ {
        <<interface>>
        +execute(TRequest) TResult
    }

    class ConnectAction {
        -ActionContext context
        +execute(ConnectionDetails, StreamObserver~SessionInfo~) void
        -handleXAConnection()
        -handleRegularConnection()
    }

    class ExecuteUpdateAction {
        -ActionContext context
        +execute(StatementRequest, StreamObserver~OpResult~) void
    }

    class ExecuteQueryAction {
        -ActionContext context
        +execute(StatementRequest, StreamObserver~OpResult~) void
    }

    class StartTransactionAction {
        -ActionContext context
        +execute(SessionInfo, StreamObserver~SessionInfo~) void
    }

    class CreateLobAction {
        -ActionContext context
        +execute(StreamObserver~LobReference~) StreamObserver~LobDataBlock~
    }

    class HandleXAConnectionWithPoolingAction {
        -ActionContext context
        +execute(ConnectionDetails, StreamObserver~SessionInfo~) void
    }

    StatementServiceImpl --> ActionContext : uses
    StatementServiceImpl ..> ConnectAction : delegates
    StatementServiceImpl ..> ExecuteUpdateAction : delegates
    StatementServiceImpl ..> ExecuteQueryAction : delegates
    StatementServiceImpl ..> StartTransactionAction : delegates
    StatementServiceImpl ..> CreateLobAction : delegates
    
    ConnectAction ..|> Action : implements
    ExecuteUpdateAction ..|> Action : implements
    ExecuteQueryAction ..|> Action : implements
    StartTransactionAction ..|> Action : implements
    CreateLobAction ..|> StreamingAction : implements
    HandleXAConnectionWithPoolingAction ..|> Action : implements
    
    ConnectAction --> ActionContext : uses
    ExecuteUpdateAction --> ActionContext : uses
    ExecuteQueryAction --> ActionContext : uses
    StartTransactionAction --> ActionContext : uses
    CreateLobAction --> ActionContext : uses
    HandleXAConnectionWithPoolingAction --> ActionContext : uses
    
    ConnectAction --> HandleXAConnectionWithPoolingAction : uses
```

### Connect Method Flow (Reference Implementation)

```mermaid
sequenceDiagram
    participant Client
    participant StatementServiceImpl
    participant ConnectAction
    participant HandleXAConnectionAction
    participant ActionContext
    participant SessionManager
    participant DataSourceMap

    Client->>StatementServiceImpl: connect(ConnectionDetails)
    StatementServiceImpl->>ConnectAction: new ConnectAction(actionContext).execute()
    
    ConnectAction->>ActionContext: Generate connection hash
    ConnectAction->>ConnectAction: Check if XA or regular connection
    
    alt XA Connection
        ConnectAction->>HandleXAConnectionAction: delegate to XA handler
        HandleXAConnectionAction->>ActionContext: Get XA datasource map
        HandleXAConnectionAction->>ActionContext: Get XA pool provider
        HandleXAConnectionAction->>SessionManager: Create XA session
        HandleXAConnectionAction->>ConnectAction: return
    else Regular Connection
        ConnectAction->>ActionContext: Get datasource map
        ConnectAction->>DataSourceMap: Check for existing datasource
        alt Datasource exists
            ConnectAction->>SessionManager: Create session from datasource
        else Datasource doesn't exist
            ConnectAction->>ConnectAction: Create new datasource/unpooled config
            ConnectAction->>ActionContext: Store datasource or unpooled config
            ConnectAction->>SessionManager: Create session
        end
    end
    
    ConnectAction->>StatementServiceImpl: Complete
    StatementServiceImpl->>Client: SessionInfo response
```

## How It Works

### Action Interfaces

Choose the right interface for your method:
#### 1. Action<TRequest, TResponse>
For standard RPC methods (20 of 21 methods).

```java
public interface Action<TRequest, TResponse> {
    void execute(TRequest request, StreamObserver<TResponse> responseObserver);
}
```

**Examples**: connect, executeUpdate, executeQuery, transactions, XA operations, etc.

#### 2. StreamingAction<TRequest, TResponse>
For bidirectional streaming (1 method: createLob).

```java
public interface StreamingAction<TRequest, TResponse> {
    StreamObserver<TRequest> execute(StreamObserver<TResponse> responseObserver);
}
```

#### 3. InitAction & ValueAction
For initialization and internal helpers.

### ActionContext

Holds all shared state (maps, services) used by actions. Thread-safe with ConcurrentHashMap.

**Key fields**:
- `datasourceMap`, `xaDataSourceMap` - Database connections
- `sessionManager` - Session/connection management  
- `circuitBreaker` - Failure protection
- `serverConfiguration` - Server config

Actions access via `context.getDatasourceMap()`, `context.getSessionManager()`, etc.

## Implementation Pattern

### Before: God Class (144 lines inline)
```java
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {
    public void connect(ConnectionDetails details, StreamObserver<SessionInfo> observer) {
        // 144 lines of logic: health checks, hashing, XA branching,
        // multinode coordination, pool creation, session management...
    }
}
```

### After: Action Pattern (3 lines + focused action)
```java
// StatementServiceImpl - thin delegator
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {
    private final ActionContext actionContext;
    
    public void connect(ConnectionDetails details, StreamObserver<SessionInfo> observer) {
        new ConnectAction(actionContext).execute(details, observer);
    }
}

// ConnectAction - focused logic (~150 lines)
@Slf4j
public class ConnectAction implements Action<ConnectionDetails, SessionInfo> {
    private final ActionContext context;
    
    public ConnectAction(ActionContext context) {
        this.context = context;
    }
    
    @Override
    public void execute(ConnectionDetails request, StreamObserver<SessionInfo> responseObserver) {
        // Connection handling logic
        // Access: context.getDatasourceMap(), context.getSessionManager(), etc.
        // Delegate to helper actions: new HandleXAConnectionAction(context).execute(...)
    }
}
```

## Quick Start for Contributors

### 1. Study the Reference
Review [PR #214](https://github.com/Open-J-Proxy/ojp/pull/214) - especially `ConnectAction` and how it uses ActionContext.

### 2. Pick a Method to Refactor
Choose any of the 20 remaining public methods in `StatementServiceImpl`:
- **Simple**: `startTransaction`, `commitTransaction`, `rollbackTransaction`, `terminateSession`, XA operations (xaEnd, xaForget, etc.)
- **Medium**: `executeUpdate`, `executeQuery`, `fetchNextRows`, `callResource`, `xaStart`, `xaPrepare`
- **Complex**: `createLob`, `readLob`

### 3. Implementation Steps
1. **Create action class** in appropriate package (e.g., `org.openjproxy.grpc.server.action.transaction`)
2. **Implement interface** (usually `Action<TRequest, TResponse>`)
3. **Copy method logic** from `StatementServiceImpl` to action's `execute()` method
4. **Replace field access** with `context.getXxx()` calls
5. **Update StatementServiceImpl** to delegate: `new YourAction(actionContext).execute(request, observer);`
6. **Test** - ensure compilation and existing tests pass
7. **Submit PR** with clear description

### Common Patterns

#### Simple Delegation
```java
// In StatementServiceImpl
public void methodName(Request request, StreamObserver<Response> observer) {
    new MethodNameAction(actionContext).execute(request, observer);
}
```

#### Accessing Shared State
```java
// In Action class
DataSource ds = context.getDatasourceMap().get(connHash);
SessionManager sessionManager = context.getSessionManager();
```

#### Error Handling
```java
try {
    // Action logic
    responseObserver.onNext(response);
    responseObserver.onCompleted();
} catch (SQLException e) {
    log.error("Error in action", e);
    sendSQLExceptionMetadata(e, responseObserver);
}
```

## Package Structure

Actions are organized by functionality:

```
org.openjproxy.grpc.server.action/
├── connection/        ConnectAction ✅ (reference implementation)
├── transaction/       Start/Commit/Rollback transactions
├── statement/         ExecuteUpdate, ExecuteQuery, FetchNextRows
├── xa/                XA transaction operations (10 methods)
├── lob/               CreateLob, ReadLob (streaming)
├── session/           TerminateSession
├── resource/          CallResource
└── util/              ProcessClusterHealth ✅, helpers
```

## Benefits


- ✅ **Testability**: Each action independently testable
- ✅ **Maintainability**: ~75 line focused classes vs 2,528 line God class  
- ✅ **Code Review**: Smaller, focused PRs
- ✅ **Parallel Development**: Multiple contributors can work simultaneously
- ✅ **Debugging**: Easier to trace specific operations

---

**Get started**: Pick a method from `StatementServiceImpl`, study ConnectAction in [PR #214](https://github.com/Open-J-Proxy/ojp/pull/214), and submit your PR!
