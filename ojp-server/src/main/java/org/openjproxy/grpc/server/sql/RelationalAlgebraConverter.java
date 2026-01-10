package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.FrameworkConfig;

import java.util.List;

/**
 * Converts between SQL and Relational Algebra (RelNode) representations.
 * 
 * Phase 1: Basic conversion pipeline - SQL → RelNode → validate round-trip works
 * Phase 2: Add optimization using HepPlanner and rule-based transformations
 * 
 * This class handles the conversion and optimization for Phase 2.
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
     * Phase 2: The RelNode is used for optimization.
     * 
     * @param sqlNode The parsed SQL node
     * @return RelNode representing the relational algebra
     * @throws ConversionException if conversion fails
     */
    public RelNode convertToRelNode(SqlNode sqlNode) throws ConversionException {
        log.debug("Converting SqlNode to RelNode");
        
        try {
            // Create a simple schema for conversion
            // For Phase 2, we still use schema-less conversion
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
     * Applies optimization rules to a RelNode using HepPlanner.
     * Phase 2: Implements rule-based query optimization.
     * 
     * @param relNode The relational algebra node to optimize
     * @param rules List of optimization rules to apply
     * @return Optimized RelNode
     * @throws OptimizationException if optimization fails
     */
    public RelNode applyOptimizations(RelNode relNode, List<RelOptRule> rules) throws OptimizationException {
        log.debug("Applying {} optimization rules to RelNode", rules.size());
        
        try {
            // Create HepProgram with specified rules
            HepProgramBuilder builder = new HepProgramBuilder();
            
            for (RelOptRule rule : rules) {
                builder.addRuleInstance(rule);
            }
            
            HepProgram program = builder.build();
            
            // Create planner and optimize
            HepPlanner planner = new HepPlanner(program);
            planner.setRoot(relNode);
            
            RelNode optimizedNode = planner.findBestExp();
            
            log.debug("Successfully optimized RelNode");
            return optimizedNode;
            
        } catch (Exception e) {
            log.warn("Failed to optimize RelNode: {}", e.getMessage());
            throw new OptimizationException("Failed to apply optimization rules", e);
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
    
    /**
     * Exception thrown when optimization fails.
     */
    public static class OptimizationException extends Exception {
        public OptimizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
