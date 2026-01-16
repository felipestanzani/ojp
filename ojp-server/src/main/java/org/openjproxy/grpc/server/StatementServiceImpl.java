package org.openjproxy.grpc.server;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.LobType;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ReadLobRequest;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.ResultSetFetchRequest;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import com.openjproxy.grpc.StatementServiceGrpc;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.lob.LobProcessor;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.resultset.ResultSetWrapper;
import org.openjproxy.grpc.server.statement.ParameterHandler;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.utils.DateTimeUtils;
import org.openjproxy.grpc.server.utils.MethodNameGenerator;
import org.openjproxy.grpc.server.utils.MethodReflectionUtils;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;
import org.openjproxy.grpc.server.utils.StatementRequestValidator;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.XidKey;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.InputStream;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.openjproxy.grpc.server.Constants.EMPTY_LIST;
import static org.openjproxy.grpc.server.Constants.EMPTY_MAP;
import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.transaction.XidHelper.convertXid;
import static org.openjproxy.grpc.server.action.transaction.XidHelper.convertXidToProto;

@Slf4j
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {

    private final Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
    // Map for storing XADataSources (native database XADataSource, not Atomikos)
    private final Map<String, XADataSource> xaDataSourceMap = new ConcurrentHashMap<>();
    // XA Pool Provider for pooling XAConnections (loaded via SPI)
    private XAConnectionPoolProvider xaPoolProvider;
    // XA Transaction Registries (one per connection hash for isolated transaction management)
    private final Map<String, XATransactionRegistry> xaRegistries = new ConcurrentHashMap<>();
    private final SessionManager sessionManager;
    private final CircuitBreaker circuitBreaker;

    // Per-datasource slow query segregation managers
    private final Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers = new ConcurrentHashMap<>();

    // Server configuration for creating segregation managers
    private final ServerConfiguration serverConfiguration;

    // SQL Enhancer Engine for query optimization
    private final org.openjproxy.grpc.server.sql.SqlEnhancerEngine sqlEnhancerEngine;

    // Multinode XA coordinator for distributing transaction limits
    private static final MultinodeXaCoordinator xaCoordinator = new MultinodeXaCoordinator();

    // Cluster health tracker for monitoring health changes
    private final ClusterHealthTracker clusterHealthTracker = new ClusterHealthTracker();

    // Unpooled connection details map (for passthrough mode when pooling is disabled)
    private final Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap = new ConcurrentHashMap<>();

    private static final List<String> INPUT_STREAM_TYPES = Arrays.asList("RAW", "BINARY VARYING", "BYTEA");
    private final Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();

    private static final String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";

    // ActionContext for refactored actions
    private final org.openjproxy.grpc.server.action.ActionContext actionContext;

    public StatementServiceImpl(SessionManager sessionManager, CircuitBreaker circuitBreaker, ServerConfiguration serverConfiguration) {
        this.sessionManager = sessionManager;
        this.circuitBreaker = circuitBreaker;
        this.serverConfiguration = serverConfiguration;

        // Initialize SQL enhancer with full configuration
        this.sqlEnhancerEngine = createSqlEnhancerEngine(serverConfiguration);

        initializeXAPoolProvider();

        // Initialize ActionContext with all shared state
        this.actionContext = new org.openjproxy.grpc.server.action.ActionContext(
                datasourceMap,
                xaDataSourceMap,
                xaRegistries,
                unpooledConnectionDetailsMap,
                dbNameMap,
                slowQuerySegregationManagers,
                xaPoolProvider,
                xaCoordinator,
                clusterHealthTracker,
                sessionManager,
                circuitBreaker,
                serverConfiguration
        );
    }

    /**
     * Creates and configures the SQL enhancer engine based on server configuration.
     * Parses mode to determine conversion and optimization settings.
     *
     * @param config Server configuration
     * @return Configured SqlEnhancerEngine instance
     */
    private org.openjproxy.grpc.server.sql.SqlEnhancerEngine createSqlEnhancerEngine(ServerConfiguration config) {
        // Parse mode to determine conversion and optimization settings
        org.openjproxy.grpc.server.sql.SqlEnhancerMode mode =
                org.openjproxy.grpc.server.sql.SqlEnhancerMode.fromString(config.getSqlEnhancerMode());

        // Parse rules if specified, otherwise use defaults
        java.util.List<String> enabledRules = null;
        if (config.getSqlEnhancerRules() != null && !config.getSqlEnhancerRules().trim().isEmpty()) {
            enabledRules = Arrays.stream(config.getSqlEnhancerRules().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        // Create engine with full configuration including targetDialect
        return new org.openjproxy.grpc.server.sql.SqlEnhancerEngine(
                config.isSqlEnhancerEnabled(),
                config.getSqlEnhancerDialect(),
                config.getSqlEnhancerTargetDialect(),
                mode.isConversionEnabled(),
                mode.isOptimizationEnabled(),
                enabledRules
        );
    }

    /**
     * Initialize XA Pool Provider if XA pooling is enabled in configuration.
     * Loads the provider via ServiceLoader (Commons Pool 2 by default).
     */
    private void initializeXAPoolProvider() {
        // XA pooling is always enabled
        // Select the provider with the HIGHEST priority (100 = highest, 0 = lowest)

        try {
            ServiceLoader<XAConnectionPoolProvider> loader = ServiceLoader.load(XAConnectionPoolProvider.class);
            XAConnectionPoolProvider selectedProvider = null;
            int highestPriority = Integer.MIN_VALUE;

            for (XAConnectionPoolProvider provider : loader) {
                if (provider.isAvailable()) {
                    log.debug("Found available XA Pool Provider: {} (priority: {})",
                            provider.getClass().getName(), provider.getPriority());

                    if (provider.getPriority() > highestPriority) {
                        selectedProvider = provider;
                        highestPriority = provider.getPriority();
                    }
                }
            }

            if (selectedProvider != null) {
                this.xaPoolProvider = selectedProvider;
                log.info("Selected XA Pool Provider: {} (priority: {})",
                        selectedProvider.getClass().getName(), selectedProvider.getPriority());

                // Update ActionContext with initialized provider (if actionContext is already created)
                if (this.actionContext != null) {
                    this.actionContext.setXaPoolProvider(selectedProvider);
                }
            } else {
                log.warn("No available XA Pool Provider found via ServiceLoader, XA pooling will be unavailable");
            }
        } catch (Exception e) {
            log.error("Failed to load XA Pool Provider: {}", e.getMessage(), e);
        }
    }

    /**
     * Gets the target server identifier from the incoming request.
     * Simply echoes back what the client sent without any override.
     */
    private String getTargetServer(SessionInfo incomingSessionInfo) {
        // Echo back the targetServer from incoming request, or return empty string if not present
        if (incomingSessionInfo != null &&
                !incomingSessionInfo.getTargetServer().isEmpty()) {
            return incomingSessionInfo.getTargetServer();
        }

        // Return empty string if client didn't send targetServer
        return "";
    }

    /**
     * Processes cluster health from the client request and triggers pool rebalancing if needed.
     * This should be called for every request that includes SessionInfo with cluster health.
     */
    private void processClusterHealth(SessionInfo sessionInfo) {
        if (sessionInfo == null) {
            log.debug("[XA-REBALANCE-DEBUG] processClusterHealth: sessionInfo is null");
            return;
        }

        String clusterHealth = sessionInfo.getClusterHealth();
        String connHash = sessionInfo.getConnHash();

        log.debug("[XA-REBALANCE] processClusterHealth called: connHash={}, clusterHealth='{}', isXA={}, hasXARegistry={}",
                connHash, clusterHealth, sessionInfo.getIsXA(), xaRegistries.containsKey(connHash));

        if (!clusterHealth.isEmpty() && !connHash.isEmpty()) {

            // Check if cluster health has changed
            boolean healthChanged = clusterHealthTracker.hasHealthChanged(connHash, clusterHealth);

            log.debug("[XA-REBALANCE] Cluster health check for {}: changed={}, current health='{}', isXA={}",
                    connHash, healthChanged, clusterHealth, sessionInfo.getIsXA());

            if (healthChanged) {
                int healthyServerCount = clusterHealthTracker.countHealthyServers(clusterHealth);
                log.info("[XA-REBALANCE] Cluster health changed for {}, healthy servers: {}, triggering pool rebalancing, isXA={}",
                        connHash, healthyServerCount, sessionInfo.getIsXA());

                // Update the pool coordinator with new healthy server count
                ConnectionPoolConfigurer.getPoolCoordinator().updateHealthyServers(connHash, healthyServerCount);

                // Apply pool size changes to non-XA HikariDataSource if present
                DataSource ds = datasourceMap.get(connHash);
                if (ds instanceof HikariDataSource hikariDataSource) {
                    log.info("[XA-REBALANCE-DEBUG] Applying size changes to HikariDataSource for {}", connHash);
                    ConnectionPoolConfigurer.applyPoolSizeChanges(connHash, hikariDataSource);
                } else {
                    log.info("[XA-REBALANCE-DEBUG] No HikariDataSource found for {}", connHash);
                }

                // Apply pool size changes to XA registry if present
                XATransactionRegistry xaRegistry = xaRegistries.get(connHash);
                if (xaRegistry != null) {
                    log.info("[XA-REBALANCE-DEBUG] Found XA registry for {}, resizing", connHash);
                    MultinodePoolCoordinator.PoolAllocation allocation =
                            ConnectionPoolConfigurer.getPoolCoordinator().getPoolAllocation(connHash);

                    if (allocation != null) {
                        int newMaxPoolSize = allocation.getCurrentMaxPoolSize();
                        int newMinIdle = allocation.getCurrentMinIdle();

                        log.info("[XA-REBALANCE-DEBUG] Resizing XA backend pool for {}: maxPoolSize={}, minIdle={}",
                                connHash, newMaxPoolSize, newMinIdle);

                        xaRegistry.resizeBackendPool(newMaxPoolSize, newMinIdle);
                    } else {
                        log.warn("[XA-REBALANCE-DEBUG] No pool allocation found for {}", connHash);
                    }
                } else if (sessionInfo.getIsXA()) {
                    // Only log missing XA registry for actual XA connections
                    log.info("[XA-REBALANCE-DEBUG] No XA registry found for XA connection {}", connHash);
                }
            } else {
                log.debug("[XA-REBALANCE-DEBUG] Cluster health unchanged for {}", connHash);
            }
        } else {
            log.info("[XA-REBALANCE-DEBUG] Skipping cluster health processing: clusterHealth={}, connHash={}",
                    clusterHealth.isEmpty() ? "empty" : "present",
                    connHash.isEmpty() ? "empty" : "present");
        }
    }

    @Override
    public void connect(ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
        org.openjproxy.grpc.server.action.connection.ConnectAction.getInstance()
                .execute(actionContext, connectionDetails, responseObserver);
    }

    /**
     * Gets the slow query segregation manager for a specific connection hash.
     * If no manager exists, creates a disabled one as a fallback.
     */
    private SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(String connHash) {
        SlowQuerySegregationManager manager = slowQuerySegregationManagers.get(connHash);
        if (manager == null) {
            log.warn("No SlowQuerySegregationManager found for connection hash {}, creating disabled fallback", connHash);
            // Create a disabled manager as fallback
            manager = new SlowQuerySegregationManager(1, 0, 0, 0, 0, 0, false);
            slowQuerySegregationManagers.put(connHash, manager);
        }
        return manager;
    }

    @SneakyThrows
    @Override
    public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing update {}", request.getSql());
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        processClusterHealth(request.getSession());

        try {
            circuitBreaker.preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);

            // Execute with slow query segregation
            OpResult result = manager.executeWithSegregation(stmtHash, () ->
                    executeUpdateInternal(request)
            );

            responseObserver.onNext(result);
            responseObserver.onCompleted();
            circuitBreaker.onSuccess(stmtHash);

        } catch (SQLDataException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver, SqlErrorType.SQL_DATA_EXCEPTION);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during update execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException cause) {
                circuitBreaker.onFailure(stmtHash, cause);
                sendSQLExceptionMetadata(cause, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }

    /**
     * Internal method for executing updates without segregation logic.
     */
    private OpResult executeUpdateInternal(StatementRequest request) throws SQLException {
        int updated = 0;
        SessionInfo returnSessionInfo;
        ConnectionSessionDTO dto = ConnectionSessionDTO.builder().build();

        Statement stmt = null;
        String psUUID = "";
        OpResult.Builder opResultBuilder = OpResult.newBuilder();

        try {
            dto = sessionConnection(request.getSession(), StatementRequestValidator.isAddBatchOperation(request) || StatementRequestValidator.hasAutoGeneratedKeysFlag(request));
            returnSessionInfo = dto.getSession();

            List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
            PreparedStatement ps = dto.getSession() != null && StringUtils.isNotBlank(dto.getSession().getSessionUUID())
                    && StringUtils.isNoneBlank(request.getStatementUUID()) ?
                    sessionManager.getPreparedStatement(dto.getSession(), request.getStatementUUID()) : null;
            if (CollectionUtils.isNotEmpty(params) || ps != null) {
                if (StringUtils.isNotEmpty(request.getStatementUUID())) {
                    Collection<Object> lobs = sessionManager.getLobs(dto.getSession());
                    for (Object o : lobs) {
                        LobDataBlocksInputStream lobIS = (LobDataBlocksInputStream) o;
                        Map<String, Object> metadata = (Map<String, Object>) sessionManager.getAttr(dto.getSession(), lobIS.getUuid());
                        Integer parameterIndex = (Integer) metadata.get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_INDEX);
                        ps.setBinaryStream(parameterIndex, lobIS);
                    }
                    if (DbName.POSTGRES.equals(dto.getDbName())) {//Postgres requires check if the lob streams are fully consumed.
                        sessionManager.waitLobStreamsConsumption(dto.getSession());
                    }
                    if (ps != null) {
                        ParameterHandler.addParametersPreparedStatement(sessionManager, dto.getSession(), ps, params);
                    }
                } else {
                    ps = StatementFactory.createPreparedStatement(sessionManager, dto, request.getSql(), params, request);
                    if (StatementRequestValidator.hasAutoGeneratedKeysFlag(request)) {
                        String psNewUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                        opResultBuilder.setUuid(psNewUUID);
                    }
                }
                if (StatementRequestValidator.isAddBatchOperation(request)) {
                    ps.addBatch();
                    if (request.getStatementUUID().isBlank()) {
                        psUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                    } else {
                        psUUID = request.getStatementUUID();
                    }
                } else {
                    updated = ps.executeUpdate();
                }
                stmt = ps;
            } else {
                stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
                updated = stmt.executeUpdate(request.getSql());
            }

            if (StatementRequestValidator.isAddBatchOperation(request)) {
                return opResultBuilder
                        .setType(ResultType.UUID_STRING)
                        .setSession(returnSessionInfo)
                        .setUuidValue(psUUID).build();
            } else {
                return opResultBuilder
                        .setType(ResultType.INTEGER)
                        .setSession(returnSessionInfo)
                        .setIntValue(updated).build();
            }
        } finally {
            //If there is no session, close statement and connection
            if ((dto.getSession() == null || StringUtils.isEmpty(dto.getSession().getSessionUUID())) && stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.error("Failure closing statement: " + e.getMessage(), e);
                }
                try {
                    stmt.getConnection().close();
                } catch (SQLException e) {
                    log.error("Failure closing connection: " + e.getMessage(), e);
                }
            }

        }
    }

    @Override
    public void executeQuery(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        processClusterHealth(request.getSession());

        try {
            circuitBreaker.preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);

            // Execute with slow query segregation
            manager.executeWithSegregation(stmtHash, () -> {
                executeQueryInternal(request, responseObserver);
                return null; // Void return for query execution
            });

            circuitBreaker.onSuccess(stmtHash);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during query execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during query execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException cause) {
                circuitBreaker.onFailure(stmtHash, cause);
                sendSQLExceptionMetadata(cause, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }

    /**
     * Internal method for executing queries without segregation logic.
     */
    private void executeQueryInternal(StatementRequest request, StreamObserver<OpResult> responseObserver) throws SQLException {
        ConnectionSessionDTO dto = this.sessionConnection(request.getSession(), true);

        // Phase 2: SQL Enhancement with timing
        String sql = request.getSql();
        long enhancementStartTime = System.currentTimeMillis();

        if (sqlEnhancerEngine.isEnabled()) {
            org.openjproxy.grpc.server.sql.SqlEnhancementResult result = sqlEnhancerEngine.enhance(sql);
            sql = result.getEnhancedSql();

            long enhancementDuration = System.currentTimeMillis() - enhancementStartTime;

            if (result.isModified()) {
                log.debug("SQL was enhanced in {}ms: {} -> {}", enhancementDuration,
                        request.getSql().substring(0, Math.min(request.getSql().length(), 50)),
                        sql.substring(0, Math.min(sql.length(), 50)));
            } else if (enhancementDuration > 10) {
                log.debug("SQL enhancement took {}ms (no modifications)", enhancementDuration);
            }
        }

        List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
        if (CollectionUtils.isNotEmpty(params)) {
            String resultSetUUID;
            try (PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, dto, sql, params, request)) {
                resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
            }
            this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
        } else {
            Statement stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
            String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(),
                    stmt.executeQuery(sql));
            this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
        }
    }

    @Override
    public void fetchNextRows(ResultSetFetchRequest request, StreamObserver<OpResult> responseObserver) {
        log.debug("Executing fetch next rows for result set  {}", request.getResultSetUUID());

        // Process cluster health from the request
        processClusterHealth(request.getSession());

        try {
            ConnectionSessionDTO dto = this.sessionConnection(request.getSession(), false);
            this.handleResultSet(dto.getSession(), request.getResultSetUUID(), responseObserver);
        } catch (SQLException e) {
            log.error("Failure fetch next rows for result set: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        }
    }

    @Override
    public StreamObserver<LobDataBlock> createLob(StreamObserver<LobReference> responseObserver) {
        log.info("Creating LOB");
        return new ServerCallStreamObserver<>() {
            private SessionInfo sessionInfo;
            private String lobUUID;
            private String stmtUUID;
            private LobType lobType;
            private LobDataBlocksInputStream lobDataBlocksInputStream = null;
            private final AtomicBoolean isFirstBlock = new AtomicBoolean(true);
            private final AtomicInteger countBytesWritten = new AtomicInteger(0);

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void setOnCancelHandler(Runnable runnable) {
                // Implementation not needed
            }

            @Override
            public void setCompression(String s) {
                // Implementation not needed
            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void setOnReadyHandler(Runnable runnable) {
                // Implementation not needed
            }

            @Override
            public void request(int i) {
                // Implementation not needed
            }

            @Override
            public void setMessageCompression(boolean b) {
                // Implementation not needed
            }

            @Override
            public void disableAutoInboundFlowControl() {
                // Implementation not needed
            }

            @Override
            public void onNext(LobDataBlock lobDataBlock) {
                try {
                    this.lobType = lobDataBlock.getLobType();
                    log.info("lob data block received, lob type {}", this.lobType);
                    ConnectionSessionDTO dto = sessionConnection(lobDataBlock.getSession(), true);
                    Connection conn = dto.getConnection();
                    if (StringUtils.isEmpty(lobDataBlock.getSession().getSessionUUID()) || this.lobUUID == null) {
                        if (LobType.LT_BLOB.equals(this.lobType)) {
                            Blob newBlob = conn.createBlob();
                            this.lobUUID = UUID.randomUUID().toString();
                            sessionManager.registerLob(dto.getSession(), newBlob, this.lobUUID);
                        } else if (LobType.LT_CLOB.equals(this.lobType)) {
                            Clob newClob = conn.createClob();
                            this.lobUUID = UUID.randomUUID().toString();
                            sessionManager.registerLob(dto.getSession(), newClob, this.lobUUID);
                        }
                    }

                    int bytesWritten = 0;
                    switch (this.lobType) {
                        case LT_BLOB: {
                            Blob blob = sessionManager.getLob(dto.getSession(), this.lobUUID);
                            if (blob == null) {
                                throw new SQLException("Unable to write LOB of type " + this.lobType + ": Blob object is null for UUID " + this.lobUUID +
                                        ". This may indicate a race condition or session management issue.");
                            }
                            byte[] byteArrayData = lobDataBlock.getData().toByteArray();
                            bytesWritten = blob.setBytes(lobDataBlock.getPosition(), byteArrayData);
                            break;
                        }
                        case LT_CLOB: {
                            Clob clob = sessionManager.getLob(dto.getSession(), this.lobUUID);
                            if (clob == null) {
                                throw new SQLException("Unable to write LOB of type " + this.lobType + ": Clob object is null for UUID " + this.lobUUID +
                                        ". This may indicate a race condition or session management issue.");
                            }
                            byte[] byteArrayData = lobDataBlock.getData().toByteArray();
                            try (Writer writer = clob.setCharacterStream(lobDataBlock.getPosition())) {
                                writer.write(new String(byteArrayData, StandardCharsets.UTF_8).toCharArray());
                            }
                            bytesWritten = byteArrayData.length;
                            break;
                        }
                        case LT_BINARY_STREAM: {
                            if (this.lobUUID == null) {
                                if (lobDataBlock.getMetadataCount() < 1) {
                                    throw new SQLException("Metadata empty for binary stream type.");
                                }
                                Map<String, Object> metadataStringKey = ProtoConverter.propertiesFromProto(lobDataBlock.getMetadataList());

                                // Convert string keys back to integer keys for backward compatibility
                                Map<Integer, Object> metadata = new java.util.HashMap<>();
                                for (Map.Entry<String, Object> entry : metadataStringKey.entrySet()) {
                                    try {
                                        metadata.put(Integer.parseInt(entry.getKey()), entry.getValue());
                                    } catch (NumberFormatException e) {
                                        // Keep as string key if not parseable
                                        metadata.put(entry.getKey().hashCode(), entry.getValue());
                                    }
                                }

                                String sql = (String) metadata.get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_SQL);
                                PreparedStatement ps;
                                String preparedStatementUUID = (String) metadata.get(CommonConstants.PREPARED_STATEMENT_UUID_BINARY_STREAM);
                                if (StringUtils.isNotEmpty(preparedStatementUUID)) {
                                    stmtUUID = preparedStatementUUID;
                                } else {
                                    ps = dto.getConnection().prepareStatement(sql);
                                    stmtUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                                }

                                //Add bite stream as parameter to the prepared statement
                                lobDataBlocksInputStream = new LobDataBlocksInputStream(lobDataBlock);
                                this.lobUUID = lobDataBlocksInputStream.getUuid();
                                //Only needs to be registered so we can wait it to receive all bytes before performing the update.
                                sessionManager.registerLob(dto.getSession(), lobDataBlocksInputStream, lobDataBlocksInputStream.getUuid());
                                sessionManager.registerAttr(dto.getSession(), lobDataBlocksInputStream.getUuid(), metadata);
                                //Need to first send the ref to the client before adding the stream as a parameter
                                sendLobRef(dto, lobDataBlock.getData().toByteArray().length);
                            } else {
                                lobDataBlocksInputStream.addBlock(lobDataBlock);
                            }
                            break;
                        }
                    }
                    this.countBytesWritten.addAndGet(bytesWritten);
                    this.sessionInfo = dto.getSession();

                    if (isFirstBlock.get()) {
                        sendLobRef(dto, bytesWritten);
                    }

                } catch (SQLException e) {
                    sendSQLExceptionMetadata(e, responseObserver);
                } catch (Exception e) {
                    sendSQLExceptionMetadata(new SQLException("Unable to write data: " + e.getMessage(), e), responseObserver);
                }
            }

            private void sendLobRef(ConnectionSessionDTO dto, int bytesWritten) {
                log.info("Returning lob ref {}", this.lobUUID);
                //Send one flag response to indicate that the Blob has been created successfully and the first
                // block fo data has been written successfully.
                LobReference.Builder lobRefBuilder = LobReference.newBuilder()
                        .setSession(dto.getSession())
                        .setUuid(this.lobUUID)
                        .setLobType(this.lobType)
                        .setBytesWritten(bytesWritten);
                if (this.stmtUUID != null) {
                    lobRefBuilder.setStmtUUID(this.stmtUUID);
                }
                responseObserver.onNext(lobRefBuilder.build());
                isFirstBlock.set(false);
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Failure lob stream: " + throwable.getMessage(), throwable);
                if (lobDataBlocksInputStream != null) {
                    lobDataBlocksInputStream.finish(true);
                }
            }

            @SneakyThrows
            @Override
            public void onCompleted() {
                if (lobDataBlocksInputStream != null) {
                    CompletableFuture.runAsync(() -> {
                        log.info("Finishing lob stream for lob ref {}", this.lobUUID);
                        lobDataBlocksInputStream.finish(true);
                    });
                }

                LobReference.Builder lobRefBuilder = LobReference.newBuilder()
                        .setSession(this.sessionInfo)
                        .setUuid(this.lobUUID)
                        .setLobType(this.lobType)
                        .setBytesWritten(this.countBytesWritten.get());
                if (this.stmtUUID != null) {
                    lobRefBuilder.setStmtUUID(this.stmtUUID);
                }

                //Send the final Lob reference with total count of written bytes.
                responseObserver.onNext(lobRefBuilder.build());
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void readLob(ReadLobRequest request, StreamObserver<LobDataBlock> responseObserver) {
        org.openjproxy.grpc.server.action.streaming.ReadLobAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Builder
    public static class ReadLobContext {
        @Getter
        private InputStream inputStream;
        @Getter
        private Optional<Long> lobLength;
        @Getter
        private Optional<Integer> availableLength;
    }

    @Override
    public void terminateSession(SessionInfo sessionInfo, StreamObserver<SessionTerminationStatus> responseObserver) {
        try {
            log.info("Terminating session");

            // Before terminating, return any completed XA backend sessions to pool
            // This implements the dual-condition lifecycle: sessions are returned when
            // both transaction is complete AND XAConnection is closed
            log.info("[XA-TERMINATE] terminateSession called for sessionUUID={}, isXA={}, connHash={}",
                    sessionInfo.getSessionUUID(), sessionInfo.getIsXA(), sessionInfo.getConnHash());
            if (sessionInfo.getIsXA()) {
                String connHash = sessionInfo.getConnHash();
                XATransactionRegistry registry = xaRegistries.get(connHash);
                log.info("[XA-TERMINATE] Looking up XA registry for connHash={}, found={}", connHash, registry != null);
                if (registry != null) {
                    log.info("[XA-TERMINATE] Calling returnCompletedSessions for ojpSessionId={}", sessionInfo.getSessionUUID());
                    int returnedCount = registry.returnCompletedSessions(sessionInfo.getSessionUUID());
                    log.info("[XA-TERMINATE] returnCompletedSessions returned count={}", returnedCount);
                    if (returnedCount > 0) {
                        log.info("Returned {} completed XA backend sessions to pool on session termination", returnedCount);
                    }
                } else {
                    log.warn("[XA-TERMINATE] No XA registry found for connHash={}", connHash);
                }
            }

            log.info("[XA-TERMINATE] Calling sessionManager.terminateSession for sessionUUID={}", sessionInfo.getSessionUUID());
            this.sessionManager.terminateSession(sessionInfo);
            responseObserver.onNext(SessionTerminationStatus.newBuilder().setTerminated(true).build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to terminate session: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void startTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Starting transaction");

        // Process cluster health from the request
        processClusterHealth(sessionInfo);

        try {
            SessionInfo activeSessionInfo = sessionInfo;

            //Start a session if none started yet.
            if (StringUtils.isEmpty(sessionInfo.getSessionUUID())) {
                Connection conn = this.datasourceMap.get(sessionInfo.getConnHash()).getConnection();
                activeSessionInfo = sessionManager.createSession(sessionInfo.getClientUUID(), conn);
                // Preserve targetServer from incoming request
                activeSessionInfo = SessionInfoUtils.withTargetServer(activeSessionInfo, getTargetServer(sessionInfo));
            }
            Connection sessionConnection = sessionManager.getConnection(activeSessionInfo);
            //Start a transaction
            sessionConnection.setAutoCommit(Boolean.FALSE);

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(activeSessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);
            // Server echoes back targetServer from incoming request (preserved by newBuilderFrom)

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to start transaction: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void commitTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Commiting transaction");

        // Process cluster health from the request
        processClusterHealth(sessionInfo);

        try {
            Connection conn = sessionManager.getConnection(sessionInfo);
            conn.commit();

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_COMMITED)
                    .setTransactionUUID(sessionInfo.getTransactionInfo().getTransactionUUID())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(sessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);
            // Server echoes back targetServer from incoming request (preserved by newBuilderFrom)

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to commit transaction: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void rollbackTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Rollback transaction");

        // Process cluster health from the request
        processClusterHealth(sessionInfo);

        try {
            Connection conn = sessionManager.getConnection(sessionInfo);
            conn.rollback();

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ROLLBACK)
                    .setTransactionUUID(sessionInfo.getTransactionInfo().getTransactionUUID())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(sessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);
            // Server echoes back targetServer from incoming request (preserved by newBuilderFrom)

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to rollback transaction: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void callResource(CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) {
        // Process cluster health from the request
        processClusterHealth(request.getSession());

        try {
            if (!request.hasSession()) {
                throw new SQLException("No active session.");
            }

            CallResourceResponse.Builder responseBuilder = CallResourceResponse.newBuilder();

            if (this.db2SpecialResultSetMetadata(request, responseObserver)) {
                return;
            }

            Object resource;
            switch (request.getResourceType()) {
                case RES_RESULT_SET:
                    resource = sessionManager.getResultSet(request.getSession(), request.getResourceUUID());
                    break;
                case RES_LOB:
                    resource = sessionManager.getLob(request.getSession(), request.getResourceUUID());
                    break;
                case RES_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    Statement statement;
                    if (!request.getResourceUUID().isBlank()) {
                        statement = sessionManager.getStatement(csDto.getSession(), request.getResourceUUID());
                    } else {
                        statement = csDto.getConnection().createStatement();
                        String uuid = sessionManager.registerStatement(csDto.getSession(), statement);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = statement;
                    break;
                }
                case RES_PREPARED_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    PreparedStatement ps;
                    if (!request.getResourceUUID().isBlank()) {
                        ps = sessionManager.getPreparedStatement(request.getSession(), request.getResourceUUID());
                    } else {
                        Map<String, Object> mapProperties = EMPTY_MAP;
                        if (!request.getPropertiesList().isEmpty()) {
                            mapProperties = ProtoConverter.propertiesFromProto(request.getPropertiesList());
                        }
                        ps = csDto.getConnection().prepareStatement((String) mapProperties.get(CommonConstants.PREPARED_STATEMENT_SQL_KEY));
                        String uuid = sessionManager.registerPreparedStatement(csDto.getSession(), ps);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = ps;
                    break;
                }
                case RES_CALLABLE_STATEMENT:
                    resource = sessionManager.getCallableStatement(request.getSession(), request.getResourceUUID());
                    break;
                case RES_CONNECTION: {
                    ConnectionSessionDTO csDto = sessionConnection(request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    resource = csDto.getConnection();
                    break;
                }
                case RES_SAVEPOINT:
                    resource = sessionManager.getAttr(request.getSession(), request.getResourceUUID());
                    break;
                default:
                    throw new RuntimeException("Resource type invalid");
            }

            if (responseBuilder.getSession() == null || StringUtils.isBlank(responseBuilder.getSession().getSessionUUID())) {
                responseBuilder.setSession(request.getSession());
            }

            List<Object> paramsReceived = (request.getTarget().getParamsCount() > 0) ?
                    ProtoConverter.parameterValuesToObjectList(request.getTarget().getParamsList()) : EMPTY_LIST;
            Class<?> clazz = resource.getClass();
            if (!paramsReceived.isEmpty() &&
                    ((CallType.CALL_RELEASE.equals(request.getTarget().getCallType()) &&
                            "Savepoint".equalsIgnoreCase(request.getTarget().getResourceName())) ||
                            (CallType.CALL_ROLLBACK.equals(request.getTarget().getCallType()))
                    )
            ) {
                Savepoint savepoint = (Savepoint) this.sessionManager.getAttr(request.getSession(),
                        (String) paramsReceived.get(0));
                paramsReceived.set(0, savepoint);
            }
            Method method = MethodReflectionUtils.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazz),
                    MethodNameGenerator.methodName(request.getTarget()), paramsReceived);
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object resultFirstLevel;
            if (params != null && params.length > 0) {
                resultFirstLevel = method.invoke(resource, paramsReceived.toArray());
                if (resultFirstLevel instanceof CallableStatement cs) {
                    resultFirstLevel = this.sessionManager.registerCallableStatement(responseBuilder.getSession(), cs);
                }
            } else {
                resultFirstLevel = method.invoke(resource);
                if (resultFirstLevel instanceof ResultSet rs) {
                    resultFirstLevel = this.sessionManager.registerResultSet(responseBuilder.getSession(), rs);
                } else if (resultFirstLevel instanceof Array array) {
                    String arrayUUID = UUID.randomUUID().toString();
                    this.sessionManager.registerAttr(responseBuilder.getSession(), arrayUUID, array);
                    resultFirstLevel = arrayUUID;
                }
            }
            if (resultFirstLevel instanceof Savepoint sp) {
                String uuid = UUID.randomUUID().toString();
                resultFirstLevel = uuid;
                this.sessionManager.registerAttr(responseBuilder.getSession(), uuid, sp);
            }
            if (request.getTarget().hasNextCall()) {
                //Second level calls, for cases like getMetadata().isAutoIncrement(int column)
                Class<?> clazzNext = resultFirstLevel.getClass();
                List<Object> paramsReceived2 = (request.getTarget().getNextCall().getParamsCount() > 0) ?
                        ProtoConverter.parameterValuesToObjectList(request.getTarget().getNextCall().getParamsList()) :
                        EMPTY_LIST;
                Method methodNext = MethodReflectionUtils.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazzNext),
                        MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                        paramsReceived2);
                params = methodNext.getParameters();
                Object resultSecondLevel;
                if (params != null && params.length > 0) {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel, paramsReceived2.toArray());
                } else {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel);
                }
                if (resultSecondLevel instanceof ResultSet rs) {
                    resultSecondLevel = this.sessionManager.registerResultSet(responseBuilder.getSession(), rs);
                }
                responseBuilder.addValues(ProtoConverter.toParameterValue(resultSecondLevel));
            } else {
                responseBuilder.addValues(ProtoConverter.toParameterValue(resultFirstLevel));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SQLException sqlException) {
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getTargetException().getMessage()),
                        responseObserver);
            }
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getMessage(), e), responseObserver);
        }
    }

    /**
     * As DB2 eagerly closes result sets in multiple situations the result set metadata is saved a priori in a session
     * attribute and has to be read in a special manner treated in this method.
     *
     * @param request
     * @param responseObserver
     * @return boolean
     * @throws SQLException
     */
    @SneakyThrows
    private boolean db2SpecialResultSetMetadata(CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) throws SQLException {
        if (DbName.DB2.equals(this.dbNameMap.get(request.getSession().getConnHash())) &&
                ResourceType.RES_RESULT_SET.equals(request.getResourceType()) &&
                CallType.CALL_GET.equals(request.getTarget().getCallType()) &&
                "Metadata".equalsIgnoreCase(request.getTarget().getResourceName())) {
            ResultSetMetaData resultSetMetaData = (ResultSetMetaData) this.sessionManager.getAttr(request.getSession(),
                    RESULT_SET_METADATA_ATTR_PREFIX + request.getResourceUUID());
            List<Object> paramsReceived = (request.getTarget().getNextCall().getParamsCount() > 0) ?
                    ProtoConverter.parameterValuesToObjectList(request.getTarget().getNextCall().getParamsList()) :
                    EMPTY_LIST;
            Method methodNext = MethodReflectionUtils.findMethodByName(ResultSetMetaData.class,
                    MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                    paramsReceived);
            Object metadataResult = methodNext.invoke(resultSetMetaData, paramsReceived.toArray());
            responseObserver.onNext(CallResourceResponse.newBuilder()
                    .setSession(request.getSession())
                    .addValues(ProtoConverter.toParameterValue(metadataResult))
                    .build());
            responseObserver.onCompleted();
            return true;
        }
        return false;
    }

    /**
     * Finds a suitable connection for the current sessionInfo.
     * If there is a connection already in the sessionInfo reuse it, if not get a fresh one from the data source.
     * This method implements lazy connection allocation for both Hikari and Atomikos XA datasources.
     *
     * @param sessionInfo        - current sessionInfo object.
     * @param startSessionIfNone - if true will start a new sessionInfo if none exists.
     * @return ConnectionSessionDTO
     * @throws SQLException if connection not found or closed (by timeout or other reason)
     */
    private ConnectionSessionDTO sessionConnection(SessionInfo sessionInfo, boolean startSessionIfNone) throws SQLException {
        ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder = ConnectionSessionDTO.builder();
        dtoBuilder.session(sessionInfo);
        Connection conn;

        if (StringUtils.isNotEmpty(sessionInfo.getSessionUUID())) {
            // Session already exists, reuse its connection
            conn = this.sessionManager.getConnection(sessionInfo);
            if (conn == null) {
                throw new SQLException("Connection not found for this sessionInfo");
            }
            dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
            if (conn.isClosed()) {
                throw new SQLException("Connection is closed");
            }
        } else {
            // Lazy allocation: check if this is an XA or regular connection
            String connHash = sessionInfo.getConnHash();
            boolean isXA = sessionInfo.getIsXA();

            if (isXA) {
                // XA connection - check if unpooled or pooled mode
                XADataSource xaDataSource = this.xaDataSourceMap.get(connHash);

                if (xaDataSource != null) {
                    // Unpooled XA mode: create XAConnection on demand
                    try {
                        log.debug("Creating unpooled XAConnection for hash: {}", connHash);
                        XAConnection xaConnection = xaDataSource.getXAConnection();
                        conn = xaConnection.getConnection();

                        // Store the XAConnection in session for XA operations
                        if (startSessionIfNone) {
                            SessionInfo updatedSession = this.sessionManager.createSession(sessionInfo.getClientUUID(), conn);
                            // Store XAConnection as an attribute for XA operations
                            this.sessionManager.registerAttr(updatedSession, "xaConnection", xaConnection);
                            dtoBuilder.session(updatedSession);
                        }
                        log.debug("Successfully created unpooled XAConnection for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled XAConnection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled XA mode - should already have a session created in connect()
                    // This shouldn't happen as XA sessions are created eagerly
                    throw new SQLException("XA session should already exist. Session UUID is missing.");
                }
            } else {
                // Regular connection - check if pooled or unpooled mode
                UnpooledConnectionDetails unpooledDetails = this.unpooledConnectionDetailsMap.get(connHash);

                if (unpooledDetails != null) {
                    // Unpooled mode: create direct connection without pooling
                    try {
                        log.debug("Creating unpooled (passthrough) connection for hash: {}", connHash);
                        conn = java.sql.DriverManager.getConnection(
                                unpooledDetails.getUrl(),
                                unpooledDetails.getUsername(),
                                unpooledDetails.getPassword());
                        log.debug("Successfully created unpooled connection for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled connection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled mode: acquire from datasource (HikariCP by default)
                    DataSource dataSource = this.datasourceMap.get(connHash);
                    if (dataSource == null) {
                        throw new SQLException("No datasource found for connection hash: " + connHash);
                    }

                    try {
                        // Use enhanced connection acquisition with timeout protection
                        conn = ConnectionAcquisitionManager.acquireConnection(dataSource, connHash);
                        log.debug("Successfully acquired connection from pool for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to acquire connection from pool for hash: {}. Error: {}",
                                connHash, e.getMessage());

                        // Re-throw the enhanced exception from ConnectionAcquisitionManager
                        throw e;
                    }
                }

                if (startSessionIfNone) {
                    SessionInfo updatedSession = this.sessionManager.createSession(sessionInfo.getClientUUID(), conn);
                    dtoBuilder.session(updatedSession);
                }
            }
        }
        dtoBuilder.connection(conn);

        return dtoBuilder.build();
    }

    private void handleResultSet(SessionInfo session, String resultSetUUID, StreamObserver<OpResult> responseObserver)
            throws SQLException {
        ResultSet rs = this.sessionManager.getResultSet(session, resultSetUUID);
        OpQueryResult.OpQueryResultBuilder queryResultBuilder = OpQueryResult.builder();
        int columnCount = rs.getMetaData().getColumnCount();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            labels.add(rs.getMetaData().getColumnName(i + 1));
        }
        queryResultBuilder.labels(labels);

        List<Object[]> results = new ArrayList<>();
        int row = 0;
        boolean justSent = false;
        DbName dbName = DatabaseUtils.resolveDbName(rs.getStatement().getConnection().getMetaData().getURL());
        //Only used if result set contains LOBs in SQL Server and DB2 (if LOB's present), so cursor is not read in advance,
        // every row has to be requested by the jdbc client.
        String resultSetMode = "";
        boolean resultSetMetadataCollected = false;

        while (rs.next()) {
            if (DbName.DB2.equals(dbName) && !resultSetMetadataCollected) {
                this.collectResultSetMetadata(session, resultSetUUID, rs);
            }
            justSent = false;
            row++;
            Object[] rowValues = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                int colType = rs.getMetaData().getColumnType(i + 1);
                String colTypeName = rs.getMetaData().getColumnTypeName(i + 1);
                Object currentValue;
                //Postgres uses type BYTEA which translates to type VARBINARY
                switch (colType) {
                    case Types.VARBINARY: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        if ("BLOB".equalsIgnoreCase(colTypeName)) {
                            currentValue = LobProcessor.treatAsBlob(sessionManager, session, rs, i, dbNameMap);
                        } else {
                            currentValue = LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, i, INPUT_STREAM_TYPES);
                        }
                        break;
                    }
                    case Types.BLOB, Types.LONGVARBINARY: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBlob(sessionManager, session, rs, i, dbNameMap);
                        break;
                    }
                    case Types.CLOB: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        Clob clob = rs.getClob(i + 1);
                        if (clob == null) {
                            currentValue = null;
                        } else {
                            String clobUUID = UUID.randomUUID().toString();
                            //CLOB needs to be prefixed as per it can be read in the JDBC driver by getString method and it would be valid to return just a UUID as string
                            currentValue = CommonConstants.OJP_CLOB_PREFIX + clobUUID;
                            this.sessionManager.registerLob(session, clob, clobUUID);
                        }
                        break;
                    }
                    case Types.BINARY: {
                        if (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName)) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, i, INPUT_STREAM_TYPES);
                        break;
                    }
                    case Types.DATE: {
                        Date date = rs.getDate(i + 1);
                        if ("YEAR".equalsIgnoreCase(colTypeName)) {
                            currentValue = date.toLocalDate().getYear();
                        } else {
                            currentValue = date;
                        }
                        break;
                    }
                    case Types.TIMESTAMP: {
                        currentValue = rs.getTimestamp(i + 1);
                        break;
                    }
                    default: {
                        currentValue = rs.getObject(i + 1);
                        //com.microsoft.sqlserver.jdbc.DateTimeOffset special case as per it does not implement any standar java.sql interface.
                        if ("datetimeoffset".equalsIgnoreCase(colTypeName) && colType == -155) {
                            currentValue = DateTimeUtils.extractOffsetDateTime(currentValue);
                        }
                        break;
                    }
                }
                rowValues[i] = currentValue;

            }
            results.add(rowValues);

            if ((DbName.DB2.equals(dbName) || DbName.SQL_SERVER.equals(dbName))
                    && CommonConstants.RESULT_SET_ROW_BY_ROW_MODE.equalsIgnoreCase(resultSetMode)) {
                break;
            }

            if (row % CommonConstants.ROWS_PER_RESULT_SET_DATA_BLOCK == 0) {
                justSent = true;
                //Send a block of records
                responseObserver.onNext(ResultSetWrapper.wrapResults(session, results, queryResultBuilder, resultSetUUID, resultSetMode));
                queryResultBuilder = OpQueryResult.builder();// Recreate the builder to not send labels in every block.
                results = new ArrayList<>();
            }
        }

        if (!justSent) {
            //Send a block of remaining records
            responseObserver.onNext(ResultSetWrapper.wrapResults(session, results, queryResultBuilder, resultSetUUID, resultSetMode));
        }

        responseObserver.onCompleted();

    }

    @SneakyThrows
    private void collectResultSetMetadata(SessionInfo session, String resultSetUUID, ResultSet rs) {
        this.sessionManager.registerAttr(session, RESULT_SET_METADATA_ATTR_PREFIX +
                resultSetUUID, new HydratedResultSetMetadata(rs.getMetaData()));
    }

    // ===== XA Transaction Operations =====

    @Override
    public void xaStart(com.openjproxy.grpc.XaStartRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaStart: session={}, xid={}, flags={}",
                request.getSession().getSessionUUID(), request.getXid(), request.getFlags());

        // Process cluster health changes before XA operation
        processClusterHealth(request.getSession());

        Session session;

        try {
            session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }

            // Branch based on XA pooling configuration
            if (xaPoolProvider != null) {
                // **NEW PATH: Use XATransactionRegistry**
                handleXAStartWithPooling(request, session, responseObserver);
            } else {
                // **OLD PATH: Pass-through (legacy)**
                handleXAStartPassThrough(request, session, responseObserver);
            }

        } catch (Exception e) {
            log.error("Error in xaStart", e);

            // Provide additional context for Oracle XA errors
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("ORA-") && (errorMsg.contains("ORA-6550") || errorMsg.contains("ORA-24756") || errorMsg.contains("ORA-24757"))) {
                log.error("Oracle XA Error: The database user may not have required XA privileges. " +
                        "All of the following must be granted: " +
                        "GRANT SELECT ON sys.dba_pending_transactions TO user; " +
                        "GRANT SELECT ON sys.pending_trans$ TO user; " +
                        "GRANT SELECT ON sys.dba_2pc_pending TO user; " +
                        "GRANT EXECUTE ON sys.dbms_system TO user; " +
                        "GRANT FORCE ANY TRANSACTION TO user; " +
                        "Or for Oracle 12c+: GRANT XA_RECOVER_ADMIN TO user; " +
                        "See ojp-server/ORACLE_XA_SETUP.md for details.");
            }


            SQLException sqlException = e instanceof SQLException ex ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

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
        String ojpSessionId = session.getSessionInfo().getSessionUUID();

        // Route based on XA flags
        if (flags == javax.transaction.xa.XAResource.TMNOFLAGS) {
            // New transaction: use existing session from OJP Session
            XABackendSession backendSession = (XABackendSession) session.getBackendSession();
            if (backendSession == null) {
                throw new SQLException("No XABackendSession found in session");
            }
            registry.registerExistingSession(xidKey, backendSession, flags, ojpSessionId);

        } else if (flags == javax.transaction.xa.XAResource.TMJOIN ||
                flags == javax.transaction.xa.XAResource.TMRESUME) {
            // Join or resume existing transaction: delegate to xaStart
            // This requires the context to exist (from previous TMNOFLAGS start)
            // Note: ojpSessionId is only used for TMNOFLAGS, but we pass it anyway for consistency
            registry.xaStart(xidKey, flags, ojpSessionId);

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

    private void handleXAStartPassThrough(com.openjproxy.grpc.XaStartRequest request, Session session,
                                          StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) throws Exception {
        if (session.getXaResource() == null) {
            throw new SQLException("Session does not have XAResource");
        }

        javax.transaction.xa.Xid xid = convertXid(request.getXid());
        session.getXaResource().start(xid, request.getFlags());

        com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                .setSession(session.getSessionInfo())
                .setSuccess(true)
                .setMessage("XA start successful")
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void xaEnd(com.openjproxy.grpc.XaEndRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaEnd: session={}, xid={}, flags={}",
                request.getSession().getSessionUUID(), request.getXid(), request.getFlags());

        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }

            // Branch based on XA pooling configuration
            if (xaPoolProvider != null) {
                // **NEW PATH: Use XATransactionRegistry**
                String connHash = session.getSessionInfo().getConnHash();
                XATransactionRegistry registry = xaRegistries.get(connHash);
                if (registry == null) {
                    throw new SQLException("No XA registry found for connection hash: " + connHash);
                }

                XidKey xidKey = XidKey.from(convertXid(request.getXid()));
                registry.xaEnd(xidKey, request.getFlags());
            } else {
                // **OLD PATH: Pass-through (legacy)**
                if (session.getXaResource() == null) {
                    throw new SQLException("Session does not have XAResource");
                }
                javax.transaction.xa.Xid xid = convertXid(request.getXid());
                session.getXaResource().end(xid, request.getFlags());
            }

            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA end successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaEnd", e);
            SQLException sqlException = e instanceof SQLException ex ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaPrepare(com.openjproxy.grpc.XaPrepareRequest request, StreamObserver<com.openjproxy.grpc.XaPrepareResponse> responseObserver) {
        log.debug("xaPrepare: session={}, xid={}",
                request.getSession().getSessionUUID(), request.getXid());

        // Process cluster health changes before XA operation
        processClusterHealth(request.getSession());

        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }

            int result;

            // Branch based on XA pooling configuration
            if (xaPoolProvider != null) {
                // **NEW PATH: Use XATransactionRegistry**
                String connHash = session.getSessionInfo().getConnHash();
                XATransactionRegistry registry = xaRegistries.get(connHash);
                if (registry == null) {
                    throw new SQLException("No XA registry found for connection hash: " + connHash);
                }

                XidKey xidKey = XidKey.from(convertXid(request.getXid()));
                result = registry.xaPrepare(xidKey);
            } else {
                // **OLD PATH: Pass-through (legacy)**
                if (session.getXaResource() == null) {
                    throw new SQLException("Session does not have XAResource");
                }
                javax.transaction.xa.Xid xid = convertXid(request.getXid());
                result = session.getXaResource().prepare(xid);
            }

            com.openjproxy.grpc.XaPrepareResponse response = com.openjproxy.grpc.XaPrepareResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setResult(result)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaPrepare", e);
            SQLException sqlException = e instanceof SQLException ex ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaCommit(com.openjproxy.grpc.XaCommitRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaCommit: session={}, xid={}, onePhase={}",
                request.getSession().getSessionUUID(), request.getXid(), request.getOnePhase());

        // Process cluster health changes before XA operation
        processClusterHealth(request.getSession());

        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }

            // Branch based on XA pooling configuration
            if (xaPoolProvider != null) {
                // **NEW PATH: Use XATransactionRegistry**
                String connHash = session.getSessionInfo().getConnHash();
                XATransactionRegistry registry = xaRegistries.get(connHash);
                if (registry == null) {
                    throw new SQLException("No XA registry found for connection hash: " + connHash);
                }

                XidKey xidKey = XidKey.from(convertXid(request.getXid()));
                registry.xaCommit(xidKey, request.getOnePhase());

                // NOTE: Do NOT unbind XAConnection here - it stays bound for session lifetime
                // XABackendSession will be returned to pool when OJP Session terminates
            } else {
                // **OLD PATH: Pass-through (legacy)**
                if (session.getXaResource() == null) {
                    throw new SQLException("Session does not have XAResource");
                }
                javax.transaction.xa.Xid xid = convertXid(request.getXid());
                session.getXaResource().commit(xid, request.getOnePhase());
            }

            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA commit successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaCommit", e);
            SQLException sqlException = e instanceof SQLException ex ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaRollback(com.openjproxy.grpc.XaRollbackRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaRollback: session={}, xid={}",
                request.getSession().getSessionUUID(), request.getXid());

        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }

            // Branch based on XA pooling configuration
            if (xaPoolProvider != null) {
                // **NEW PATH: Use XATransactionRegistry**
                String connHash = session.getSessionInfo().getConnHash();
                XATransactionRegistry registry = xaRegistries.get(connHash);
                if (registry == null) {
                    throw new SQLException("No XA registry found for connection hash: " + connHash);
                }

                XidKey xidKey = XidKey.from(convertXid(request.getXid()));
                registry.xaRollback(xidKey);

                // NOTE: Do NOT unbind XAConnection here - it stays bound for session lifetime
                // XABackendSession will be returned to pool when OJP Session terminates
            } else {
                // **OLD PATH: Pass-through (legacy)**
                if (session.getXaResource() == null) {
                    throw new SQLException("Session does not have XAResource");
                }
                javax.transaction.xa.Xid xid = convertXid(request.getXid());
                session.getXaResource().rollback(xid);
            }

            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA rollback successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaRollback", e);
            SQLException sqlException = e instanceof SQLException ex ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaRecover(com.openjproxy.grpc.XaRecoverRequest request, StreamObserver<com.openjproxy.grpc.XaRecoverResponse> responseObserver) {
        log.debug("xaRecover: session={}, flag={}",
                request.getSession().getSessionUUID(), request.getFlag());

        try {
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }

            javax.transaction.xa.Xid[] xids = session.getXaResource().recover(request.getFlag());

            com.openjproxy.grpc.XaRecoverResponse.Builder responseBuilder = com.openjproxy.grpc.XaRecoverResponse.newBuilder()
                    .setSession(session.getSessionInfo());

            for (javax.transaction.xa.Xid xid : xids) {
                responseBuilder.addXids(convertXidToProto(xid));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaRecover", e);
            SQLException sqlException = (e instanceof SQLException ex) ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    @Override
    public void xaForget(com.openjproxy.grpc.XaForgetRequest request, StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaForgetAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaSetTransactionTimeout(com.openjproxy.grpc.XaSetTransactionTimeoutRequest request,
                                        StreamObserver<com.openjproxy.grpc.XaSetTransactionTimeoutResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaSetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaGetTransactionTimeout(com.openjproxy.grpc.XaGetTransactionTimeoutRequest request,
                                        StreamObserver<com.openjproxy.grpc.XaGetTransactionTimeoutResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaGetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);

    }

    @Override
    public void xaIsSameRM(com.openjproxy.grpc.XaIsSameRMRequest request,
                           StreamObserver<com.openjproxy.grpc.XaIsSameRMResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaIsSameRMAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }
}
