package org.openjproxy.datasource.hikari;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.openjproxy.datasource.ConnectionPoolProvider;
import org.openjproxy.datasource.ConnectionPoolProviderRegistry;
import org.openjproxy.datasource.PoolConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HikariConnectionPoolProvider.
 */
class HikariConnectionPoolProviderTest {

    private HikariConnectionPoolProvider provider;
    private DataSource createdDataSource;

    @BeforeEach
    void setUp() {
        provider = new HikariConnectionPoolProvider();
        ConnectionPoolProviderRegistry.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (createdDataSource != null) {
            provider.closeDataSource(createdDataSource);
            createdDataSource = null;
        }
    }

    @Test
    @DisplayName("Provider should have correct ID 'hikari'")
    void testProviderId() {
        assertEquals("hikari", provider.id());
        assertEquals(HikariConnectionPoolProvider.PROVIDER_ID, provider.id());
    }

    @Test
    @DisplayName("Provider should be available when HikariCP is on classpath")
    void testIsAvailable() {
        assertTrue(provider.isAvailable());
    }

    @Test
    @DisplayName("Provider should have highest priority (100) as default")
    void testPriority() {
        assertEquals(100, provider.getPriority());
    }

    @Test
    @DisplayName("createDataSource should create a working H2 DataSource")
    void testCreateDataSourceH2() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:hikaritest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .maxPoolSize(5)
                .minIdle(1)
                .connectionTimeoutMs(5000)
                .validationQuery("SELECT 1")
                .build();

        createdDataSource = provider.createDataSource(config);

        assertNotNull(createdDataSource);
        assertInstanceOf(HikariDataSource.class, createdDataSource);

        // Verify we can get a connection
        try (Connection conn = createdDataSource.getConnection()) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());

            // Execute a simple query
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("createDataSource should configure pool sizing correctly")
    void testPoolSizing() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:poolsizetest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxPoolSize(15)
                .minIdle(3)
                .build();

        createdDataSource = provider.createDataSource(config);
        HikariDataSource hikariDs = (HikariDataSource) createdDataSource;

        assertEquals(15, hikariDs.getMaximumPoolSize());
        assertEquals(3, hikariDs.getMinimumIdle());
    }

    @Test
    @DisplayName("createDataSource should configure timeouts correctly")
    void testTimeouts() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:timeouttest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .connectionTimeoutMs(10000)
                .idleTimeoutMs(300000)
                .maxLifetimeMs(600000)
                .build();

        createdDataSource = provider.createDataSource(config);
        HikariDataSource hikariDs = (HikariDataSource) createdDataSource;

        assertEquals(10000, hikariDs.getConnectionTimeout());
        assertEquals(300000, hikariDs.getIdleTimeout());
        assertEquals(600000, hikariDs.getMaxLifetime());
    }

    @Test
    @DisplayName("createDataSource should configure validation query")
    void testValidationQuery() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:validationtest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .validationQuery("SELECT 1")
                .build();

        createdDataSource = provider.createDataSource(config);
        HikariDataSource hikariDs = (HikariDataSource) createdDataSource;

        assertEquals("SELECT 1", hikariDs.getConnectionTestQuery());
    }

    @Test
    @DisplayName("createDataSource should configure auto-commit")
    void testAutoCommit() throws Exception {
        PoolConfig configTrue = PoolConfig.builder()
                .url("jdbc:h2:mem:autocommit1;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .autoCommit(true)
                .build();

        DataSource ds1 = provider.createDataSource(configTrue);
        assertTrue(((HikariDataSource) ds1).isAutoCommit());
        provider.closeDataSource(ds1);

        PoolConfig configFalse = PoolConfig.builder()
                .url("jdbc:h2:mem:autocommit2;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .autoCommit(false)
                .build();

        DataSource ds2 = provider.createDataSource(configFalse);
        assertFalse(((HikariDataSource) ds2).isAutoCommit());
        provider.closeDataSource(ds2);
    }

    @Test
    @DisplayName("createDataSource should throw for null config")
    void testNullConfig() {
        assertThrows(IllegalArgumentException.class, 
                () -> provider.createDataSource(null));
    }

    @Test
    @DisplayName("closeDataSource should close the pool")
    void testCloseDataSource() throws Exception {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:closetest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .build();

        DataSource ds = provider.createDataSource(config);
        
        // Get a connection to ensure pool is initialized
        Connection conn = ds.getConnection();
        conn.close();

        // Close the pool
        provider.closeDataSource(ds);

        // Verify pool is closed
        assertTrue(((HikariDataSource) ds).isClosed());
    }

    @Test
    @DisplayName("closeDataSource should handle null gracefully")
    void testCloseNullDataSource() {
        assertDoesNotThrow(() -> provider.closeDataSource(null));
    }

    @Test
    @DisplayName("getStatistics should return pool statistics")
    void testGetStatistics() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:statstest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxPoolSize(10)
                .minIdle(2)
                .build();

        createdDataSource = provider.createDataSource(config);
        
        // Get some connections to affect stats
        Connection conn1 = createdDataSource.getConnection();
        Connection conn2 = createdDataSource.getConnection();

        Map<String, Object> stats = provider.getStatistics(createdDataSource);

        assertEquals(2, stats.get("activeConnections"));
        assertEquals(10, stats.get("maxPoolSize"));
        assertEquals(2, stats.get("minIdle"));
        assertFalse((Boolean) stats.get("isClosed"));

        conn1.close();
        conn2.close();
    }

    @Test
    @DisplayName("Provider should be discoverable via ServiceLoader")
    void testServiceLoaderDiscovery() {
        ConnectionPoolProviderRegistry.reload();

        Optional<ConnectionPoolProvider> found = ConnectionPoolProviderRegistry.getProvider("hikari");
        assertTrue(found.isPresent());
        assertInstanceOf(HikariConnectionPoolProvider.class, found.get());
    }

    @Test
    @DisplayName("HikariCP provider should be default (highest priority)")
    void testDefaultProvider() {
        ConnectionPoolProviderRegistry.reload();

        Optional<ConnectionPoolProvider> defaultProvider = ConnectionPoolProviderRegistry.getDefaultProvider();
        assertTrue(defaultProvider.isPresent());
        assertEquals("hikari", defaultProvider.get().id());
    }

    @Test
    @DisplayName("Multiple connections should be possible up to maxPoolSize")
    void testMultipleConnections() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:multitest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxPoolSize(3)
                .minIdle(1)
                .connectionTimeoutMs(1000)
                .build();

        createdDataSource = provider.createDataSource(config);

        Connection conn1 = createdDataSource.getConnection();
        Connection conn2 = createdDataSource.getConnection();
        Connection conn3 = createdDataSource.getConnection();

        assertNotNull(conn1);
        assertNotNull(conn2);
        assertNotNull(conn3);

        conn1.close();
        conn2.close();
        conn3.close();
    }

    @Test
    @DisplayName("createDataSource should work with minimal config")
    void testMinimalConfig() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:minimaltest;DB_CLOSE_DELAY=-1")
                .build();

        createdDataSource = provider.createDataSource(config);
        assertNotNull(createdDataSource);

        try (Connection conn = createdDataSource.getConnection()) {
            assertNotNull(conn);
        }
    }

    @Test
    @DisplayName("Metrics prefix should be used in pool name")
    void testMetricsPrefix() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:metricstest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .metricsPrefix("myapp.orders")
                .build();

        createdDataSource = provider.createDataSource(config);
        HikariDataSource hikariDs = (HikariDataSource) createdDataSource;
        
        assertTrue(hikariDs.getPoolName().startsWith("myapp.orders"));
    }
}
