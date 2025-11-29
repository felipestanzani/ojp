package org.openjproxy.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConnectionPoolProviderRegistry.
 */
class ConnectionPoolProviderRegistryTest {

    @BeforeEach
    void setUp() {
        ConnectionPoolProviderRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        ConnectionPoolProviderRegistry.clear();
    }

    @Test
    @DisplayName("Manual registration should work")
    void testManualRegistration() {
        ConnectionPoolProvider mockProvider = createMockProvider("test-provider", 10);
        ConnectionPoolProviderRegistry.registerProvider(mockProvider);

        Optional<ConnectionPoolProvider> retrieved = ConnectionPoolProviderRegistry.getProvider("test-provider");
        assertTrue(retrieved.isPresent());
        assertEquals("test-provider", retrieved.get().id());
    }

    @Test
    @DisplayName("getProvider should return empty for unknown provider")
    void testGetUnknownProvider() {
        Optional<ConnectionPoolProvider> result = ConnectionPoolProviderRegistry.getProvider("unknown");
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("getDefaultProvider should return highest priority provider")
    void testGetDefaultProvider() {
        ConnectionPoolProvider lowPriority = createMockProvider("low", -10);
        ConnectionPoolProvider highPriority = createMockProvider("high", 100);
        ConnectionPoolProvider medPriority = createMockProvider("med", 50);

        ConnectionPoolProviderRegistry.registerProvider(lowPriority);
        ConnectionPoolProviderRegistry.registerProvider(highPriority);
        ConnectionPoolProviderRegistry.registerProvider(medPriority);

        Optional<ConnectionPoolProvider> defaultProvider = ConnectionPoolProviderRegistry.getDefaultProvider();
        assertTrue(defaultProvider.isPresent());
        assertEquals("high", defaultProvider.get().id());
    }

    @Test
    @DisplayName("getDefaultProvider should return empty when no providers available")
    void testGetDefaultProviderEmpty() {
        Optional<ConnectionPoolProvider> defaultProvider = ConnectionPoolProviderRegistry.getDefaultProvider();
        assertFalse(defaultProvider.isPresent());
    }

    @Test
    @DisplayName("getProviders should return all registered providers")
    void testGetProviders() {
        ConnectionPoolProviderRegistry.registerProvider(createMockProvider("p1", 0));
        ConnectionPoolProviderRegistry.registerProvider(createMockProvider("p2", 0));
        ConnectionPoolProviderRegistry.registerProvider(createMockProvider("p3", 0));

        Map<String, ConnectionPoolProvider> providers = ConnectionPoolProviderRegistry.getProviders();
        assertEquals(3, providers.size());
        assertTrue(providers.containsKey("p1"));
        assertTrue(providers.containsKey("p2"));
        assertTrue(providers.containsKey("p3"));
    }

    @Test
    @DisplayName("createDataSource should use specified provider")
    void testCreateDataSourceWithProviderId() throws SQLException {
        DataSource mockDataSource = mock(DataSource.class);
        ConnectionPoolProvider mockProvider = mock(ConnectionPoolProvider.class);
        when(mockProvider.id()).thenReturn("test-provider");
        when(mockProvider.isAvailable()).thenReturn(true);
        when(mockProvider.createDataSource(any())).thenReturn(mockDataSource);

        ConnectionPoolProviderRegistry.registerProvider(mockProvider);

        PoolConfig config = PoolConfig.builder().url("jdbc:test:db").build();
        DataSource result = ConnectionPoolProviderRegistry.createDataSource("test-provider", config);

        assertSame(mockDataSource, result);
        verify(mockProvider).createDataSource(config);
    }

    @Test
    @DisplayName("createDataSource should throw for unknown provider")
    void testCreateDataSourceUnknownProvider() {
        PoolConfig config = PoolConfig.builder().url("jdbc:test:db").build();

        assertThrows(IllegalArgumentException.class,
                () -> ConnectionPoolProviderRegistry.createDataSource("unknown", config));
    }

    @Test
    @DisplayName("createDataSource without provider should use default")
    void testCreateDataSourceDefault() throws SQLException {
        DataSource mockDataSource = mock(DataSource.class);
        ConnectionPoolProvider mockProvider = mock(ConnectionPoolProvider.class);
        when(mockProvider.id()).thenReturn("default-provider");
        when(mockProvider.isAvailable()).thenReturn(true);
        when(mockProvider.getPriority()).thenReturn(0);
        when(mockProvider.createDataSource(any())).thenReturn(mockDataSource);

        ConnectionPoolProviderRegistry.registerProvider(mockProvider);

        PoolConfig config = PoolConfig.builder().url("jdbc:test:db").build();
        DataSource result = ConnectionPoolProviderRegistry.createDataSource(config);

        assertSame(mockDataSource, result);
    }

    @Test
    @DisplayName("createDataSource without providers should throw")
    void testCreateDataSourceNoProviders() {
        PoolConfig config = PoolConfig.builder().url("jdbc:test:db").build();

        assertThrows(IllegalStateException.class,
                () -> ConnectionPoolProviderRegistry.createDataSource(config));
    }

    @Test
    @DisplayName("closeDataSource should delegate to provider")
    void testCloseDataSource() throws Exception {
        DataSource mockDataSource = mock(DataSource.class);
        ConnectionPoolProvider mockProvider = mock(ConnectionPoolProvider.class);
        when(mockProvider.id()).thenReturn("test-provider");
        when(mockProvider.isAvailable()).thenReturn(true);

        ConnectionPoolProviderRegistry.registerProvider(mockProvider);
        ConnectionPoolProviderRegistry.closeDataSource("test-provider", mockDataSource);

        verify(mockProvider).closeDataSource(mockDataSource);
    }

    @Test
    @DisplayName("closeDataSource should throw for unknown provider")
    void testCloseDataSourceUnknownProvider() {
        DataSource mockDataSource = mock(DataSource.class);

        assertThrows(IllegalArgumentException.class,
                () -> ConnectionPoolProviderRegistry.closeDataSource("unknown", mockDataSource));
    }

    @Test
    @DisplayName("getStatistics should delegate to provider")
    void testGetStatistics() {
        DataSource mockDataSource = mock(DataSource.class);
        Map<String, Object> stats = Map.of("active", 5, "idle", 10);
        
        ConnectionPoolProvider mockProvider = mock(ConnectionPoolProvider.class);
        when(mockProvider.id()).thenReturn("test-provider");
        when(mockProvider.isAvailable()).thenReturn(true);
        when(mockProvider.getStatistics(mockDataSource)).thenReturn(stats);

        ConnectionPoolProviderRegistry.registerProvider(mockProvider);
        Map<String, Object> result = ConnectionPoolProviderRegistry.getStatistics("test-provider", mockDataSource);

        assertEquals(stats, result);
    }

    @Test
    @DisplayName("getStatistics should return empty map for unknown provider")
    void testGetStatisticsUnknownProvider() {
        DataSource mockDataSource = mock(DataSource.class);
        Map<String, Object> result = ConnectionPoolProviderRegistry.getStatistics("unknown", mockDataSource);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getAvailableProviderIds should return sorted list by priority")
    void testGetAvailableProviderIds() {
        ConnectionPoolProviderRegistry.registerProvider(createMockProvider("low", -10));
        ConnectionPoolProviderRegistry.registerProvider(createMockProvider("high", 100));
        ConnectionPoolProviderRegistry.registerProvider(createMockProvider("med", 50));

        java.util.List<String> ids = ConnectionPoolProviderRegistry.getAvailableProviderIds();
        assertEquals(3, ids.size());
        assertEquals("high", ids.get(0)); // Highest priority first
        assertEquals("med", ids.get(1));
        assertEquals("low", ids.get(2));
    }

    @Test
    @DisplayName("Unavailable providers should be excluded from default selection")
    void testUnavailableProvidersExcluded() {
        ConnectionPoolProvider available = createMockProvider("available", 10);
        
        ConnectionPoolProvider unavailable = mock(ConnectionPoolProvider.class);
        when(unavailable.id()).thenReturn("unavailable");
        when(unavailable.isAvailable()).thenReturn(false);
        when(unavailable.getPriority()).thenReturn(100); // Higher priority but unavailable

        ConnectionPoolProviderRegistry.registerProvider(unavailable);
        ConnectionPoolProviderRegistry.registerProvider(available);

        Optional<ConnectionPoolProvider> defaultProvider = ConnectionPoolProviderRegistry.getDefaultProvider();
        assertTrue(defaultProvider.isPresent());
        assertEquals("available", defaultProvider.get().id());
    }

    @Test
    @DisplayName("clear should remove all providers")
    void testClear() {
        ConnectionPoolProviderRegistry.registerProvider(createMockProvider("p1", 0));
        ConnectionPoolProviderRegistry.registerProvider(createMockProvider("p2", 0));

        assertEquals(2, ConnectionPoolProviderRegistry.getProviders().size());

        ConnectionPoolProviderRegistry.clear();

        assertEquals(0, ConnectionPoolProviderRegistry.getProviders().size());
    }

    @Test
    @DisplayName("Null provider registration should be ignored")
    void testNullProviderRegistration() {
        ConnectionPoolProviderRegistry.registerProvider(null);
        assertEquals(0, ConnectionPoolProviderRegistry.getProviders().size());
    }

    @Test
    @DisplayName("Provider with null id should not be registered")
    void testNullIdProviderRegistration() {
        ConnectionPoolProvider mockProvider = mock(ConnectionPoolProvider.class);
        when(mockProvider.id()).thenReturn(null);

        ConnectionPoolProviderRegistry.registerProvider(mockProvider);
        assertEquals(0, ConnectionPoolProviderRegistry.getProviders().size());
    }

    private ConnectionPoolProvider createMockProvider(String id, int priority) {
        ConnectionPoolProvider provider = mock(ConnectionPoolProvider.class);
        when(provider.id()).thenReturn(id);
        when(provider.getPriority()).thenReturn(priority);
        when(provider.isAvailable()).thenReturn(true);
        return provider;
    }
}
