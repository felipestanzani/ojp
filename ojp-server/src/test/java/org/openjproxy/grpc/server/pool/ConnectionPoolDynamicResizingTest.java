package org.openjproxy.grpc.server.pool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests dynamic pool resizing functionality when cluster health changes.
 */
class ConnectionPoolDynamicResizingTest {

    private HikariDataSource dataSource;

    @AfterEach
    void tearDown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Test
    void testDynamicPoolResizingOnHealthChange() {
        // Setup: Create a test HikariDataSource with initial multinode configuration
        String connHash = "test-conn-hash";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:conn_pool_resize_1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(30); // Original: 30 / 3 servers = 10 each
        config.setMinimumIdle(6);      // Original: 6 / 3 servers = 2 each
        
        dataSource = new HikariDataSource(config);
        
        // Simulate initial multinode setup (3 servers)
        MultinodePoolCoordinator coordinator = ConnectionPoolConfigurer.getPoolCoordinator();
        coordinator.calculatePoolSizes(connHash, 30, 6, Arrays.asList("server1:1059", "server2:1059", "server3:1059"));
        
        // Initial pool sizes should be divided (30/3=10, 6/3=2)
        MultinodePoolCoordinator.PoolAllocation allocation = coordinator.getPoolAllocation(connHash);
        assertNotNull(allocation);
        assertEquals(10, allocation.getCurrentMaxPoolSize());
        assertEquals(2, allocation.getCurrentMinIdle());
        
        // Apply initial sizes to dataSource
        dataSource.setMaximumPoolSize(allocation.getCurrentMaxPoolSize());
        dataSource.setMinimumIdle(allocation.getCurrentMinIdle());
        
        assertEquals(10, dataSource.getMaximumPoolSize());
        assertEquals(2, dataSource.getMinimumIdle());
        
        // Simulate cluster health change: 1 server goes down (3 -> 2 healthy)
        ClusterHealthTracker tracker = new ClusterHealthTracker();
        String initialHealth = "server1:1059(UP);server2:1059(UP);server3:1059(UP)";
        String changedHealth = "server1:1059(UP);server2:1059(DOWN);server3:1059(UP)";
        
        // First health report (baseline) - establish initial state
        ConnectionPoolConfigurer.processClusterHealth(connHash, initialHealth, tracker, dataSource);
        
        // Verify initial state
        assertEquals(10, dataSource.getMaximumPoolSize());
        assertEquals(2, dataSource.getMinimumIdle());
        
        // Second health report (change detected) - this should trigger resize
        ConnectionPoolConfigurer.processClusterHealth(connHash, changedHealth, tracker, dataSource);
        
        // Verify pool was resized: 30/2=15, 6/2=3
        assertEquals(15, dataSource.getMaximumPoolSize(), "Max pool size should be 15 after rebalancing");
        assertEquals(3, dataSource.getMinimumIdle(), "Min idle should be 3 after rebalancing");
        
        // Verify coordinator state was updated
        allocation = coordinator.getPoolAllocation(connHash);
        assertEquals(2, allocation.getHealthyServers());
        assertEquals(15, allocation.getCurrentMaxPoolSize());
        assertEquals(3, allocation.getCurrentMinIdle());
    }

    @Test
    void testNoResizeWhenHealthUnchanged() {
        // Setup
        String connHash = "test-conn-hash-2";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:conn_pool_resize_2");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(4);
        
        dataSource = new HikariDataSource(config);
        
        // Simulate 2-server cluster
        MultinodePoolCoordinator coordinator = ConnectionPoolConfigurer.getPoolCoordinator();
        coordinator.calculatePoolSizes(connHash, 20, 4, Arrays.asList("server1:1059", "server2:1059"));
        
        MultinodePoolCoordinator.PoolAllocation allocation = coordinator.getPoolAllocation(connHash);
        dataSource.setMaximumPoolSize(allocation.getCurrentMaxPoolSize());
        dataSource.setMinimumIdle(allocation.getCurrentMinIdle());
        
        assertEquals(10, dataSource.getMaximumPoolSize());
        assertEquals(2, dataSource.getMinimumIdle());
        
        // Report same health twice
        ClusterHealthTracker tracker = new ClusterHealthTracker();
        String health = "server1:1059(UP);server2:1059(UP)";
        
        tracker.hasHealthChanged(connHash, health);
        ConnectionPoolConfigurer.processClusterHealth(connHash, health, tracker, dataSource);
        
        // Second report with same health
        boolean changed = tracker.hasHealthChanged(connHash, health);
        assertFalse(changed, "Health should be unchanged");
        
        ConnectionPoolConfigurer.processClusterHealth(connHash, health, tracker, dataSource);
        
        // Pool sizes should remain unchanged
        assertEquals(10, dataSource.getMaximumPoolSize());
        assertEquals(2, dataSource.getMinimumIdle());
    }

    @Test
    void testApplyPoolSizeChangesWithNullAllocation() {
        // Setup: DataSource without pool allocation
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:conn_pool_resize_3");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        
        dataSource = new HikariDataSource(config);
        
        // Call applyPoolSizeChanges with non-existent connHash (no allocation)
        ConnectionPoolConfigurer.applyPoolSizeChanges("non-existent-hash", dataSource);
        
        // Pool sizes should remain unchanged
        assertEquals(20, dataSource.getMaximumPoolSize());
        assertEquals(5, dataSource.getMinimumIdle());
    }

    @Test
    void testPoolReductionWhenServerRecovers() {
        // Setup: Create a test HikariDataSource
        String connHash = "test-conn-hash-recovery";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:conn_pool_resize_recovery");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(20); // Original: 20 / 2 servers = 10 each
        config.setMinimumIdle(10);     // Original: 10 / 2 servers = 5 each
        
        dataSource = new HikariDataSource(config);
        
        // Simulate initial 2-server setup
        MultinodePoolCoordinator coordinator = ConnectionPoolConfigurer.getPoolCoordinator();
        coordinator.calculatePoolSizes(connHash, 20, 10, Arrays.asList("server1:1059", "server2:1059"));
        
        MultinodePoolCoordinator.PoolAllocation allocation = coordinator.getPoolAllocation(connHash);
        dataSource.setMaximumPoolSize(allocation.getCurrentMaxPoolSize());
        dataSource.setMinimumIdle(allocation.getCurrentMinIdle());
        
        // Initial state: 2 servers, each with max=10, min=5
        assertEquals(10, dataSource.getMaximumPoolSize());
        assertEquals(5, dataSource.getMinimumIdle());
        
        ClusterHealthTracker tracker = new ClusterHealthTracker();
        
        // Step 1: Both servers UP (baseline)
        String bothUp = "server1:1059(UP);server2:1059(UP)";
        ConnectionPoolConfigurer.processClusterHealth(connHash, bothUp, tracker, dataSource);
        assertEquals(10, dataSource.getMaximumPoolSize());
        assertEquals(5, dataSource.getMinimumIdle());
        
        // Step 2: Server 2 goes DOWN - pool should INCREASE
        String oneDown = "server1:1059(UP);server2:1059(DOWN)";
        ConnectionPoolConfigurer.processClusterHealth(connHash, oneDown, tracker, dataSource);
        
        assertEquals(20, dataSource.getMaximumPoolSize(), "Pool should increase to 20 when 1 server down");
        assertEquals(10, dataSource.getMinimumIdle(), "Min idle should increase to 10 when 1 server down");
        
        // Step 3: Server 2 comes back UP - pool should DECREASE back to original
        String bothUpAgain = "server1:1059(UP);server2:1059(UP)";
        ConnectionPoolConfigurer.processClusterHealth(connHash, bothUpAgain, tracker, dataSource);
        
        assertEquals(10, dataSource.getMaximumPoolSize(), "Pool should decrease back to 10 when server recovers");
        assertEquals(5, dataSource.getMinimumIdle(), "Min idle should decrease back to 5 when server recovers");
        
        // Verify coordinator state
        allocation = coordinator.getPoolAllocation(connHash);
        assertEquals(2, allocation.getHealthyServers());
        assertEquals(10, allocation.getCurrentMaxPoolSize());
        assertEquals(5, allocation.getCurrentMinIdle());
    }
}
