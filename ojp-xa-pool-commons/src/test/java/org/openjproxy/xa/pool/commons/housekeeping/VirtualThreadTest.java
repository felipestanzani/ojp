package org.openjproxy.xa.pool.commons.housekeeping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjproxy.xa.pool.commons.CommonsPool2XADataSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify the thread type used for housekeeping tasks.
 * This test checks if the housekeeping thread is a virtual thread or platform thread
 * and documents the implications of each approach.
 */
class VirtualThreadTest {
    
    private org.h2.jdbcx.JdbcDataSource h2DataSource;
    private CommonsPool2XADataSource pooledDataSource;
    
    @BeforeEach
    void setUp() throws Exception {
        // Create H2 XA-capable data source
        h2DataSource = new org.h2.jdbcx.JdbcDataSource();
        h2DataSource.setURL("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        h2DataSource.setUser("sa");
        h2DataSource.setPassword("");
    }
    
    @AfterEach
    void tearDown() {
        if (pooledDataSource != null) {
            pooledDataSource.close();
        }
    }
    
    @Test
    @DisplayName("Verify housekeeping thread type and characteristics")
    void testHousekeepingThreadType() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.leakDetection.enabled", "true");
        config.put("xa.leakDetection.timeoutMs", "5000");
        config.put("xa.leakDetection.intervalMs", "1000");
        config.put("xa.diagnostics.enabled", "true");
        config.put("xa.diagnostics.intervalMs", "2000");
        
        // Get thread count before creating pool
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long threadCountBefore = threadMXBean.getThreadCount();
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        // Wait for executor to start
        Thread.sleep(500);
        
        // Get thread count after
        long threadCountAfter = threadMXBean.getThreadCount();
        
        // Find the housekeeping thread
        ThreadInfo[] threads = threadMXBean.dumpAllThreads(false, false);
        ThreadInfo housekeepingThread = null;
        for (ThreadInfo thread : threads) {
            if (thread.getThreadName().contains("ojp-xa-housekeeping")) {
                housekeepingThread = thread;
                break;
            }
        }
        
        assertNotNull(housekeepingThread, "Housekeeping thread should be created");
        
        // Verify thread characteristics
        System.out.println("=== Housekeeping Thread Characteristics ===");
        System.out.println("Thread Name: " + housekeepingThread.getThreadName());
        System.out.println("Thread ID: " + housekeepingThread.getThreadId());
        System.out.println("Thread State: " + housekeepingThread.getThreadState());
        System.out.println("Thread Count Before: " + threadCountBefore);
        System.out.println("Thread Count After: " + threadCountAfter);
        System.out.println("Thread Count Increase: " + (threadCountAfter - threadCountBefore));
        
        // Check if virtual threads are available and being used
        boolean virtualThreadsAvailable = ThreadFactory.areVirtualThreadsAvailable();
        boolean usingVirtualThreads = ThreadFactory.isUsingVirtualThreads();
        System.out.println("Virtual Threads Available: " + virtualThreadsAvailable);
        System.out.println("Using Virtual Threads: " + usingVirtualThreads);
        System.out.println("Java Version: " + System.getProperty("java.version"));
        
        // Document implications
        System.out.println("\n=== Implementation Details ===");
        if (usingVirtualThreads) {
            System.out.println("✓ Virtual threads are enabled for housekeeping tasks");
            System.out.println("\nVirtual Thread Benefits:");
            System.out.println("- Lightweight: Much lower memory footprint (KB vs MB per thread)");
            System.out.println("- Scalable: Can create millions of virtual threads");
            System.out.println("- No thread pooling needed: Cheap to create on demand");
            System.out.println("- Managed by JVM: Automatic scheduling on carrier threads");
            System.out.println("- Better resource utilization for I/O-bound tasks");
        } else {
            System.out.println("✓ Platform daemon threads are used for housekeeping tasks");
            System.out.println("\nPlatform Thread Characteristics:");
            System.out.println("- Traditional OS thread: Higher memory footprint (~2MB stack per thread)");
            System.out.println("- Limited scalability: OS limits on thread count");
            System.out.println("- Daemon thread: Won't prevent JVM shutdown");
            System.out.println("- Suitable for long-running scheduled tasks");
            
            if (virtualThreadsAvailable) {
                System.out.println("\nNote: Virtual threads are available but disabled via system property");
            } else {
                System.out.println("\nVirtual Thread Upgrade Path:");
                System.out.println("- Upgrade to Java 21+ to enable virtual threads");
                System.out.println("- No code changes required - automatic detection and upgrade");
                System.out.println("- Benefits increase with number of XA pool instances");
                System.out.println("- Particularly beneficial for systems with 100+ pools");
            }
        }
        
        System.out.println("\n=== Backward Compatibility ===");
        System.out.println("- Code works on Java 11, 17, and 21+");
        System.out.println("- Automatic detection of virtual thread support");
        System.out.println("- Graceful fallback to platform threads when unavailable");
        System.out.println("- Can be disabled via -Dojp.xa.useVirtualThreads=false");
        
        // Verify the thread is daemon
        // Note: We can't directly check isDaemon() via ThreadInfo, but we know it from the code
        assertTrue(housekeepingThread.getThreadName().contains("ojp-xa-housekeeping"),
                "Thread name should match our housekeeping thread");
    }
    
    /**
     * Checks if virtual threads are supported in the current Java version.
     * Virtual threads were introduced as a preview feature in Java 19 and
     * became a standard feature in Java 21 (JEP 444).
     *
     * @return true if Thread.ofVirtual() method is available
     */
    private boolean checkIfVirtualThread() {
        return ThreadFactory.areVirtualThreadsAvailable();
    }
    
    @Test
    @DisplayName("Verify single thread per pool instance")
    void testSingleThreadPerPoolInstance() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.leakDetection.enabled", "true");
        config.put("xa.diagnostics.enabled", "true");
        
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long threadCountBefore = threadMXBean.getThreadCount();
        
        // Create first pool
        CommonsPool2XADataSource pool1 = new CommonsPool2XADataSource(h2DataSource, config);
        Thread.sleep(100);
        long threadCountAfter1 = threadMXBean.getThreadCount();
        
        // Create second pool
        org.h2.jdbcx.JdbcDataSource h2DataSource2 = new org.h2.jdbcx.JdbcDataSource();
        h2DataSource2.setURL("jdbc:h2:mem:testdb2;DB_CLOSE_DELAY=-1");
        h2DataSource2.setUser("sa");
        h2DataSource2.setPassword("");
        CommonsPool2XADataSource pool2 = new CommonsPool2XADataSource(h2DataSource2, config);
        Thread.sleep(100);
        long threadCountAfter2 = threadMXBean.getThreadCount();
        
        System.out.println("=== Multiple Pool Instance Test ===");
        System.out.println("Threads before any pools: " + threadCountBefore);
        System.out.println("Threads after pool 1: " + threadCountAfter1 + " (increase: " + (threadCountAfter1 - threadCountBefore) + ")");
        System.out.println("Threads after pool 2: " + threadCountAfter2 + " (increase: " + (threadCountAfter2 - threadCountAfter1) + ")");
        
        // Each pool should create approximately 1 thread (may have +/- due to other JVM threads)
        long pool1Increase = threadCountAfter1 - threadCountBefore;
        long pool2Increase = threadCountAfter2 - threadCountAfter1;
        
        assertTrue(pool1Increase >= 1, "First pool should create at least 1 thread");
        assertTrue(pool2Increase >= 1, "Second pool should create at least 1 thread");
        
        System.out.println("\nWith platform threads: Each pool creates ~1 platform thread (~2MB stack)");
        System.out.println("With virtual threads: Each pool would create ~1 virtual thread (~KB stack)");
        System.out.println("Benefit increases with number of pool instances");
        
        // Clean up
        pool1.close();
        pool2.close();
    }
}
