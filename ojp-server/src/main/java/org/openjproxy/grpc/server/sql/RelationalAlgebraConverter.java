package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.FrameworkConfig;

/**
 * Converts between SQL and Relational Algebra (RelNode) representations.
 * 
 * Phase 1: Basic conversion pipeline - SQL → RelNode → validate round-trip works
 * Phase 2: Add SQL generation from RelNode
 * 
 * This class handles the conversion for Phase 1 - proving the pipeline works.
 * We validate that SQL can be converted to RelNode but return original SQL.
 */
@Slf4j
public class RelationalAlgebraConverter {
    
    private final SqlParser.Config parserConfig;
    
    /**
     * Creates a new converter with the specified parser configuration.
     * 
     * @param parserConfig The parser configuration
     */
    public RelationalAlgebraConverter(SqlParser.Config parserConfig) {
        this.parserConfig = parserConfig;
    }
    
    /**
     * Converts a parsed SQL node to relational algebra.
     * 
     * Phase 1: This validates that the SQL can be converted to RelNode.
     * The actual RelNode is not used yet - we just prove the conversion works.
     * 
     * @param sqlNode The parsed SQL node
     * @return RelNode representing the relational algebra
     * @throws ConversionException if conversion fails
     */
    public RelNode convertToRelNode(SqlNode sqlNode) throws ConversionException {
        log.debug("Converting SqlNode to RelNode");
        
        try {
            // Create a simple schema for conversion
            // For Phase 1, we use schema-less conversion
            SchemaPlus rootSchema = Frameworks.createRootSchema(true);
            
            // Create framework configuration
            FrameworkConfig config = Frameworks.newConfigBuilder()
                .parserConfig(parserConfig)
                .defaultSchema(rootSchema)
                .build();
            
            // Use planner for conversion
            Planner planner = Frameworks.getPlanner(config);
            
            try {
                // Validate the SQL node
                SqlNode validatedNode = planner.validate(sqlNode);
                
                // Convert to relational algebra
                RelRoot relRoot = planner.rel(validatedNode);
                
                log.debug("Successfully converted SqlNode to RelNode");
                return relRoot.rel;
                
            } finally {
                planner.close();
            }
        } catch (Exception e) {
            log.warn("Failed to convert SqlNode to RelNode: {}", e.getMessage());
            throw new ConversionException("Failed to convert SQL to relational algebra", e);
        }
    }
    
    /**
     * Exception thrown when conversion fails.
     */
    public static class ConversionException extends Exception {
        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
