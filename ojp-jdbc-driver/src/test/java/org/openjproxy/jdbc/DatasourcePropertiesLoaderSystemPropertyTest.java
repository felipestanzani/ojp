package org.openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that system properties and environment variables override file properties in DatasourcePropertiesLoader
 */
public class DatasourcePropertiesLoaderSystemPropertyTest {

    @AfterEach
    public void cleanup() {
        // Clear any system properties we set during tests
        System.clearProperty("multinode.ojp.connection.pool.enabled");
        System.clearProperty("ojp.connection.pool.enabled");
    }

    @Test
    public void testSystemPropertyOverridesFileProperty() {
        // Set a system property that should override the file property
        System.setProperty("multinode.ojp.connection.pool.enabled", "false");
        
        // Load properties for "multinode" datasource
        Properties props = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("multinode");
        
        assertNotNull(props, "Properties should not be null");
        
        // The system property should override the file property
        String poolEnabled = props.getProperty("ojp.connection.pool.enabled");
        assertNotNull(poolEnabled, "Pool enabled property should be present");
        assertEquals("false", poolEnabled, "System property should override file property to 'false'");
    }

    @Test
    public void testDefaultDataSourceSystemPropertyOverride() {
        // Set a system property for the default datasource
        System.setProperty("ojp.connection.pool.enabled", "false");
        
        // Load properties for "default" datasource
        Properties props = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("default");
        
        // The system property should be present
        String poolEnabled = props.getProperty("ojp.connection.pool.enabled");
        assertNotNull(poolEnabled, "Pool enabled property should be present");
        assertEquals("false", poolEnabled, "System property should set pool enabled to 'false'");
    }
    
    @Test
    public void testEnvironmentVariableOverridesSystemProperty() throws Exception {
        // Note: Modifying environment variables at runtime is complex and not recommended for production
        // This test documents the expected behavior but may not run in all environments
        
        // Set a system property first
        System.setProperty("multinode.ojp.connection.pool.enabled", "true");
        
        // Mock environment variable (this is for documentation purposes)
        // In real usage: export MULTINODE_OJP_CONNECTION_POOL_ENABLED=false
        Map<String, String> mockEnv = new HashMap<>();
        mockEnv.put("MULTINODE_OJP_CONNECTION_POOL_ENABLED", "false");
        
        try {
            setEnv(mockEnv);
            
            // Load properties for "multinode" datasource
            Properties props = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("multinode");
            
            assertNotNull(props, "Properties should not be null");
            
            // The environment variable should override the system property
            String poolEnabled = props.getProperty("ojp.connection.pool.enabled");
            assertNotNull(poolEnabled, "Pool enabled property should be present");
            assertEquals("false", poolEnabled, "Environment variable should override system property to 'false'");
        } finally {
            // Clean up
            System.clearProperty("multinode.ojp.connection.pool.enabled");
        }
    }
    
    /**
     * Helper method to set environment variables for testing
     * Warning: This uses reflection and is only suitable for testing
     */
    @SuppressWarnings("unchecked")
    private static void setEnv(Map<String, String> newenv) throws Exception {
        try {
            Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
            Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
            theEnvironmentField.setAccessible(true);
            Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
            env.putAll(newenv);
            Field theCaseInsensitiveEnvironmentField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
            theCaseInsensitiveEnvironmentField.setAccessible(true);
            Map<String, String> cienv = (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
            cienv.putAll(newenv);
        } catch (NoSuchFieldException e) {
            Class<?>[] classes = java.util.Collections.class.getDeclaredClasses();
            Map<String, String> env = System.getenv();
            for(Class<?> cl : classes) {
                if("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
                    Field field = cl.getDeclaredField("m");
                    field.setAccessible(true);
                    Object obj = field.get(env);
                    Map<String, String> map = (Map<String, String>) obj;
                    map.clear();
                    map.putAll(newenv);
                }
            }
        }
    }
}
