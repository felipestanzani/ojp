package org.openjproxy.grpc.client;

import org.openjproxy.constants.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Configuration for health check and connection redistribution in multinode deployments.
 * Loads settings from ojp.properties.
 */
public class HealthCheckConfig {
    
    private static final Logger log = LoggerFactory.getLogger(HealthCheckConfig.class);
    
    // Default values
    private static final long DEFAULT_HEALTH_CHECK_INTERVAL_MS = 5000L; // 5 seconds
    private static final long DEFAULT_HEALTH_CHECK_THRESHOLD_MS = 5000L; // 5 seconds
    private static final int DEFAULT_HEALTH_CHECK_TIMEOUT_MS = 5000; // 5 seconds
    private static final boolean DEFAULT_REDISTRIBUTION_ENABLED = true;
    private static final double DEFAULT_IDLE_REBALANCE_FRACTION = 1.0;
    private static final int DEFAULT_MAX_CLOSE_PER_RECOVERY = 100;
    private static final boolean DEFAULT_LOAD_AWARE_SELECTION_ENABLED = true;
    
    // Property keys
    private static final String PROP_HEALTH_CHECK_INTERVAL = "ojp.health.check.interval";
    private static final String PROP_HEALTH_CHECK_THRESHOLD = "ojp.health.check.threshold";
    private static final String PROP_HEALTH_CHECK_TIMEOUT = "ojp.health.check.timeout";
    private static final String PROP_REDISTRIBUTION_ENABLED = "ojp.redistribution.enabled";
    private static final String PROP_REDISTRIBUTION_IDLE_FRACTION = "ojp.redistribution.idleRebalanceFraction";
    private static final String PROP_REDISTRIBUTION_MAX_CLOSE = "ojp.redistribution.maxClosePerRecovery";
    private static final String PROP_LOAD_AWARE_SELECTION = "ojp.loadaware.selection.enabled";
    
    private final long healthCheckIntervalMs;
    private final long healthCheckThresholdMs;
    private final int healthCheckTimeoutMs;
    private final boolean redistributionEnabled;
    private final double idleRebalanceFraction;
    private final int maxClosePerRecovery;
    private final boolean loadAwareSelectionEnabled;
    
    private HealthCheckConfig(long healthCheckIntervalMs, long healthCheckThresholdMs,
                            int healthCheckTimeoutMs,
                            boolean redistributionEnabled, double idleRebalanceFraction,
                            int maxClosePerRecovery, boolean loadAwareSelectionEnabled) {
        this.healthCheckIntervalMs = healthCheckIntervalMs;
        this.healthCheckThresholdMs = healthCheckThresholdMs;
        this.healthCheckTimeoutMs = healthCheckTimeoutMs;
        this.redistributionEnabled = redistributionEnabled;
        this.idleRebalanceFraction = idleRebalanceFraction;
        this.maxClosePerRecovery = maxClosePerRecovery;
        this.loadAwareSelectionEnabled = loadAwareSelectionEnabled;
    }
    
    /**
     * Loads health check configuration from properties.
     * 
     * @param props Properties to load from (typically from ojp.properties)
     * @return HealthCheckConfig instance with loaded or default values
     */
    public static HealthCheckConfig loadFromProperties(Properties props) {
        if (props == null) {
            log.debug("No properties provided, using default health check configuration");
            return createDefault();
        }
        
        long interval = getLongProperty(props, PROP_HEALTH_CHECK_INTERVAL, DEFAULT_HEALTH_CHECK_INTERVAL_MS);
        long threshold = getLongProperty(props, PROP_HEALTH_CHECK_THRESHOLD, DEFAULT_HEALTH_CHECK_THRESHOLD_MS);
        int timeout = getIntProperty(props, PROP_HEALTH_CHECK_TIMEOUT, DEFAULT_HEALTH_CHECK_TIMEOUT_MS);
        boolean enabled = getBooleanProperty(props, PROP_REDISTRIBUTION_ENABLED, DEFAULT_REDISTRIBUTION_ENABLED);
        double idleFraction = getDoubleProperty(props, PROP_REDISTRIBUTION_IDLE_FRACTION, DEFAULT_IDLE_REBALANCE_FRACTION);
        int maxClose = getIntProperty(props, PROP_REDISTRIBUTION_MAX_CLOSE, DEFAULT_MAX_CLOSE_PER_RECOVERY);
        boolean loadAware = getBooleanProperty(props, PROP_LOAD_AWARE_SELECTION, DEFAULT_LOAD_AWARE_SELECTION_ENABLED);
        
        log.info("Health check configuration loaded: interval={}ms, threshold={}ms, timeout={}ms, enabled={}, idleFraction={}, maxClose={}, loadAwareSelection={}", 
                interval, threshold, timeout, enabled, idleFraction, maxClose, loadAware);
        
        return new HealthCheckConfig(interval, threshold, timeout, enabled, idleFraction, maxClose, loadAware);
    }
    
    /**
     * Creates a configuration with default values.
     * 
     * @return HealthCheckConfig instance with default values
     */
    public static HealthCheckConfig createDefault() {
        return new HealthCheckConfig(
            DEFAULT_HEALTH_CHECK_INTERVAL_MS,
            DEFAULT_HEALTH_CHECK_THRESHOLD_MS,
            DEFAULT_HEALTH_CHECK_TIMEOUT_MS,
            DEFAULT_REDISTRIBUTION_ENABLED,
            DEFAULT_IDLE_REBALANCE_FRACTION,
            DEFAULT_MAX_CLOSE_PER_RECOVERY,
            DEFAULT_LOAD_AWARE_SELECTION_ENABLED
        );
    }
    
    private static long getLongProperty(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            long longValue = Long.parseLong(value.trim());
            if (longValue < 0) {
                log.warn("Invalid negative value for {}: {}, using default: {}", key, value, defaultValue);
                return defaultValue;
            }
            return longValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    private static int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int intValue = Integer.parseInt(value.trim());
            if (intValue < 0) {
                log.warn("Invalid negative value for {}: {}, using default: {}", key, value, defaultValue);
                return defaultValue;
            }
            return intValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    private static boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
    
    private static double getDoubleProperty(Properties props, String key, double defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            double doubleValue = Double.parseDouble(value.trim());
            if (doubleValue < 0.0 || doubleValue > 1.0) {
                log.warn("Invalid value for {}: {} (must be 0.0-1.0), using default: {}", key, value, defaultValue);
                return defaultValue;
            }
            return doubleValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    public long getHealthCheckIntervalMs() {
        return healthCheckIntervalMs;
    }
    
    public long getHealthCheckThresholdMs() {
        return healthCheckThresholdMs;
    }
    
    public int getHealthCheckTimeoutMs() {
        return healthCheckTimeoutMs;
    }
    
    public boolean isRedistributionEnabled() {
        return redistributionEnabled;
    }

    public double getIdleRebalanceFraction() {
        return idleRebalanceFraction;
    }
    
    public int getMaxClosePerRecovery() {
        return maxClosePerRecovery;
    }
    
    public boolean isLoadAwareSelectionEnabled() {
        return loadAwareSelectionEnabled;
    }
    
    @Override
    public String toString() {
        return "HealthCheckConfig{" +
                "intervalMs=" + healthCheckIntervalMs +
                ", thresholdMs=" + healthCheckThresholdMs +
                ", timeoutMs=" + healthCheckTimeoutMs +
                ", enabled=" + redistributionEnabled +
                ", idleFraction=" + idleRebalanceFraction +
                ", maxClose=" + maxClosePerRecovery +
                ", loadAwareSelection=" + loadAwareSelectionEnabled +
                '}';
    }
}
