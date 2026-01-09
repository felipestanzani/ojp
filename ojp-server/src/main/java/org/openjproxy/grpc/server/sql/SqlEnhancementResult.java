package org.openjproxy.grpc.server.sql;

import lombok.Getter;

/**
 * Result of SQL enhancement operation.
 * Contains the enhanced SQL and metadata about the enhancement process.
 */
@Getter
public class SqlEnhancementResult {
    
    private final String enhancedSql;
    private final boolean modified;
    private final boolean hasErrors;
    private final String errorMessage;
    
    private SqlEnhancementResult(String enhancedSql, boolean modified, boolean hasErrors, String errorMessage) {
        this.enhancedSql = enhancedSql;
        this.modified = modified;
        this.hasErrors = hasErrors;
        this.errorMessage = errorMessage;
    }
    
    /**
     * Creates a successful enhancement result.
     * 
     * @param enhancedSql The enhanced SQL
     * @param modified Whether the SQL was modified
     * @return SqlEnhancementResult
     */
    public static SqlEnhancementResult success(String enhancedSql, boolean modified) {
        return new SqlEnhancementResult(enhancedSql, modified, false, null);
    }
    
    /**
     * Creates a pass-through result (original SQL unchanged).
     * 
     * @param originalSql The original SQL
     * @return SqlEnhancementResult
     */
    public static SqlEnhancementResult passthrough(String originalSql) {
        return new SqlEnhancementResult(originalSql, false, false, null);
    }
    
    /**
     * Creates an error result.
     * 
     * @param originalSql The original SQL
     * @param errorMessage The error message
     * @return SqlEnhancementResult
     */
    public static SqlEnhancementResult error(String originalSql, String errorMessage) {
        return new SqlEnhancementResult(originalSql, false, true, errorMessage);
    }
}
