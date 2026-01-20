package org.openjproxy.xa.pool.commons.housekeeping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Factory for creating thread executors with support for both platform and virtual threads.
 * <p>
 * This factory attempts to use virtual threads (Java 21+) when available, falling back to
 * platform daemon threads for older Java versions. Virtual threads provide better scalability
 * and reduced memory footprint for workloads with many XA pool instances.
 * </p>
 * 
 * <h3>Benefits of Virtual Threads for Housekeeping:</h3>
 * <ul>
 *   <li><strong>Reduced Memory Footprint:</strong> Virtual threads use ~KB of stack vs ~2MB for platform threads</li>
 *   <li><strong>Better Scalability:</strong> Can create millions of virtual threads vs thousands of platform threads</li>
 *   <li><strong>Lower Overhead:</strong> Cheaper to create and manage, ideal for periodic tasks</li>
 *   <li><strong>No Functional Changes:</strong> Same behavior as platform threads, transparent upgrade</li>
 * </ul>
 * 
 * <h3>When Virtual Threads Are Used:</h3>
 * <ul>
 *   <li>Java 21 or later runtime</li>
 *   <li>Thread.ofVirtual() API is available</li>
 *   <li>System property "ojp.xa.useVirtualThreads" is not set to "false"</li>
 * </ul>
 * 
 * <h3>Backward Compatibility:</h3>
 * <p>
 * When virtual threads are not available (Java 11-20), the factory creates traditional
 * platform daemon threads. This ensures the code works across all supported Java versions
 * without requiring changes.
 * </p>
 * 
 * @see java.lang.Thread.Builder.OfVirtual
 * @see Executors#newThreadPerTaskExecutor
 */
public class ThreadFactory {
    private static final Logger log = LoggerFactory.getLogger(ThreadFactory.class);
    
    private static final boolean VIRTUAL_THREADS_AVAILABLE;
    private static final boolean USE_VIRTUAL_THREADS;
    
    static {
        VIRTUAL_THREADS_AVAILABLE = checkVirtualThreadSupport();
        
        // Allow disabling virtual threads via system property
        String property = System.getProperty("ojp.xa.useVirtualThreads", "true");
        USE_VIRTUAL_THREADS = VIRTUAL_THREADS_AVAILABLE && Boolean.parseBoolean(property);
        
        if (VIRTUAL_THREADS_AVAILABLE) {
            if (USE_VIRTUAL_THREADS) {
                log.info("Virtual threads are available and enabled for XA pool housekeeping tasks");
            } else {
                log.info("Virtual threads are available but disabled via system property ojp.xa.useVirtualThreads=false");
            }
        } else {
            log.info("Virtual threads not available (requires Java 21+), using platform daemon threads");
        }
    }
    
    /**
     * Checks if virtual threads are supported in the current Java runtime.
     * Virtual threads were introduced as a preview feature in Java 19 and
     * became a standard feature in Java 21 (JEP 444).
     *
     * @return true if Thread.ofVirtual() method is available
     */
    private static boolean checkVirtualThreadSupport() {
        try {
            Class<?> threadClass = Class.forName("java.lang.Thread");
            Method ofVirtualMethod = threadClass.getMethod("ofVirtual");
            return ofVirtualMethod != null;
        } catch (Exception e) {
            // Method not found or other error - virtual threads not available
            return false;
        }
    }
    
    /**
     * Creates a single-threaded scheduled executor service for housekeeping tasks.
     * <p>
     * If virtual threads are available (Java 21+), creates a virtual thread-based executor.
     * Otherwise, creates a traditional platform thread-based executor with daemon threads.
     * </p>
     * 
     * <p>The returned executor is suitable for periodic housekeeping tasks such as:</p>
     * <ul>
     *   <li>Leak detection scanning</li>
     *   <li>Pool diagnostics reporting</li>
     *   <li>Max lifetime enforcement</li>
     * </ul>
     *
     * @param threadName the name to use for the thread(s)
     * @return a new ScheduledExecutorService
     */
    public static ScheduledExecutorService createHousekeepingExecutor(String threadName) {
        if (USE_VIRTUAL_THREADS) {
            return createVirtualThreadExecutor(threadName);
        } else {
            return createPlatformThreadExecutor(threadName);
        }
    }
    
    /**
     * Creates a virtual thread-based scheduled executor (Java 21+).
     * <p>
     * Uses reflection to invoke Thread.ofVirtual() to maintain compatibility
     * with Java 11 compilation target.
     * </p>
     *
     * @param threadName the name prefix for virtual threads
     * @return a new ScheduledExecutorService using virtual threads
     */
    private static ScheduledExecutorService createVirtualThreadExecutor(String threadName) {
        try {
            // Get Thread.ofVirtual() method
            Class<?> threadClass = Class.forName("java.lang.Thread");
            Method ofVirtualMethod = threadClass.getMethod("ofVirtual");
            
            // Call Thread.ofVirtual() to get a Builder.OfVirtual instance
            Object virtualThreadBuilder = ofVirtualMethod.invoke(null);
            
            // Get the Builder.OfVirtual interface
            Class<?> builderInterface = Class.forName("java.lang.Thread$Builder$OfVirtual");
            
            // Call name(String prefix, long start) to name the threads
            Method nameMethod = builderInterface.getMethod("name", String.class, long.class);
            virtualThreadBuilder = nameMethod.invoke(virtualThreadBuilder, threadName + "-", 0L);
            
            // Call factory() to get a ThreadFactory
            Method factoryMethod = builderInterface.getMethod("factory");
            java.util.concurrent.ThreadFactory threadFactory = 
                (java.util.concurrent.ThreadFactory) factoryMethod.invoke(virtualThreadBuilder);
            
            // Create a single-threaded scheduled executor with the virtual thread factory
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
            
            log.debug("Created virtual thread-based scheduled executor: {}", threadName);
            return executor;
            
        } catch (Exception e) {
            // Fallback to platform threads if virtual thread creation fails
            log.warn("Failed to create virtual thread executor, falling back to platform threads", e);
            return createPlatformThreadExecutor(threadName);
        }
    }
    
    /**
     * Creates a platform thread-based scheduled executor with daemon threads.
     * <p>
     * This is the traditional approach used before virtual threads were available.
     * Platform threads have higher memory overhead (~2MB stack) but are suitable
     * for long-running scheduled tasks.
     * </p>
     *
     * @param threadName the name for the daemon thread
     * @return a new ScheduledExecutorService using platform daemon threads
     */
    private static ScheduledExecutorService createPlatformThreadExecutor(String threadName) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        
        log.debug("Created platform daemon thread-based scheduled executor: {}", threadName);
        return executor;
    }
    
    /**
     * Checks if virtual threads are being used for housekeeping.
     * Useful for testing and diagnostics.
     *
     * @return true if virtual threads are available and enabled
     */
    public static boolean isUsingVirtualThreads() {
        return USE_VIRTUAL_THREADS;
    }
    
    /**
     * Checks if virtual threads are available in the current Java runtime.
     * Useful for testing and diagnostics.
     *
     * @return true if Thread.ofVirtual() is available
     */
    public static boolean areVirtualThreadsAvailable() {
        return VIRTUAL_THREADS_AVAILABLE;
    }
}
