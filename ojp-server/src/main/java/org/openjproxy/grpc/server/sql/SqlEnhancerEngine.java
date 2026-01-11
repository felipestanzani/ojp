package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL Enhancer Engine that uses Apache Calcite for SQL parsing, validation, and optimization.
 * 
 * Features:
 * - SQL syntax validation and parsing
 * - Query optimization using rule-based transformations
 * - SQL rewriting for improved performance
 * - Comprehensive caching for fast repeated queries
 * - Metrics tracking for monitoring
 */
@Slf4j
public class SqlEnhancerEngine {
    
    private final boolean enabled;
    private final SqlParser.Config parserConfig;
    private final ConcurrentHashMap<String, SqlEnhancementResult> cache;
    private final OjpSqlDialect dialect;
    private final org.apache.calcite.sql.SqlDialect calciteDialect;
    private final RelationalAlgebraConverter converter;
    private final boolean conversionEnabled;
    
    // Optimization configuration
    private final boolean optimizationEnabled;
    private final OptimizationRuleRegistry ruleRegistry;
    private final List<String> enabledRules;
    
    // Schema management
    private final SchemaCache schemaCache;
    private final SchemaLoader schemaLoader;
    private final javax.sql.DataSource dataSource;
    private final String catalogName;
    private final String schemaName;
    private final long schemaRefreshIntervalMillis;
    
    // Metrics tracking - using AtomicLong for thread-safe updates without synchronization
    private final AtomicLong totalQueriesProcessed = new AtomicLong(0);
    private final AtomicLong totalQueriesOptimized = new AtomicLong(0);
    private final AtomicLong totalOptimizationTimeMs = new AtomicLong(0);
    private final AtomicLong totalQueriesModified = new AtomicLong(0);
    
    
    /**
     * Creates a new SqlEnhancerEngine with full configuration options including schema refresh.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     * @param optimizationEnabled Whether to enable query optimization
     * @param enabledRules List of rule names to enable (null = use safe rules)
     * @param schemaCache Optional schema cache for real schema metadata (can be null)
     * @param schemaLoader Optional schema loader for periodic refresh (can be null)
     * @param dataSource Optional data source for schema refresh (can be null)
     * @param catalogName Catalog name for schema refresh (can be null)
     * @param schemaName Schema name for schema refresh (can be null)
     * @param schemaRefreshIntervalHours Hours between schema refreshes (0 = disabled)
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, boolean conversionEnabled,
                             boolean optimizationEnabled, List<String> enabledRules,
                             SchemaCache schemaCache, SchemaLoader schemaLoader,
                             javax.sql.DataSource dataSource, String catalogName, String schemaName,
                             long schemaRefreshIntervalHours) {
        this.enabled = enabled;
        this.conversionEnabled = conversionEnabled;
        this.optimizationEnabled = optimizationEnabled;
        this.cache = new ConcurrentHashMap<>();
        this.dialect = OjpSqlDialect.fromString(dialectName);
        this.calciteDialect = dialect.getCalciteDialect();
        this.schemaCache = schemaCache;
        this.schemaLoader = schemaLoader;
        this.dataSource = dataSource;
        this.catalogName = catalogName;
        this.schemaName = schemaName;
        this.schemaRefreshIntervalMillis = schemaRefreshIntervalHours * 60 * 60 * 1000; // Convert hours to milliseconds
        
        // Configure parser with dialect-specific settings
        SqlParser.Config baseConfig = SqlParser.config();
        
        // Configure conformance based on dialect
        SqlConformanceEnum conformance = getConformanceForDialect(this.dialect);
        
        this.parserConfig = baseConfig
            .withConformance(conformance)
            .withCaseSensitive(false); // Most SQL is case-insensitive
        
        // Initialize converter if conversion is enabled
        // Pass SqlDialect for SQL generation and SchemaCache for real schema
        this.converter = conversionEnabled ? 
            new RelationalAlgebraConverter(parserConfig, calciteDialect, schemaCache) : null;
        
        // Initialize optimization components
        this.ruleRegistry = new OptimizationRuleRegistry();
        this.enabledRules = enabledRules != null ? enabledRules : 
            Arrays.asList("FILTER_REDUCE", "PROJECT_REDUCE", "FILTER_MERGE", "PROJECT_MERGE", "PROJECT_REMOVE");
        
        if (enabled) {
            String conversionStatus = conversionEnabled ? " with relational algebra conversion" : "";
            String optimizationStatus = optimizationEnabled ? " and optimization" : "";
            String schemaStatus = schemaCache != null ? " and real schema support" : "";
            String refreshStatus = (schemaLoader != null && dataSource != null && schemaRefreshIntervalHours > 0) ? 
                " with periodic refresh" : "";
            log.info("SQL Enhancer Engine initialized and enabled with dialect: {}{}{}{}{}", 
                    dialectName, conversionStatus, optimizationStatus, schemaStatus, refreshStatus);
        } else {
            log.info("SQL Enhancer Engine initialized but disabled");
        }
    }
    
    /**
     * Creates a new SqlEnhancerEngine with full configuration options (without schema refresh).
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     * @param optimizationEnabled Whether to enable query optimization
     * @param enabledRules List of rule names to enable (null = use safe rules)
     * @param schemaCache Optional schema cache for real schema metadata (can be null)
     * @param schemaRefreshIntervalHours Hours between schema refreshes (0 = disabled)
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, boolean conversionEnabled,
                             boolean optimizationEnabled, List<String> enabledRules,
                             SchemaCache schemaCache, long schemaRefreshIntervalHours) {
        this(enabled, dialectName, conversionEnabled, optimizationEnabled, enabledRules,
             schemaCache, null, null, null, null, schemaRefreshIntervalHours);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with full configuration options (no schema cache).
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     * @param optimizationEnabled Whether to enable query optimization
     * @param enabledRules List of rule names to enable (null = use safe rules)
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, boolean conversionEnabled,
                             boolean optimizationEnabled, List<String> enabledRules) {
        this(enabled, dialectName, conversionEnabled, optimizationEnabled, enabledRules, null, 0);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with conversion enabled.
     * Optimization is disabled by default for backward compatibility.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, boolean conversionEnabled) {
        this(enabled, dialectName, conversionEnabled, false, null);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with the given enabled status and dialect.
     * Conversion is disabled by default for backward compatibility.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName) {
        this(enabled, dialectName, false);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with default GENERIC dialect.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     */
    public SqlEnhancerEngine(boolean enabled) {
        this(enabled, "GENERIC");
    }
    
    /**
     * Get SQL conformance based on dialect.
     */
    private SqlConformanceEnum getConformanceForDialect(OjpSqlDialect dialect) {
        switch (dialect) {
            case POSTGRESQL:
                return SqlConformanceEnum.LENIENT;
            case MYSQL:
                return SqlConformanceEnum.MYSQL_5;
            case ORACLE:
                return SqlConformanceEnum.ORACLE_12;
            case SQL_SERVER:
                return SqlConformanceEnum.SQL_SERVER_2008;
            default:
                return SqlConformanceEnum.DEFAULT;
        }
    }
    
    /**
     * Checks if the SQL enhancer is enabled.
     * 
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Gets the configured SQL dialect.
     * 
     * @return the SQL dialect
     */
    public OjpSqlDialect getDialect() {
        return dialect;
    }
    
    /**
     * Gets cache statistics.
     * 
     * @return String describing cache statistics
     */
    public String getCacheStats() {
        return String.format("Cache size: %d (no max limit, dynamically expands)", cache.size());
    }
    
    /**
     * Gets optimization statistics.
     * 
     * @return String describing optimization statistics
     */
    public String getOptimizationStats() {
        if (!optimizationEnabled) {
            return "Optimization disabled";
        }
        
        long processed = totalQueriesProcessed.get();
        long optimized = totalQueriesOptimized.get();
        long optimizationTime = totalOptimizationTimeMs.get();
        long modified = totalQueriesModified.get();
        
        long avgOptimizationTime = optimized > 0 ? optimizationTime / optimized : 0;
        
        double optimizationRate = processed > 0 ? 
            (100.0 * optimized / processed) : 0.0;
        
        double modificationRate = optimized > 0 ? 
            (100.0 * modified / optimized) : 0.0;
        
        return String.format(
            "Optimization Stats: Processed=%d, Optimized=%d (%.1f%%), Modified=%d (%.1f%%), AvgTime=%dms",
            processed, optimized, optimizationRate,
            modified, modificationRate, avgOptimizationTime
        );
    }
    
    /**
     * Clears the cache.
     */
    public void clearCache() {
        cache.clear();
        log.info("SQL enhancer cache cleared");
    }
    
    /**
     * Parses, validates, and optionally optimizes SQL.
     * Phase 3: Adds database-specific dialect support.
     * 
     * Note: Enhancement happens synchronously in the same thread as query execution,
     * on the first execution of each unique SQL query. The SQL is blocked until 
     * parsing completes or times out. Subsequent executions use cached results.
     * 
     * @param sql The SQL statement to enhance
     * @return SqlEnhancementResult containing the result
     */
    public SqlEnhancementResult enhance(String sql) {
        if (!enabled) {
            // If disabled, just return the original SQL
            return SqlEnhancementResult.passthrough(sql);
        }
        
        // Phase 2: Use original SQL as cache key
        // Check cache first - no synchronization needed for ConcurrentHashMap.get()
        SqlEnhancementResult cached = cache.get(sql);
        if (cached != null) {
            log.debug("Cache hit for SQL (dialect: {}): {}", dialect, sql.substring(0, Math.min(sql.length(), 50)));
            return cached;
        }
        
        // Track metrics
        totalQueriesProcessed.incrementAndGet();
        
        long startTime = System.currentTimeMillis();
        SqlEnhancementResult result;
        
        try {
            // Parse and validate SQL with dialect-specific configuration
            SqlParser parser = SqlParser.create(sql, parserConfig);
            SqlNode sqlNode = parser.parseQuery();
            
            // Log successful parse
            log.debug("Successfully parsed and validated SQL with {} dialect: {}", 
                     dialect, sql.substring(0, Math.min(sql.length(), 100)));
            
            // Relational Algebra Conversion and Optimization (if enabled)
            if (conversionEnabled && converter != null) {
                try {
                    long optimizationStartTime = System.currentTimeMillis();
                    
                    // Convert SQL → RelNode
                    RelNode relNode = converter.convertToRelNode(sql);
                    log.debug("Successfully converted SQL to RelNode");
                    
                    // Apply optimization if enabled
                    if (optimizationEnabled) {
                        try {
                            // Get optimization rules
                            List<RelOptRule> rules = ruleRegistry.getRulesByNames(enabledRules);
                            log.debug("Applying {} optimization rules", rules.size());
                            
                            // Apply optimizations
                            RelNode optimizedNode = converter.applyOptimizations(relNode, rules);
                            
                            // Generate SQL from optimized RelNode
                            try {
                                String optimizedSql = converter.convertToSql(optimizedNode);
                                
                                long optimizationEndTime = System.currentTimeMillis();
                                long optimizationTime = optimizationEndTime - optimizationStartTime;
                                
                                // Check if SQL was actually modified
                                boolean wasModified = !sql.trim().equalsIgnoreCase(optimizedSql.trim());
                                
                                // Track metrics
                                totalQueriesOptimized.incrementAndGet();
                                totalOptimizationTimeMs.addAndGet(optimizationTime);
                                if (wasModified) {
                                    totalQueriesModified.incrementAndGet();
                                }
                                
                                if (wasModified) {
                                    log.info("SQL optimized with {} rules in {}ms. Original length: {}, Optimized length: {}", 
                                            rules.size(), optimizationTime, sql.length(), optimizedSql.length());
                                    log.debug("Original SQL: {}", sql.substring(0, Math.min(sql.length(), 200)));
                                    log.debug("Optimized SQL: {}", optimizedSql.substring(0, Math.min(optimizedSql.length(), 200)));
                                }
                                
                                result = SqlEnhancementResult.optimized(optimizedSql, wasModified, 
                                                                       enabledRules, optimizationTime);
                                
                                log.debug("Optimization complete in {}ms with {} rules, modified: {}", 
                                         optimizationTime, rules.size(), wasModified);
                                
                            } catch (RelationalAlgebraConverter.SqlGenerationException e) {
                                log.debug("SQL generation failed, using original SQL: {}", e.getMessage());
                                long optimizationTime = System.currentTimeMillis() - optimizationStartTime;
                                // Return original SQL with optimization metadata even though generation failed
                                result = SqlEnhancementResult.optimized(sql, false, enabledRules, optimizationTime);
                            }
                            
                        } catch (RelationalAlgebraConverter.OptimizationException e) {
                            log.debug("Optimization failed, using original SQL: {}", e.getMessage());
                            result = SqlEnhancementResult.success(sql, false);
                        }
                    } else {
                        // Optimization not enabled, return original SQL
                        result = SqlEnhancementResult.success(sql, false);
                    }
                    
                } catch (RelationalAlgebraConverter.ConversionException e) {
                    log.debug("Conversion failed, falling back to original SQL: {}", e.getMessage());
                    result = SqlEnhancementResult.success(sql, false);
                } catch (Exception e) {
                    log.warn("Unexpected error during conversion/optimization, falling back to original SQL: {}", 
                            e.getMessage());
                    result = SqlEnhancementResult.success(sql, false);
                }
            } else {
                // Conversion not enabled, return original SQL
                result = SqlEnhancementResult.success(sql, false);
            }
            
        } catch (SqlParseException e) {
            // Log parse errors with dialect info
            log.debug("SQL parse error with {} dialect: {} for SQL: {}", 
                     dialect, e.getMessage(), sql.substring(0, Math.min(sql.length(), 100)));
            
            // On parse error, return original SQL (pass-through mode)
            result = SqlEnhancementResult.passthrough(sql);
        } catch (Exception e) {
            // Catch any unexpected errors
            log.warn("Unexpected error in SQL enhancer with {} dialect: {}", dialect, e.getMessage(), e);
            
            // Fall back to pass-through mode
            result = SqlEnhancementResult.passthrough(sql);
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // Log performance if it took significant time
        if (duration > 50) {
            log.debug("SQL enhancement took {}ms for SQL: {}", duration, sql.substring(0, Math.min(sql.length(), 50)));
        }
        
        // Phase 2: Cache the result - ConcurrentHashMap.put() is thread-safe without explicit synchronization
        // If two threads cache the same SQL simultaneously, the last one wins (acceptable - same result)
        cache.put(sql, result);
        
        // Check if schema refresh is needed (after enhancement to minimize overhead)
        triggerSchemaRefreshIfNeeded();
        
        return result;
    }
    
    /**
     * Translates SQL from one dialect to another.
     * Phase 3: Dialect translation support.
     * 
     * @param sql The SQL to translate
     * @param targetDialect The target SQL dialect
     * @return Translated SQL or original if translation fails
     */
    public String translateDialect(String sql, OjpSqlDialect targetDialect) {
        if (!enabled) {
            return sql;
        }
        
        try {
            // Parse with current dialect
            SqlParser parser = SqlParser.create(sql, parserConfig);
            SqlNode sqlNode = parser.parseQuery();
            
            // Convert to target dialect
            org.apache.calcite.sql.SqlDialect targetCalciteDialect = targetDialect.getCalciteDialect();
            String translated = sqlNode.toSqlString(targetCalciteDialect).getSql();
            
            log.debug("Translated SQL from {} to {}: {} chars -> {} chars", 
                     this.dialect, targetDialect, sql.length(), translated.length());
            
            return translated;
        } catch (Exception e) {
            log.warn("Failed to translate SQL from {} to {}: {}", 
                    this.dialect, targetDialect, e.getMessage());
            return sql; // Return original on error
        }
    }
    
    /**
     * Triggers an asynchronous schema refresh if needed.
     * Checks if refresh interval has passed and refresh is not already in progress.
     */
    private void triggerSchemaRefreshIfNeeded() {
        // Only refresh if all required components are available
        if (schemaCache == null || schemaLoader == null || dataSource == null) {
            return;
        }
        
        // Check if refresh is needed and not already in progress
        if (schemaCache.needsRefresh(schemaRefreshIntervalMillis)) {
            if (schemaCache.tryAcquireRefreshLock()) {
                try {
                    log.debug("Triggering async schema refresh");
                    // Trigger async refresh
                    schemaLoader.loadSchemaAsync(dataSource, catalogName, schemaName)
                        .thenAccept(schema -> {
                            schemaCache.updateSchema(schema);
                            log.info("Schema refreshed successfully with {} tables", schema.getTables().size());
                        })
                        .exceptionally(ex -> {
                            log.warn("Schema refresh failed: {}", ex.getMessage());
                            return null;
                        })
                        .whenComplete((result, ex) -> schemaCache.releaseRefreshLock());
                } catch (Exception e) {
                    schemaCache.releaseRefreshLock();
                    log.warn("Failed to start schema refresh: {}", e.getMessage());
                }
            }
        }
    }
}
