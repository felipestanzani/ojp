package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/**
 * SQL Enhancer Engine that uses Apache Calcite for SQL parsing, validation, and optimization.
 * 
 * Phase 1: Basic integration with SQL parsing only
 * Phase 2: Add validation and optimization
 * Phase 3: Add database-specific dialect support
 */
@Slf4j
public class SqlEnhancerEngine {
    
    private final boolean enabled;
    private final SqlParser.Config parserConfig;
    
    /**
     * Creates a new SqlEnhancerEngine with the given enabled status.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     */
    public SqlEnhancerEngine(boolean enabled) {
        this.enabled = enabled;
        this.parserConfig = SqlParser.config();
        
        if (enabled) {
            log.info("SQL Enhancer Engine initialized and enabled");
        } else {
            log.info("SQL Enhancer Engine initialized but disabled");
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
     * Parses SQL and returns the enhanced SQL.
     * In Phase 1, this only validates that the SQL can be parsed and returns it unchanged.
     * 
     * @param sql The SQL statement to parse
     * @return SqlEnhancementResult containing the result
     */
    public SqlEnhancementResult enhance(String sql) {
        if (!enabled) {
            // If disabled, just return the original SQL
            return SqlEnhancementResult.passthrough(sql);
        }
        
        try {
            // Phase 1: Just parse the SQL to validate syntax
            SqlParser parser = SqlParser.create(sql, parserConfig);
            SqlNode sqlNode = parser.parseQuery();
            
            // Log successful parse
            log.debug("Successfully parsed SQL: {}", sql.substring(0, Math.min(sql.length(), 100)));
            
            // Phase 1: Return original SQL (no optimization yet)
            return SqlEnhancementResult.success(sql, false);
            
        } catch (SqlParseException e) {
            // Log parse errors
            log.debug("SQL parse error: {} for SQL: {}", e.getMessage(), sql.substring(0, Math.min(sql.length(), 100)));
            
            // Phase 1: On parse error, return original SQL (pass-through mode)
            // This allows queries to still execute even if Calcite can't parse them
            return SqlEnhancementResult.passthrough(sql);
        } catch (Exception e) {
            // Catch any unexpected errors
            log.warn("Unexpected error in SQL enhancer: {}", e.getMessage(), e);
            
            // Fall back to pass-through mode
            return SqlEnhancementResult.passthrough(sql);
        }
    }
}
