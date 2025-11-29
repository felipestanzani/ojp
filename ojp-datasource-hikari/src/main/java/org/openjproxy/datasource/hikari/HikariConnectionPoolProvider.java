package org.openjproxy.datasource.hikari;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openjproxy.datasource.ConnectionPoolProvider;
import org.openjproxy.datasource.PoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * HikariCP implementation of {@link ConnectionPoolProvider}.
 * This is the default connection pool provider for OJP.
 * 
 * <p>This provider creates and manages connection pools using HikariCP,
 * the high-performance JDBC connection pool. It maps the generic
 * {@link PoolConfig} settings to HikariCP-specific configuration.</p>
 * 
 * <p>The provider is registered via ServiceLoader and is selected by default
 * (highest priority) when no specific provider is requested.</p>
 * 
 * <h2>Configuration Mapping</h2>
 * <ul>
 *   <li>{@code url} → {@code jdbcUrl}</li>
 *   <li>{@code username} → {@code username}</li>
 *   <li>{@code password} → {@code password}</li>
 *   <li>{@code driverClassName} → {@code driverClassName}</li>
 *   <li>{@code maxPoolSize} → {@code maximumPoolSize}</li>
 *   <li>{@code minIdle} → {@code minimumIdle}</li>
 *   <li>{@code connectionTimeoutMs} → {@code connectionTimeout}</li>
 *   <li>{@code idleTimeoutMs} → {@code idleTimeout}</li>
 *   <li>{@code maxLifetimeMs} → {@code maxLifetime}</li>
 *   <li>{@code validationQuery} → {@code connectionTestQuery}</li>
 *   <li>{@code autoCommit} → {@code autoCommit}</li>
 * </ul>
 */
public class HikariConnectionPoolProvider implements ConnectionPoolProvider {

    private static final Logger log = LoggerFactory.getLogger(HikariConnectionPoolProvider.class);
    
    public static final String PROVIDER_ID = "hikari";
    private static final int PRIORITY = 100; // Highest priority - default provider

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public DataSource createDataSource(PoolConfig config) throws SQLException {
        if (config == null) {
            throw new IllegalArgumentException("PoolConfig cannot be null");
        }

        HikariConfig hikariConfig = new HikariConfig();

        // Connection settings
        if (config.getUrl() != null) {
            hikariConfig.setJdbcUrl(config.getUrl());
        }
        if (config.getUsername() != null) {
            hikariConfig.setUsername(config.getUsername());
        }
        String password = config.getPasswordAsString();
        if (password != null) {
            hikariConfig.setPassword(password);
        }
        if (config.getDriverClassName() != null) {
            hikariConfig.setDriverClassName(config.getDriverClassName());
        }

        // Pool sizing
        hikariConfig.setMaximumPoolSize(config.getMaxPoolSize());
        hikariConfig.setMinimumIdle(config.getMinIdle());

        // Timeouts
        hikariConfig.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikariConfig.setIdleTimeout(config.getIdleTimeoutMs());
        hikariConfig.setMaxLifetime(config.getMaxLifetimeMs());

        // Validation
        if (config.getValidationQuery() != null && !config.getValidationQuery().isEmpty()) {
            hikariConfig.setConnectionTestQuery(config.getValidationQuery());
        }

        // Auto-commit
        hikariConfig.setAutoCommit(config.isAutoCommit());

        // Pool name for monitoring
        String poolName = config.getMetricsPrefix() != null 
                ? config.getMetricsPrefix() + "-hikari" 
                : "ojp-hikari-" + System.currentTimeMillis();
        hikariConfig.setPoolName(poolName);

        // Additional HikariCP-specific settings for production use
        hikariConfig.setLeakDetectionThreshold(60000); // 60 seconds
        hikariConfig.setValidationTimeout(5000);       // 5 seconds
        hikariConfig.setInitializationFailTimeout(10000); // 10 seconds
        hikariConfig.setRegisterMbeans(true);

        // Connection properties
        for (Map.Entry<String, String> entry : config.getProperties().entrySet()) {
            hikariConfig.addDataSourceProperty(entry.getKey(), entry.getValue());
        }

        log.info("Creating HikariCP DataSource '{}': url={}, maxPoolSize={}, minIdle={}, connectionTimeout={}ms",
                poolName, config.getUrl(), hikariConfig.getMaximumPoolSize(), 
                hikariConfig.getMinimumIdle(), hikariConfig.getConnectionTimeout());

        try {
            return new HikariDataSource(hikariConfig);
        } catch (Exception e) {
            log.error("Failed to create HikariCP DataSource: {}", e.getMessage(), e);
            throw new SQLException("Failed to create HikariCP DataSource: " + e.getMessage(), e);
        }
    }

    @Override
    public void closeDataSource(DataSource dataSource) throws Exception {
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
            log.info("Closing HikariCP DataSource '{}': active={}, idle={}, total={}", 
                    hikariDataSource.getPoolName(),
                    hikariDataSource.getHikariPoolMXBean() != null ? hikariDataSource.getHikariPoolMXBean().getActiveConnections() : 0,
                    hikariDataSource.getHikariPoolMXBean() != null ? hikariDataSource.getHikariPoolMXBean().getIdleConnections() : 0,
                    hikariDataSource.getHikariPoolMXBean() != null ? hikariDataSource.getHikariPoolMXBean().getTotalConnections() : 0);
            hikariDataSource.close();
        } else if (dataSource != null) {
            log.warn("Cannot close DataSource: not a HikariDataSource instance ({})", 
                    dataSource.getClass().getName());
        }
    }

    @Override
    public Map<String, Object> getStatistics(DataSource dataSource) {
        Map<String, Object> stats = new HashMap<>();
        
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
            
            stats.put("poolName", hikariDataSource.getPoolName());
            stats.put("maxPoolSize", hikariDataSource.getMaximumPoolSize());
            stats.put("minIdle", hikariDataSource.getMinimumIdle());
            stats.put("connectionTimeout", hikariDataSource.getConnectionTimeout());
            stats.put("idleTimeout", hikariDataSource.getIdleTimeout());
            stats.put("maxLifetime", hikariDataSource.getMaxLifetime());
            stats.put("isClosed", hikariDataSource.isClosed());
            
            // Runtime statistics from MXBean
            if (hikariDataSource.getHikariPoolMXBean() != null) {
                stats.put("activeConnections", hikariDataSource.getHikariPoolMXBean().getActiveConnections());
                stats.put("idleConnections", hikariDataSource.getHikariPoolMXBean().getIdleConnections());
                stats.put("totalConnections", hikariDataSource.getHikariPoolMXBean().getTotalConnections());
                stats.put("threadsAwaitingConnection", hikariDataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
            }
        }
        
        return stats;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.zaxxer.hikari.HikariDataSource");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
