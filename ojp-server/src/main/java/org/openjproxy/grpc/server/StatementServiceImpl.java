package org.openjproxy.grpc.server;

import com.openjproxy.grpc.*;
import com.zaxxer.hikari.HikariDataSource;
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
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.connection.ConnectAction;
import org.openjproxy.grpc.server.action.streaming.CreateLobAction;
import org.openjproxy.grpc.server.action.streaming.ReadLobAction;
import org.openjproxy.grpc.server.action.transaction.*;
import org.openjproxy.grpc.server.lob.LobProcessor;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.resultset.ResultSetWrapper;
import org.openjproxy.grpc.server.sql.SqlEnhancementResult;
import org.openjproxy.grpc.server.sql.SqlEnhancerEngine;
import org.openjproxy.grpc.server.statement.ParameterHandler;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.utils.DateTimeUtils;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;
import org.openjproxy.grpc.server.utils.StatementRequestValidator;
import org.openjproxy.grpc.server.sql.SqlSessionAffinityDetector;
import org.openjproxy.grpc.server.action.xa.XaStartAction;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.InputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

import org.openjproxy.grpc.server.action.xa.XaEndAction;
import org.openjproxy.grpc.server.action.session.TerminateSessionAction;
import org.openjproxy.grpc.server.action.resource.CallResourceAction;
import org.openjproxy.grpc.server.action.xa.XaPrepareAction;
import org.openjproxy.grpc.server.action.xa.XaCommitAction;
import org.openjproxy.grpc.server.action.xa.XaRollbackAction;
import org.openjproxy.grpc.server.action.xa.XaRecoverAction;

@Slf4j
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {

    private final Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
    // Map for storing XADataSources (native database XADataSource, not Atomikos)
    private final Map<String, XADataSource> xaDataSourceMap = new ConcurrentHashMap<>();
    // XA Pool Provider for pooling XAConnections (loaded via SPI)
    private XAConnectionPoolProvider xaPoolProvider;
    // XA Transaction Registries (one per connection hash for isolated transaction
    // management)
    private final Map<String, XATransactionRegistry> xaRegistries = new ConcurrentHashMap<>();
    private final SessionManager sessionManager;
    private final CircuitBreaker circuitBreaker;

    // Per-datasource slow query segregation managers
    private final Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers = new ConcurrentHashMap<>();

    // SQL Enhancer Engine for query optimization
    private final SqlEnhancerEngine sqlEnhancerEngine;

    // Multinode XA coordinator for distributing transaction limits
    private static final MultinodeXaCoordinator xaCoordinator = new MultinodeXaCoordinator();

    // Cluster health tracker for monitoring health changes
    private final ClusterHealthTracker clusterHealthTracker = new ClusterHealthTracker();

    // Unpooled connection details map (for passthrough mode when pooling is
    // disabled)
    private final Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap = new ConcurrentHashMap<>();

    private static final List<String> INPUT_STREAM_TYPES = Arrays.asList("RAW", "BINARY VARYING", "BYTEA");
    private final Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();

    private static final String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";

    // ActionContext for refactored actions
    private final ActionContext actionContext;

    public StatementServiceImpl(SessionManager sessionManager, CircuitBreaker circuitBreaker,
            ServerConfiguration serverConfiguration) {
        this.sessionManager = sessionManager;
        this.circuitBreaker = circuitBreaker;
        // Server configuration for creating segregation managers
        this.sqlEnhancerEngine = new SqlEnhancerEngine(
                serverConfiguration.isSqlEnhancerEnabled());
        initializeXAPoolProvider();

        // Initialize ActionContext with all shared state
        this.actionContext = new ActionContext(
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
                serverConfiguration);
    }

    /**
     * Updates the last activity time for the session to prevent premature cleanup.
     * This should be called at the beginning of any method that operates on a session.
     *
     * @param sessionInfo the session information
     */
    private void updateSessionActivity(SessionInfo sessionInfo) {
        if (sessionInfo != null && !sessionInfo.getSessionUUID().isEmpty()) {
            sessionManager.updateSessionActivity(sessionInfo);
        }
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

                // Update ActionContext with initialized provider (if actionContext is already
                // created)
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
        // Echo back the targetServer from incoming request, or return empty string if
        // not present
        if (incomingSessionInfo != null && !incomingSessionInfo.getTargetServer().isEmpty()) {
            return incomingSessionInfo.getTargetServer();
        }

        // Return empty string if client didn't send targetServer
        return "";
    }

    /**
     * Processes cluster health from the client request and triggers pool
     * rebalancing if needed.
     * This should be called for every request that includes SessionInfo with
     * cluster health.
     */
    private void processClusterHealth(SessionInfo sessionInfo) {
        if (sessionInfo == null) {
            log.debug("[XA-REBALANCE-DEBUG] processClusterHealth: sessionInfo is null");
            return;
        }

        String clusterHealth = sessionInfo.getClusterHealth();
        String connHash = sessionInfo.getConnHash();

        log.debug(
                "[XA-REBALANCE] processClusterHealth called: connHash={}, clusterHealth='{}', isXA={}, hasXARegistry={}",
                connHash, clusterHealth, sessionInfo.getIsXA(), xaRegistries.containsKey(connHash));

        if (clusterHealth.isEmpty() || connHash.isEmpty()) {
            log.info("[XA-REBALANCE-DEBUG] Skipping cluster health processing: clusterHealth={}, connHash={}",
                    clusterHealth.isEmpty() ? "empty" : "present",
                    connHash.isEmpty() ? "empty" : "present");
            return;
        }

        // Check if cluster health has changed
        boolean healthChanged = clusterHealthTracker.hasHealthChanged(connHash, clusterHealth);

        log.debug("[XA-REBALANCE] Cluster health check for {}: changed={}, current health='{}', isXA={}",
                connHash, healthChanged, clusterHealth, sessionInfo.getIsXA());

        if (healthChanged) {
            handleHealthChange(connHash, clusterHealth, sessionInfo);
        } else {
            log.debug("[XA-REBALANCE-DEBUG] Cluster health unchanged for {}", connHash);
        }
    }

    /**
     * Handles cluster health change by updating pool coordinator and applying
     * pool size changes to both HikariDataSource and XA registry.
     */
    private void handleHealthChange(String connHash, String clusterHealth, SessionInfo sessionInfo) {
        int healthyServerCount = clusterHealthTracker.countHealthyServers(clusterHealth);
        log.info(
                "[XA-REBALANCE] Cluster health changed for {}, healthy servers: {}, triggering pool rebalancing, isXA={}",
                connHash, healthyServerCount, sessionInfo.getIsXA());

        // Update the pool coordinator with new healthy server count
        ConnectionPoolConfigurer.getPoolCoordinator().updateHealthyServers(connHash, healthyServerCount);

        // Apply pool size changes to both HikariDataSource and XA registry
        applyHikariPoolSizeChanges(connHash);
        applyXARegistryPoolSizeChanges(connHash, sessionInfo);
    }

    /**
     * Applies pool size changes to HikariDataSource if present.
     */
    private void applyHikariPoolSizeChanges(String connHash) {
        DataSource ds = datasourceMap.get(connHash);
        if (ds instanceof HikariDataSource hikariDataSource) {
            log.info("[XA-REBALANCE-DEBUG] Applying size changes to HikariDataSource for {}", connHash);
            ConnectionPoolConfigurer.applyPoolSizeChanges(connHash, hikariDataSource);
        } else {
            log.info("[XA-REBALANCE-DEBUG] No HikariDataSource found for {}", connHash);
        }
    }

    /**
     * Applies pool size changes to XA registry if present.
     */
    private void applyXARegistryPoolSizeChanges(String connHash, SessionInfo sessionInfo) {
        XATransactionRegistry xaRegistry = xaRegistries.get(connHash);
        if (xaRegistry != null) {
            log.info("[XA-REBALANCE-DEBUG] Found XA registry for {}, resizing", connHash);
            MultinodePoolCoordinator.PoolAllocation allocation = ConnectionPoolConfigurer.getPoolCoordinator()
                    .getPoolAllocation(connHash);

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
    }

    @Override
    public void connect(ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
        ConnectAction.getInstance()
                .execute(actionContext, connectionDetails, responseObserver);
    }

    /**
     * Gets the slow query segregation manager for a specific connection hash.
     * If no manager exists, creates a disabled one as a fallback.
     */
    private SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(String connHash) {
        SlowQuerySegregationManager manager = slowQuerySegregationManagers.get(connHash);
        if (manager == null) {
            log.warn("No SlowQuerySegregationManager found for connection hash {}, creating disabled fallback",
                    connHash);
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
        
        // Update session activity
        updateSessionActivity(request.getSession());
        
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        processClusterHealth(request.getSession());

        try {
            circuitBreaker.preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);

            // Execute with slow query segregation
            OpResult result = manager.executeWithSegregation(stmtHash, () -> executeUpdateInternal(request));

            responseObserver.onNext(result);
            responseObserver.onCompleted();
            circuitBreaker.onSuccess(stmtHash);

        } catch (SQLDataException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during update execution: {}", e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver, SqlErrorType.SQL_DATA_EXCEPTION);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during update execution: {}", e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during update execution: {}", e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
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
        Statement stmt = null;
        ConnectionSessionDTO dto = ConnectionSessionDTO.builder().build();

        try {
            dto = initializeSessionConnection(request);
            SessionInfo returnSessionInfo = dto.getSession();

            List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
            
            if (shouldUsePreparedStatement(params, request, dto)) {
                UpdateExecutionResult result = executePreparedStatementUpdate(request, dto, params);
                stmt = result.getStatement();
                return buildUpdateResult(request, result, returnSessionInfo);
            } else {
                UpdateExecutionResult result = executeRegularStatementUpdate(request, dto);
                stmt = result.getStatement();
                return buildUpdateResult(request, result, returnSessionInfo);
            }
        } finally {
            cleanupResources(dto, stmt);
        }
    }

    /**
     * Initializes the session connection for the update operation.
     */
    private ConnectionSessionDTO initializeSessionConnection(StatementRequest request) throws SQLException {
        boolean requiresSessionAffinity = SqlSessionAffinityDetector.requiresSessionAffinity(request.getSql());
        boolean needsSession = StatementRequestValidator.isAddBatchOperation(request)
                || StatementRequestValidator.hasAutoGeneratedKeysFlag(request)
                || requiresSessionAffinity;
        return sessionConnection(request.getSession(), needsSession);
    }

    /**
     * Determines if a prepared statement should be used based on parameters and request.
     */
    private boolean shouldUsePreparedStatement(List<Parameter> params, StatementRequest request, ConnectionSessionDTO dto) {
        if (CollectionUtils.isNotEmpty(params)) {
            return true;
        }
        return dto.getSession() != null 
                && StringUtils.isNotBlank(dto.getSession().getSessionUUID())
                && StringUtils.isNoneBlank(request.getStatementUUID());
    }

    /**
     * Handles LOB parameters for a prepared statement.
     */
    private void handleLobParameters(PreparedStatement ps, ConnectionSessionDTO dto, SessionInfo session) throws SQLException {
        if (ps == null) {
            return;
        }

        Collection<Object> lobs = sessionManager.getLobs(session);
        for (Object o : lobs) {
            LobDataBlocksInputStream lobIS = (LobDataBlocksInputStream) o;
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) sessionManager.getAttr(session, lobIS.getUuid());
            Integer parameterIndex = (Integer) metadata
                    .get(String.valueOf(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_INDEX));
            ps.setBinaryStream(parameterIndex, lobIS);
        }

        if (DbName.POSTGRES.equals(dto.getDbName())) {
            sessionManager.waitLobStreamsConsumption(session);
        }
    }

    /**
     * Gets an existing prepared statement or creates a new one.
     */
    private PreparedStatement getOrCreatePreparedStatement(StatementRequest request, ConnectionSessionDTO dto, 
            List<Parameter> params, OpResult.Builder opResultBuilder) throws SQLException {
        if (StringUtils.isNotEmpty(request.getStatementUUID())) {
            return sessionManager.getPreparedStatement(dto.getSession(), request.getStatementUUID());
        } else {
            PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, dto, request.getSql(), params, request);
            if (StatementRequestValidator.hasAutoGeneratedKeysFlag(request)) {
                String psNewUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                opResultBuilder.setUuid(psNewUUID);
            }
            return ps;
        }
    }

    /**
     * Executes an update using a prepared statement.
     */
    private UpdateExecutionResult executePreparedStatementUpdate(StatementRequest request, ConnectionSessionDTO dto, 
            List<Parameter> params) throws SQLException {
        OpResult.Builder opResultBuilder = OpResult.newBuilder();
        PreparedStatement ps = getOrCreatePreparedStatement(request, dto, params, opResultBuilder);

        if (StringUtils.isNotEmpty(request.getStatementUUID())) {
            handleLobParameters(ps, dto, dto.getSession());
            if (ps != null) {
                ParameterHandler.addParametersPreparedStatement(sessionManager, dto.getSession(), ps, params);
            }
        }

        String psUUID = "";
        int updated = 0;

        if (StatementRequestValidator.isAddBatchOperation(request)) {
            if (ps != null) {
                ps.addBatch();
            }
            psUUID = request.getStatementUUID().isBlank() 
                    ? sessionManager.registerPreparedStatement(dto.getSession(), ps)
                    : request.getStatementUUID();
        } else {
            updated = ps != null ? ps.executeUpdate() : 0;
        }

        return UpdateExecutionResult.builder()
                .statement(ps)
                .updated(updated)
                .psUUID(psUUID)
                .opResultBuilder(opResultBuilder)
                .build();
    }

    /**
     * Executes an update using a regular statement.
     */
    private UpdateExecutionResult executeRegularStatementUpdate(StatementRequest request, ConnectionSessionDTO dto) 
            throws SQLException {
        Statement stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
        int updated = stmt.executeUpdate(request.getSql());
        
        return UpdateExecutionResult.builder()
                .statement(stmt)
                .updated(updated)
                .psUUID("")
                .opResultBuilder(OpResult.newBuilder())
                .build();
    }

    /**
     * Builds the operation result from the execution result.
     */
    private OpResult buildUpdateResult(StatementRequest request, UpdateExecutionResult result, SessionInfo returnSessionInfo) {
        OpResult.Builder builder = result.getOpResultBuilder();
        
        if (StatementRequestValidator.isAddBatchOperation(request)) {
            return builder
                    .setType(ResultType.UUID_STRING)
                    .setSession(returnSessionInfo)
                    .setUuidValue(result.getPsUUID())
                    .build();
        }
        
        return builder
                .setType(ResultType.INTEGER)
                .setSession(returnSessionInfo)
                .setIntValue(result.getUpdated())
                .build();
    }

    /**
     * Cleans up resources (statement and connection) if no session exists.
     */
    private void cleanupResources(ConnectionSessionDTO dto, Statement stmt) {
        if (stmt == null) {
            return;
        }
        
        boolean shouldCleanup = dto.getSession() == null 
                || StringUtils.isEmpty(dto.getSession().getSessionUUID());
        
        if (!shouldCleanup) {
            return;
        }

        try {
            stmt.close();
        } catch (SQLException e) {
            log.error("Failure closing statement: {}", e.getMessage(), e);
        }
        
        try {
            stmt.getConnection().close();
        } catch (SQLException e) {
            log.error("Failure closing connection: {}", e.getMessage(), e);
        }
    }

    /**
     * Result holder for update execution.
     */
    @Builder
    @Getter
    private static class UpdateExecutionResult {
        private Statement statement;
        private int updated;
        private String psUUID;
        private OpResult.Builder opResultBuilder;
    }

    @Override
    public void executeQuery(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());
        
        // Update session activity
        updateSessionActivity(request.getSession());
        
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
            log.error("Failure during query execution: {}", e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during query execution: {}", e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
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
    private void executeQueryInternal(StatementRequest request, StreamObserver<OpResult> responseObserver)
            throws SQLException {
        ConnectionSessionDTO dto = this.sessionConnection(request.getSession(), true);

        // Phase 2: SQL Enhancement with timing
        String sql = request.getSql();
        long enhancementStartTime = System.currentTimeMillis();

        if (sqlEnhancerEngine.isEnabled()) {
            SqlEnhancementResult result = sqlEnhancerEngine.enhance(sql);
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
        String resultSetUUID;

        if (CollectionUtils.isNotEmpty(params)) {

            try (PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, dto, sql, params, request)) {
                resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
                this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
            }
        } else {
            Statement stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
            resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(),
                    stmt.executeQuery(sql));
            this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
        }
    }

    @Override
    public void fetchNextRows(ResultSetFetchRequest request, StreamObserver<OpResult> responseObserver) {
        log.debug("Executing fetch next rows for result set  {}", request.getResultSetUUID());

        // Update session activity
        updateSessionActivity(request.getSession());

        // Process cluster health from the request
        processClusterHealth(request.getSession());

        try {
            ConnectionSessionDTO dto = this.sessionConnection(request.getSession(), false);
            this.handleResultSet(dto.getSession(), request.getResultSetUUID(), responseObserver);
        } catch (SQLException e) {
            log.error("Failure fetch next rows for result set: {}", e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        }
    }

    @Override
    public StreamObserver<LobDataBlock> createLob(StreamObserver<LobReference> responseObserver) {
        return CreateLobAction.getInstance()
                .execute(actionContext, responseObserver);
    }

    @Override
    public void readLob(ReadLobRequest request, StreamObserver<LobDataBlock> responseObserver) {
        ReadLobAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Builder
    public static class ReadLobContext {
        @Getter
        private InputStream inputStream;
        @Getter
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private Optional<Long> lobLength;
        @Getter
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private Optional<Integer> availableLength;
    }

    @Override
    public void terminateSession(SessionInfo sessionInfo, StreamObserver<SessionTerminationStatus> responseObserver) {
        TerminateSessionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void startTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Starting transaction");

        // Process cluster health from the request
        processClusterHealth(sessionInfo);

        try {
            SessionInfo activeSessionInfo = sessionInfo;

            // Start a session if none started yet.
            if (StringUtils.isEmpty(sessionInfo.getSessionUUID())) {
                Connection conn = this.datasourceMap.get(sessionInfo.getConnHash()).getConnection();
                activeSessionInfo = sessionManager.createSession(sessionInfo.getClientUUID(), conn);
                // Preserve targetServer from incoming request
                activeSessionInfo = SessionInfoUtils.withTargetServer(activeSessionInfo, getTargetServer(sessionInfo));
            }
            Connection sessionConnection = sessionManager.getConnection(activeSessionInfo);
            // Start a transaction
            sessionConnection.setAutoCommit(Boolean.FALSE);

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(activeSessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);
            // Server echoes back targetServer from incoming request (preserved by
            // newBuilderFrom)

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to start transaction: " + e.getMessage()),
                    responseObserver);
        }
    }

    @Override
    public void commitTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        CommitTransactionAction.getInstance().execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void rollbackTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        RollbackTransactionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void callResource(CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) {
        CallResourceAction.getInstance().execute(actionContext, request, responseObserver);
    }

    /**
     * Reuses an existing session connection if available.
     * Validates the connection and sets the database name on the DTO builder.
     *
     * @param sessionInfo the session information
     * @param dtoBuilder   the DTO builder to update
     * @return the connection from the existing session
     * @throws SQLException if connection not found or closed
     */
    private Connection reuseExistingSessionConnection(SessionInfo sessionInfo,
            ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder) throws SQLException {
        Connection conn = this.sessionManager.getConnection(sessionInfo);
        if (conn == null) {
            throw new SQLException("Connection not found for this sessionInfo");
        }
        dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
        if (conn.isClosed()) {
            throw new SQLException("Connection is closed");
        }
        return conn;
    }

    /**
     * Creates an unpooled XA connection from the XADataSource.
     * Registers the XAConnection as a session attribute for XA operations.
     *
     * @param sessionInfo     the session information
     * @param connHash        the connection hash
     * @param xaDataSource    the XA data source
     * @param startSessionIfNone if true, creates a session if none exists
     * @param dtoBuilder      the DTO builder to update
     * @return the connection from the XA connection
     * @throws SQLException if connection creation fails
     */
    private Connection createUnpooledXaConnection(SessionInfo sessionInfo, String connHash,
            XADataSource xaDataSource, boolean startSessionIfNone,
            ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder) throws SQLException {
        try {
            log.debug("Creating unpooled XAConnection for hash: {}", connHash);
            XAConnection xaConnection = xaDataSource.getXAConnection();
            Connection conn = xaConnection.getConnection();

            // Store the XAConnection in session for XA operations
            if (startSessionIfNone) {
                SessionInfo updatedSession = this.sessionManager.createSession(sessionInfo.getClientUUID(), conn);
                // Store XAConnection as an attribute for XA operations
                this.sessionManager.registerAttr(updatedSession, "xaConnection", xaConnection);
                dtoBuilder.session(updatedSession);
            }
            log.debug("Successfully created unpooled XAConnection for hash: {}", connHash);
            return conn;
        } catch (SQLException e) {
            log.error("Failed to create unpooled XAConnection for hash: {}. Error: {}", connHash, e.getMessage());
            throw e;
        }
    }

    /**
     * Creates an XA connection based on the connection hash.
     * Handles both unpooled and pooled XA modes.
     *
     * @param sessionInfo        the session information
     * @param connHash           the connection hash
     * @param startSessionIfNone if true, creates a session if none exists
     * @param dtoBuilder         the DTO builder to update
     * @return the XA connection
     * @throws SQLException if connection creation fails or session is missing
     */
    private Connection createXaConnection(SessionInfo sessionInfo, String connHash, boolean startSessionIfNone,
            ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder) throws SQLException {
        XADataSource xaDataSource = this.xaDataSourceMap.get(connHash);

        if (xaDataSource != null) {
            // Unpooled XA mode: create XAConnection on demand
            return createUnpooledXaConnection(sessionInfo, connHash, xaDataSource, startSessionIfNone, dtoBuilder);
        } else {
            // Pooled XA mode - should already have a session created in connect()
            // This shouldn't happen as XA sessions are created eagerly
            throw new SQLException("XA session should already exist. Session UUID is missing.");
        }
    }

    /**
     * Creates an unpooled regular connection via DriverManager.
     *
     * @param connHash the connection hash
     * @return the unpooled connection
     * @throws SQLException if connection creation fails
     */
    private Connection createUnpooledRegularConnection(String connHash) throws SQLException {
        UnpooledConnectionDetails unpooledDetails = this.unpooledConnectionDetailsMap.get(connHash);
        if (unpooledDetails == null) {
            throw new SQLException("Unpooled connection details not found for hash: " + connHash);
        }

        try {
            log.debug("Creating unpooled (passthrough) connection for hash: {}", connHash);
            Connection conn = DriverManager.getConnection(
                    unpooledDetails.getUrl(),
                    unpooledDetails.getUsername(),
                    unpooledDetails.getPassword());
            log.debug("Successfully created unpooled connection for hash: {}", connHash);
            return conn;
        } catch (SQLException e) {
            log.error("Failed to create unpooled connection for hash: {}. Error: {}", connHash, e.getMessage());
            throw e;
        }
    }

    /**
     * Creates a pooled regular connection from the datasource.
     *
     * @param connHash the connection hash
     * @return the pooled connection
     * @throws SQLException if datasource not found or connection acquisition fails
     */
    private Connection createPooledRegularConnection(String connHash) throws SQLException {
        DataSource dataSource = this.datasourceMap.get(connHash);
        if (dataSource == null) {
            throw new SQLException("No datasource found for connection hash: " + connHash);
        }

        try {
            // Use enhanced connection acquisition with timeout protection
            Connection conn = ConnectionAcquisitionManager.acquireConnection(dataSource, connHash);
            log.debug("Successfully acquired connection from pool for hash: {}", connHash);
            return conn;
        } catch (SQLException e) {
            log.error("Failed to acquire connection from pool for hash: {}. Error: {}", connHash, e.getMessage());
            // Re-throw the enhanced exception from ConnectionAcquisitionManager
            throw e;
        }
    }

    /**
     * Creates a regular (non-XA) connection based on connection mode.
     * Handles both unpooled and pooled modes, and creates session if needed.
     *
     * @param sessionInfo        the session information
     * @param connHash           the connection hash
     * @param startSessionIfNone if true, creates a session if none exists
     * @param dtoBuilder         the DTO builder to update
     * @return the regular connection
     * @throws SQLException if connection creation fails
     */
    private Connection createRegularConnection(SessionInfo sessionInfo, String connHash, boolean startSessionIfNone,
            ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder) throws SQLException {
        UnpooledConnectionDetails unpooledDetails = this.unpooledConnectionDetailsMap.get(connHash);
        Connection conn;

        if (unpooledDetails != null) {
            // Unpooled mode: create direct connection without pooling
            conn = createUnpooledRegularConnection(connHash);
        } else {
            // Pooled mode: acquire from datasource (HikariCP by default)
            conn = createPooledRegularConnection(connHash);
        }

        if (startSessionIfNone) {
            SessionInfo updatedSession = this.sessionManager.createSession(sessionInfo.getClientUUID(), conn);
            dtoBuilder.session(updatedSession);
        }

        return conn;
    }

    /**
     * Finds a suitable connection for the current sessionInfo.
     * If there is a connection already in the sessionInfo reuse it, if not get a
     * fresh one from the data source.
     * This method implements lazy connection allocation for both Hikari and
     * Atomikos XA datasources.
     *
     * @param sessionInfo        - current sessionInfo object.
     * @param startSessionIfNone - if true will start a new sessionInfo if none
     *                           exists.
     * @return ConnectionSessionDTO
     * @throws SQLException if connection not found or closed (by timeout or other
     *                      reason)
     */
    private ConnectionSessionDTO sessionConnection(SessionInfo sessionInfo, boolean startSessionIfNone)
            throws SQLException {
        ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder = ConnectionSessionDTO.builder();
        dtoBuilder.session(sessionInfo);
        Connection conn;

        if (StringUtils.isNotEmpty(sessionInfo.getSessionUUID())) {
            // Session already exists, reuse its connection
            conn = reuseExistingSessionConnection(sessionInfo, dtoBuilder);
        } else {
            // Lazy allocation: check if this is an XA or regular connection
            String connHash = sessionInfo.getConnHash();
            boolean isXA = sessionInfo.getIsXA();

            if (isXA) {
                conn = createXaConnection(sessionInfo, connHash, startSessionIfNone, dtoBuilder);
            } else {
                conn = createRegularConnection(sessionInfo, connHash, startSessionIfNone, dtoBuilder);
            }
        }

        dtoBuilder.connection(conn);
        return dtoBuilder.build();
    }

    /**
     * Checks if row-by-row mode is required for the given database and column type.
     */
    private boolean requiresRowByRowMode(DbName dbName, int colType) {
        return (DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName))
                && (colType == Types.VARBINARY || colType == Types.BLOB 
                    || colType == Types.LONGVARBINARY || colType == Types.CLOB 
                    || colType == Types.BINARY);
    }

    /**
     * Checks if the loop should break early based on database and result set mode.
     */
    private boolean shouldBreakEarly(DbName dbName, String resultSetMode) {
        return (DbName.DB2.equals(dbName) || DbName.SQL_SERVER.equals(dbName))
                && CommonConstants.RESULT_SET_ROW_BY_ROW_MODE.equalsIgnoreCase(resultSetMode);
    }

    /**
     * Checks if a result block should be sent based on row count.
     */
    private boolean shouldSendBlock(int row) {
        return row % CommonConstants.ROWS_PER_RESULT_SET_DATA_BLOCK == 0;
    }

    /**
     * Processes a VARBINARY column value.
     */
    private Object processVarbinaryColumn(ResultSet rs, int columnIndex, DbName dbName, 
                                         SessionInfo session, String colTypeName, String[] resultSetMode) throws SQLException {
        if (requiresRowByRowMode(dbName, Types.VARBINARY)) {
            resultSetMode[0] = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
        }
        if ("BLOB".equalsIgnoreCase(colTypeName)) {
            return LobProcessor.treatAsBlob(sessionManager, session, rs, columnIndex, dbNameMap);
        } else {
            return LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, columnIndex, INPUT_STREAM_TYPES);
        }
    }

    /**
     * Processes a BLOB or LONGVARBINARY column value.
     */
    private Object processBlobColumn(ResultSet rs, int columnIndex, DbName dbName, 
                                     SessionInfo session, int colType, String[] resultSetMode) throws SQLException {
        if (requiresRowByRowMode(dbName, colType)) {
            resultSetMode[0] = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
        }
        return LobProcessor.treatAsBlob(sessionManager, session, rs, columnIndex, dbNameMap);
    }

    /**
     * Processes a CLOB column value.
     */
    private Object processClobColumn(ResultSet rs, int columnIndex, DbName dbName, 
                                    SessionInfo session, String[] resultSetMode) throws SQLException {
        if (requiresRowByRowMode(dbName, Types.CLOB)) {
            resultSetMode[0] = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
        }
        Clob clob = rs.getClob(columnIndex + 1);
        if (clob != null) {
            String clobUUID = UUID.randomUUID().toString();
            // CLOB needs to be prefixed as per it can be read in the JDBC driver by
            // getString method, and it would be valid to return just a UUID as string
            this.sessionManager.registerLob(session, clob, clobUUID);
            return CommonConstants.OJP_CLOB_PREFIX + clobUUID;
        }
        return null;
    }

    /**
     * Processes a BINARY column value.
     */
    private Object processBinaryColumn(ResultSet rs, int columnIndex, DbName dbName, 
                                      SessionInfo session, String[] resultSetMode) throws SQLException {
        if (requiresRowByRowMode(dbName, Types.BINARY)) {
            resultSetMode[0] = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
        }
        return LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, columnIndex, INPUT_STREAM_TYPES);
    }

    /**
     * Processes a DATE column value.
     */
    private Object processDateColumn(ResultSet rs, int columnIndex, String colTypeName) throws SQLException {
        Date date = rs.getDate(columnIndex + 1);
        if ("YEAR".equalsIgnoreCase(colTypeName)) {
            return date.toLocalDate().getYear();
        }
        return date;
    }

    /**
     * Processes a default column value (handles special cases like DateTimeOffset).
     */
    private Object processDefaultColumn(ResultSet rs, int columnIndex, String colTypeName, int colType) throws SQLException {
        Object currentValue = rs.getObject(columnIndex + 1);
        // com.microsoft.sqlserver.jdbc.DateTimeOffset special case as per it does not
        // implement any standard java.sql interface.
        if ("datetimeoffset".equalsIgnoreCase(colTypeName) && colType == -155) {
            return DateTimeUtils.extractOffsetDateTime(currentValue);
        }
        return currentValue;
    }

    /**
     * Processes a single column value based on its SQL type.
     * Updates resultSetMode array if row-by-row mode is needed.
     */
    private Object processColumnValue(ResultSet rs, int columnIndex, DbName dbName, 
                                     SessionInfo session, String[] resultSetMode) throws SQLException {
        int colType = rs.getMetaData().getColumnType(columnIndex + 1);
        String colTypeName = rs.getMetaData().getColumnTypeName(columnIndex + 1);
        
        // Postgres uses type BYTEA which translates to type VARBINARY
        return switch (colType) {
            case Types.VARBINARY -> processVarbinaryColumn(rs, columnIndex, dbName, session, colTypeName, resultSetMode);
            case Types.BLOB, Types.LONGVARBINARY -> processBlobColumn(rs, columnIndex, dbName, session, colType, resultSetMode);
            case Types.CLOB -> processClobColumn(rs, columnIndex, dbName, session, resultSetMode);
            case Types.BINARY -> processBinaryColumn(rs, columnIndex, dbName, session, resultSetMode);
            case Types.DATE -> processDateColumn(rs, columnIndex, colTypeName);
            case Types.TIMESTAMP -> rs.getTimestamp(columnIndex + 1);
            default -> processDefaultColumn(rs, columnIndex, colTypeName, colType);
        };
    }

    /**
     * Processes all columns in a single row from the result set.
     * Updates resultSetMode array if row-by-row mode is needed.
     */
    private Object[] processRow(ResultSet rs, int columnCount, DbName dbName, 
                                SessionInfo session, String[] resultSetMode) throws SQLException {
        Object[] rowValues = new Object[columnCount];
        for (int i = 0; i < columnCount; i++) {
            rowValues[i] = processColumnValue(rs, i, dbName, session, resultSetMode);
        }
        return rowValues;
    }

    /**
     * Sends a result block to the response observer and returns a new builder for the next block.
     */
    private OpQueryResult.OpQueryResultBuilder sendResultBlock(SessionInfo session, 
                                                               List<Object[]> results,
                                                               OpQueryResult.OpQueryResultBuilder queryResultBuilder,
                                                               String resultSetUUID, 
                                                               String resultSetMode,
                                                               StreamObserver<OpResult> responseObserver) {
        responseObserver.onNext(ResultSetWrapper.wrapResults(session, results, queryResultBuilder,
                resultSetUUID, resultSetMode));
        // Recreate the builder to not send labels in every block.
        return OpQueryResult.builder();
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
        // Only used if result set contains LOBs in SQL Server and DB2 (if LOB's
        // present), so cursor is not read in advance,
        // every row has to be requested by the JDBC client.
        String[] resultSetMode = {""};
        boolean resultSetMetadataCollected = false;

        while (rs.next()) {
            if (DbName.DB2.equals(dbName) && !resultSetMetadataCollected) {
                this.collectResultSetMetadata(session, resultSetUUID, rs);
                resultSetMetadataCollected = true;
            }
            justSent = false;
            row++;
            
            Object[] rowValues = processRow(rs, columnCount, dbName, session, resultSetMode);
            results.add(rowValues);

            if (shouldBreakEarly(dbName, resultSetMode[0])) {
                break;
            }

            if (shouldSendBlock(row)) {
                justSent = true;
                queryResultBuilder = sendResultBlock(session, results, queryResultBuilder,
                        resultSetUUID, resultSetMode[0], responseObserver);
                results = new ArrayList<>();
            }
        }

        if (!justSent) {
            // Send a block of remaining records
            responseObserver.onNext(
                    ResultSetWrapper.wrapResults(session, results, queryResultBuilder, resultSetUUID, resultSetMode[0]));
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
    public void xaStart(XaStartRequest request,
                        StreamObserver<XaResponse> responseObserver) {
        XaStartAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaEnd(XaEndRequest request,
                      StreamObserver<XaResponse> responseObserver) {
        XaEndAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaPrepare(XaPrepareRequest request,
                          StreamObserver<XaPrepareResponse> responseObserver) {
        XaPrepareAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaCommit(XaCommitRequest request,
                         StreamObserver<XaResponse> responseObserver) {
        XaCommitAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaRollback(XaRollbackRequest request,
                           StreamObserver<XaResponse> responseObserver) {
        XaRollbackAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaRecover(XaRecoverRequest request,
                          StreamObserver<XaRecoverResponse> responseObserver) {
        XaRecoverAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaForget(XaForgetRequest request,
                         StreamObserver<XaResponse> responseObserver) {
        XaForgetAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaSetTransactionTimeout(XaSetTransactionTimeoutRequest request,
                                        StreamObserver<XaSetTransactionTimeoutResponse> responseObserver) {
        XaSetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaGetTransactionTimeout(XaGetTransactionTimeoutRequest request,
                                        StreamObserver<XaGetTransactionTimeoutResponse> responseObserver) {
        XaGetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);

    }

    @Override
    public void xaIsSameRM(XaIsSameRMRequest request,
                           StreamObserver<XaIsSameRMResponse> responseObserver) {
        XaIsSameRMAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }
}
