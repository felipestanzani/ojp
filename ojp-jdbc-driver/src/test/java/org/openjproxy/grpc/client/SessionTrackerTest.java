package org.openjproxy.grpc.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SessionTracker.
 * Tests registration, unregistration, counting, and thread-safety.
 */
class SessionTrackerTest {
    
    private SessionTracker tracker;
    private ServerEndpoint server1;
    private ServerEndpoint server2;
    private ServerEndpoint server3;
    
    @BeforeEach
    void setUp() {
        tracker = new SessionTracker();
        server1 = new ServerEndpoint("server1", 1059, "default");
        server2 = new ServerEndpoint("server2", 1059, "default");
        server3 = new ServerEndpoint("server3", 1059, "default");
    }
    
    @Test
    void testRegisterSingleSession() {
        tracker.registerSession("session1", server1);
        
        assertEquals(1, tracker.getSessionCount(server1));
        assertEquals(server1, tracker.getBoundServer("session1"));
        assertTrue(tracker.isTracked("session1"));
        assertEquals(1, tracker.getTotalSessions());
    }
    
    @Test
    void testRegisterMultipleSessions() {
        tracker.registerSession("session1", server1);
        tracker.registerSession("session2", server1);
        tracker.registerSession("session3", server2);
        
        assertEquals(2, tracker.getSessionCount(server1));
        assertEquals(1, tracker.getSessionCount(server2));
        assertEquals(0, tracker.getSessionCount(server3));
        assertEquals(3, tracker.getTotalSessions());
    }
    
    @Test
    void testUnregisterSession() {
        tracker.registerSession("session1", server1);
        tracker.registerSession("session2", server1);
        
        tracker.unregisterSession("session1");
        
        assertEquals(1, tracker.getSessionCount(server1));
        assertNull(tracker.getBoundServer("session1"));
        assertFalse(tracker.isTracked("session1"));
        assertEquals(1, tracker.getTotalSessions());
    }
    
    @Test
    void testUnregisterNonExistentSession() {
        tracker.registerSession("session1", server1);
        
        // Should not throw exception
        tracker.unregisterSession("nonexistent");
        
        assertEquals(1, tracker.getSessionCount(server1));
        assertEquals(1, tracker.getTotalSessions());
    }
    
    @Test
    void testUnregisterNullSession() {
        tracker.registerSession("session1", server1);
        
        // Should not throw exception
        tracker.unregisterSession(null);
        
        assertEquals(1, tracker.getSessionCount(server1));
    }
    
    @Test
    void testRegisterNullSessionUUID() {
        // Should not throw exception, just log warning
        tracker.registerSession(null, server1);
        
        assertEquals(0, tracker.getSessionCount(server1));
        assertEquals(0, tracker.getTotalSessions());
    }
    
    @Test
    void testRegisterEmptySessionUUID() {
        // Should not throw exception, just log warning
        tracker.registerSession("", server1);
        
        assertEquals(0, tracker.getSessionCount(server1));
        assertEquals(0, tracker.getTotalSessions());
    }
    
    @Test
    void testRegisterNullServer() {
        // Should not throw exception, just log warning
        tracker.registerSession("session1", null);
        
        assertNull(tracker.getBoundServer("session1"));
        assertEquals(0, tracker.getTotalSessions());
    }
    
    @Test
    void testReregisterSessionToDifferentServer() {
        tracker.registerSession("session1", server1);
        assertEquals(1, tracker.getSessionCount(server1));
        
        // Re-register to different server
        tracker.registerSession("session1", server2);
        
        assertEquals(0, tracker.getSessionCount(server1));
        assertEquals(1, tracker.getSessionCount(server2));
        assertEquals(server2, tracker.getBoundServer("session1"));
        assertEquals(1, tracker.getTotalSessions());
    }
    
    @Test
    void testGetSessionCounts() {
        tracker.registerSession("session1", server1);
        tracker.registerSession("session2", server1);
        tracker.registerSession("session3", server2);
        
        Map<ServerEndpoint, Integer> counts = tracker.getSessionCounts();
        
        assertEquals(2, counts.get(server1).intValue());
        assertEquals(1, counts.get(server2).intValue());
        assertNull(counts.get(server3));
    }
    
    @Test
    void testGetBoundServerForNullSession() {
        assertNull(tracker.getBoundServer(null));
    }
    
    @Test
    void testGetBoundServerForEmptySession() {
        assertNull(tracker.getBoundServer(""));
    }
    
    @Test
    void testGetBoundServerForNonExistentSession() {
        assertNull(tracker.getBoundServer("nonexistent"));
    }
    
    @Test
    void testIsTrackedForNullSession() {
        assertFalse(tracker.isTracked(null));
    }
    
    @Test
    void testGetSessionCountForNullServer() {
        assertEquals(0, tracker.getSessionCount(null));
    }
    
    @Test
    void testClear() {
        tracker.registerSession("session1", server1);
        tracker.registerSession("session2", server1);
        tracker.registerSession("session3", server2);
        
        assertEquals(3, tracker.getTotalSessions());
        
        tracker.clear();
        
        assertEquals(0, tracker.getTotalSessions());
        assertEquals(0, tracker.getSessionCount(server1));
        assertEquals(0, tracker.getSessionCount(server2));
        assertNull(tracker.getBoundServer("session1"));
        assertFalse(tracker.isTracked("session1"));
    }
    
    @Test
    void testConcurrentRegistration() throws Exception {
        int threadCount = 10;
        int sessionsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * sessionsPerThread);
        
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            for (int s = 0; s < sessionsPerThread; s++) {
                final int sessionIndex = s;
                executor.submit(() -> {
                    try {
                        String sessionId = "thread" + threadIndex + "-session" + sessionIndex;
                        tracker.registerSession(sessionId, server1);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent registration timed out");
        executor.shutdown();
        
        assertEquals(threadCount * sessionsPerThread, tracker.getSessionCount(server1));
        assertEquals(threadCount * sessionsPerThread, tracker.getTotalSessions());
    }
    
    @Test
    void testConcurrentRegistrationAndUnregistration() throws Exception {
        int threadCount = 10;
        int sessionsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch registerLatch = new CountDownLatch(threadCount * sessionsPerThread);
        CountDownLatch unregisterLatch = new CountDownLatch(threadCount * sessionsPerThread);
        
        // Register sessions
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            for (int s = 0; s < sessionsPerThread; s++) {
                final int sessionIndex = s;
                executor.submit(() -> {
                    try {
                        String sessionId = "thread" + threadIndex + "-session" + sessionIndex;
                        tracker.registerSession(sessionId, server1);
                    } finally {
                        registerLatch.countDown();
                    }
                });
            }
        }
        
        assertTrue(registerLatch.await(10, TimeUnit.SECONDS), "Concurrent registration timed out");
        
        // Unregister sessions
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            for (int s = 0; s < sessionsPerThread; s++) {
                final int sessionIndex = s;
                executor.submit(() -> {
                    try {
                        String sessionId = "thread" + threadIndex + "-session" + sessionIndex;
                        tracker.unregisterSession(sessionId);
                    } finally {
                        unregisterLatch.countDown();
                    }
                });
            }
        }
        
        assertTrue(unregisterLatch.await(10, TimeUnit.SECONDS), "Concurrent unregistration timed out");
        executor.shutdown();
        
        assertEquals(0, tracker.getSessionCount(server1));
        assertEquals(0, tracker.getTotalSessions());
    }
    
    @Test
    void testConcurrentRegistrationToMultipleServers() throws Exception {
        int threadCount = 12; // Divisible by 3 for 3 servers
        int sessionsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * sessionsPerThread);
        
        ServerEndpoint[] servers = {server1, server2, server3};
        
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            final ServerEndpoint targetServer = servers[threadIndex % 3];
            for (int s = 0; s < sessionsPerThread; s++) {
                final int sessionIndex = s;
                executor.submit(() -> {
                    try {
                        String sessionId = "thread" + threadIndex + "-session" + sessionIndex;
                        tracker.registerSession(sessionId, targetServer);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent registration timed out");
        executor.shutdown();
        
        int expectedPerServer = (threadCount / 3) * sessionsPerThread;
        assertEquals(expectedPerServer, tracker.getSessionCount(server1));
        assertEquals(expectedPerServer, tracker.getSessionCount(server2));
        assertEquals(expectedPerServer, tracker.getSessionCount(server3));
        assertEquals(threadCount * sessionsPerThread, tracker.getTotalSessions());
    }
}
