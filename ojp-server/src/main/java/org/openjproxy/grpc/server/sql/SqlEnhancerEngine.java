package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL Enhancer Engine that uses Apache Calcite for SQL parsing, validation, and optimization.
 * 
 * Phase 1: Basic integration with SQL parsing and relational algebra conversion
 * Phase 2: Add validation and optimization with caching (uses original SQL as cache keys)
 * Phase 3: Add database-specific dialect support and custom functions
 * Phase 4: Full query optimization with rule-based transformations
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
    
    
    /**
     * Creates a new SqlEnhancerEngine with the given enabled status and dialect.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     * @param conversionEnabled Whether to enable SQL-to-RelNode-to-SQL conversion (Phase 1)
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, boolean conversionEnabled) {
        this.enabled = enabled;
        this.conversionEnabled = conversionEnabled;
        this.cache = new ConcurrentHashMap<>();
        this.dialect = OjpSqlDialect.fromString(dialectName);
        this.calciteDialect = dialect.getCalciteDialect();
        
        // Phase 3: Configure parser with dialect-specific settings
        SqlParser.Config baseConfig = SqlParser.config();
        
        // Configure conformance based on dialect
        SqlConformanceEnum conformance = getConformanceForDialect(this.dialect);
        
        this.parserConfig = baseConfig
            .withConformance(conformance)
            .withCaseSensitive(false); // Most SQL is case-insensitive
        
        // Initialize converter if conversion is enabled
        this.converter = conversionEnabled ? 
            new RelationalAlgebraConverter(parserConfig) : null;
        
        if (enabled) {
            String conversionStatus = conversionEnabled ? " with relational algebra conversion" : "";
            log.info("SQL Enhancer Engine initialized and enabled with dialect: {}{}", 
                    dialectName, conversionStatus);
        } else {
            log.info("SQL Enhancer Engine initialized but disabled");
        }
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
        
        long startTime = System.currentTimeMillis();
        SqlEnhancementResult result;
        
        try {
            // Phase 3: Parse and validate SQL with dialect-specific configuration
            SqlParser parser = SqlParser.create(sql, parserConfig);
            SqlNode sqlNode = parser.parseQuery();
            
            // Log successful parse
            log.debug("Successfully parsed and validated SQL with {} dialect: {}", 
                     dialect, sql.substring(0, Math.min(sql.length(), 100)));
            
            // Phase 1: Relational Algebra Conversion (if enabled)
            if (conversionEnabled && converter != null) {
                try {
                    // Convert SQL → RelNode for validation
                    // Phase 1: Just validates conversion works, returns original SQL
                    RelNode relNode = converter.convertToRelNode(sqlNode);
                    log.debug("Successfully converted SQL to RelNode (Phase 1 validation)");
                    
                    // Phase 1: Return original SQL (not converted)
                    // Phase 2 will use optimized/converted SQL
                    result = SqlEnhancementResult.success(sql, false);
                    
                } catch (RelationalAlgebraConverter.ConversionException e) {
                    log.debug("Conversion failed, falling back to original SQL: {}", e.getMessage());
                    result = SqlEnhancementResult.success(sql, false);
                } catch (Exception e) {
                    log.warn("Unexpected error during conversion, falling back to original SQL: {}", 
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
            
            // Phase 3: On parse error, return original SQL (pass-through mode)
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
}
