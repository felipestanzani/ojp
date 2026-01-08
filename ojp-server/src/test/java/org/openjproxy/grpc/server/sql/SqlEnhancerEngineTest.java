package org.openjproxy.grpc.server.sql;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SqlEnhancerEngine - Phase 1: Basic parsing functionality
 */
class SqlEnhancerEngineTest {
    
    @Test
    void testEnhancerDisabled() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(false);
        
        assertFalse(engine.isEnabled(), "Engine should be disabled");
        
        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);
        
        assertEquals(sql, result.getEnhancedSql(), "SQL should be unchanged when disabled");
        assertFalse(result.isModified(), "SQL should not be marked as modified");
        assertFalse(result.isHasErrors(), "Should not have errors");
    }
    
    @Test
    void testEnhancerEnabled_ValidSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);
        
        assertTrue(engine.isEnabled(), "Engine should be enabled");
        
        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);
        
        assertEquals(sql, result.getEnhancedSql(), "SQL should be unchanged in Phase 1");
        assertFalse(result.isModified(), "SQL should not be modified in Phase 1");
        assertFalse(result.isHasErrors(), "Should not have errors for valid SQL");
    }
    
    @Test
    void testEnhancerEnabled_InvalidSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);
        
        String sql = "SELECT * FROM WHERE";
        SqlEnhancementResult result = engine.enhance(sql);
        
        // Phase 1: Invalid SQL should pass through (no errors thrown)
        assertEquals(sql, result.getEnhancedSql(), "Invalid SQL should pass through");
        assertFalse(result.isModified(), "SQL should not be modified");
        assertFalse(result.isHasErrors(), "Should not mark as error in pass-through mode");
    }
    
    @Test
    void testEnhancerEnabled_ComplexQuery() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);
        
        String sql = "SELECT u.id, u.name, o.order_id " +
                     "FROM users u " +
                     "INNER JOIN orders o ON u.id = o.user_id " +
                     "WHERE u.status = 'active' AND o.created_at > '2024-01-01'";
        
        SqlEnhancementResult result = engine.enhance(sql);
        
        assertEquals(sql, result.getEnhancedSql(), "Complex SQL should parse successfully");
        assertFalse(result.isModified(), "SQL should not be modified in Phase 1");
        assertFalse(result.isHasErrors(), "Should not have errors for valid complex SQL");
    }
    
    @Test
    void testEnhancerEnabled_PreparedStatement() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);
        
        String sql = "SELECT * FROM users WHERE id = ? AND status = ?";
        SqlEnhancementResult result = engine.enhance(sql);
        
        assertEquals(sql, result.getEnhancedSql(), "Prepared statement SQL should parse successfully");
        assertFalse(result.isModified(), "SQL should not be modified in Phase 1");
        assertFalse(result.isHasErrors(), "Should not have errors for prepared statement");
    }
}
