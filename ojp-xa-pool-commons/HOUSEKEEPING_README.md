# XA Connection Pool Housekeeping Features

This document describes the housekeeping features implemented for the OJP XA connection pool based on Apache Commons Pool 2.

## Overview

The housekeeping implementation adds three production-ready features to detect and prevent common connection pool issues:

1. **Leak Detection** - Identifies connections that are borrowed but not returned
2. **Max Lifetime** - Automatically recycles old connections to prevent staleness
3. **Enhanced Diagnostics** - Provides visibility into pool health and utilization

## Features

### 1. Leak Detection (Enabled by Default)

Tracks borrowed connections and warns when they are held too long.

**Key Characteristics:**
- **Status**: Enabled by default for production safety
- **Overhead**: <0.5% CPU, ~200 bytes per connection
- **Thread**: Shares single daemon thread per pool instance

**Configuration:**
```properties
# Enable/disable leak detection (default: true)
xa.leakDetection.enabled=true

# Timeout before warning (default: 5 minutes)
xa.leakDetection.timeoutMs=300000

# Check interval (default: 1 minute)
xa.leakDetection.intervalMs=60000

# Enhanced mode with stack traces (default: false)
xa.leakDetection.enhanced=false
```

**Example Output:**
```
[LEAK DETECTED] Connection held for too long by thread: worker-thread-5
```

With enhanced mode enabled:
```
[LEAK DETECTED] Connection held for too long by thread: worker-thread-5. Acquisition trace:
	at com.example.MyService.queryDatabase(MyService.java:123)
	at com.example.MyController.handleRequest(MyController.java:45)
	...
```

### 2. Max Lifetime (Passive Enforcement)

Automatically recycles connections after a configured lifetime to prevent stale connections and database-side resource issues.

**Key Characteristics:**
- **Status**: Enabled with 30-minute default lifetime
- **Overhead**: 0% (passive, checked during existing validation)
- **Protection**: **Active connections never recycled** - only idle connections in pool

**Critical Requirement:**
Connections must be **idle for a minimum time** (default 5 minutes) before recycling, even if they've exceeded max lifetime. This prevents recycling connections during long-running transactions.

**Configuration:**
```properties
# Max lifetime for connections (default: 30 minutes, 0=disabled)
xa.maxLifetimeMs=1800000

# Minimum idle time before recycle (default: 5 minutes)
xa.idleBeforeRecycleMs=300000
```

**Example Output:**
```
[MAX LIFETIME] Connection expired after 1850000ms, will be recycled
```

### 3. Enhanced Diagnostics (Opt-In)

Periodically logs comprehensive pool statistics for operational visibility.

**Key Characteristics:**
- **Status**: Disabled by default (opt-in)
- **Overhead**: <0.1% CPU when enabled
- **Thread**: Shares single daemon thread per pool instance

**Configuration:**
```properties
# Enable/disable diagnostics (default: false)
xa.diagnostics.enabled=false

# Log interval (default: 5 minutes)
xa.diagnostics.intervalMs=300000
```

**Example Output:**
```
[XA-POOL-DIAGNOSTICS] Pool State: active=3, idle=7, waiters=0, total=10/20 (15.0% utilized), minIdle=5, maxIdle=20, lifetime: created=15, destroyed=5, borrowed=42, returned=39
```
Note: Log message appears on a single line in actual output.

## Configuration Presets

### Development Configuration
```properties
# More aggressive leak detection for early bug detection
xa.leakDetection.enabled=true
xa.leakDetection.timeoutMs=60000        # 1 minute
xa.leakDetection.enhanced=true          # Stack traces enabled
xa.leakDetection.intervalMs=30000       # Check every 30 seconds

# Shorter lifetime for testing
xa.maxLifetimeMs=600000                 # 10 minutes
xa.idleBeforeRecycleMs=60000            # 1 minute idle

# Diagnostics enabled for visibility
xa.diagnostics.enabled=true
xa.diagnostics.intervalMs=60000         # Every minute
```

### Production Configuration (Default)
```properties
# Leak detection enabled with production-appropriate timeouts
xa.leakDetection.enabled=true
xa.leakDetection.timeoutMs=300000       # 5 minutes
xa.leakDetection.enhanced=false         # Stack traces off
xa.leakDetection.intervalMs=60000       # Check every minute

# Standard lifetime
xa.maxLifetimeMs=1800000                # 30 minutes
xa.idleBeforeRecycleMs=300000           # 5 minutes idle

# Diagnostics disabled (enable if needed)
xa.diagnostics.enabled=false
xa.diagnostics.intervalMs=300000        # 5 minutes
```

### Batch Processing Configuration
```properties
# Lenient leak detection for long-running batch jobs
xa.leakDetection.enabled=true
xa.leakDetection.timeoutMs=600000       # 10 minutes
xa.leakDetection.enhanced=false
xa.leakDetection.intervalMs=120000      # Check every 2 minutes

# Shorter lifetime for high turnover
xa.maxLifetimeMs=900000                 # 15 minutes
xa.idleBeforeRecycleMs=180000           # 3 minutes idle

# Diagnostics for batch monitoring
xa.diagnostics.enabled=true
xa.diagnostics.intervalMs=120000        # Every 2 minutes
```

## Architecture

### Thread Allocation

**One daemon thread per pool instance** - NOT a singleton.

Each `CommonsPool2XADataSource` instance creates:
- ONE `ScheduledExecutorService` (single-threaded, daemon)
- Thread named: `ojp-xa-housekeeping`
- Shared by both leak detection and diagnostics tasks
- Only created if at least one feature is enabled

**Resource Footprint:**
- **No features enabled**: 0 threads, 0 memory
- **Leak detection only**: 1 thread, ~1 MB memory
- **Diagnostics only**: 1 thread, ~1 MB memory
- **Both features**: 1 thread (shared), ~1 MB memory

**Scaling Example:**
```
Application with 3 database pools:
├── PostgreSQL pool → 1 thread → Monitors PostgreSQL sessions only
├── MySQL pool      → 1 thread → Monitors MySQL sessions only
└── Oracle pool     → 1 thread → Monitors Oracle sessions only

Total: 3 independent threads, 3 MB memory
```

### Lifecycle

**Creation:**
- During pool constructor if any feature enabled
- Thread starts immediately in daemon mode

**Active State:**
- Thread sleeps between scheduled task executions
- Wakes up to run task (<100ms execution time)
- Returns to sleep state

**Shutdown:**
- Graceful termination on pool.close()
- 30-second timeout for pending tasks
- Force shutdown if timeout exceeded

**Code:**
```java
if (housekeepingExecutor != null) {
    housekeepingExecutor.shutdown();
    if (!housekeepingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        housekeepingExecutor.shutdownNow();
    }
}
```

## Monitoring and Troubleshooting

### Monitoring Leak Detection

**Normal Operation:**
- No warnings in logs
- All borrowed connections returned promptly

**Potential Issues:**
```
[LEAK DETECTED] Connection held for too long by thread: worker-5
```

**Actions:**
1. Check thread dump for worker-5
2. Review application code for missing connection.close()
3. Check for long-running queries
4. Enable enhanced mode temporarily for stack traces

### Monitoring Max Lifetime

**Normal Operation:**
```
[MAX LIFETIME] Connection expired after 1850000ms, will be recycled
```

**Expected Behavior:**
- Connections recycled after configured lifetime
- No impact on active transactions (idle requirement)

**Potential Issues:**
- Frequent recycling → Lower maxLifetimeMs
- No recycling → Check if connections are staying active

### Monitoring Diagnostics

**Normal Operation:**
```
[XA-POOL-DIAGNOSTICS] Pool State: active=5, idle=15, waiters=0, total=20/50 (40.0% utilized)
```

**Watch For:**
- **High utilization** (>80%): Consider increasing maxTotal
- **Many waiters** (>0): Increase maxTotal or check for slow queries
- **Low utilization** (<10%): Consider decreasing minIdle
- **High created/destroyed ratio**: May indicate connection churn

## Performance Characteristics

| Feature | CPU Overhead | Memory Overhead | Thread Count |
|---------|-------------|-----------------|--------------|
| Leak Detection | <0.5% | ~200 bytes/connection | 1 daemon (shared) |
| Max Lifetime | 0% | ~100 bytes/connection | 0 (passive) |
| Diagnostics | <0.1% | Negligible | 1 daemon (shared) |
| **Total** | **<1%** | **~300 bytes/connection** | **1 daemon** |

**Memory for thread**: ~1 MB per pool instance (Java thread stack)

## Best Practices

### 1. Always Enable Leak Detection
Leak detection should be enabled in all environments (dev, test, prod) to catch connection leaks early.

### 2. Tune Timeouts for Your Workload
- **Fast APIs**: 1-5 minute leak timeout
- **Batch jobs**: 10-30 minute leak timeout
- **Mixed workload**: 5 minute leak timeout (default)

### 3. Set Appropriate Max Lifetime
- **High-volume OLTP**: 15-30 minutes
- **Batch processing**: 5-15 minutes
- **Low-volume apps**: 30-60 minutes

### 4. Use Diagnostics Selectively
- Enable during troubleshooting
- Enable for critical pools in production
- Disable for low-priority pools to reduce log volume

### 5. Monitor Pool Utilization
Use diagnostics to right-size your pool:
- >80% utilization → Increase maxTotal
- <20% utilization → Decrease minIdle
- Waiters >0 → Increase maxTotal immediately

### 6. Handle Leak Warnings Promptly
Leaked connections reduce available pool capacity and can lead to outages. Investigate and fix immediately.

## Troubleshooting Guide

### Issue: Frequent Leak Warnings

**Symptoms:**
```
[LEAK DETECTED] Connection held for too long...
```

**Possible Causes:**
1. Application code not closing connections
2. Long-running queries exceeding timeout
3. Timeout set too low for workload

**Actions:**
1. Enable enhanced mode: `xa.leakDetection.enhanced=true`
2. Review stack traces in logs
3. Check query execution times
4. Increase timeout if appropriate: `xa.leakDetection.timeoutMs=600000`

### Issue: Connections Not Recycling

**Symptoms:**
- No "Connection expired" messages
- Old connections never destroyed

**Possible Causes:**
1. Max lifetime disabled (0)
2. Connections always active (not idle)
3. Idle time requirement not met

**Actions:**
1. Check config: `xa.maxLifetimeMs` must be >0
2. Lower idle requirement: `xa.idleBeforeRecycleMs=60000`
3. Enable diagnostics to see active vs idle ratio

### Issue: Pool Exhaustion (All Connections Busy)

**Symptoms:**
```
[XA-POOL-DIAGNOSTICS] Pool State: active=50, idle=0, waiters=5, total=50/50 (100.0% utilized)
```

**Possible Causes:**
1. Pool too small for workload
2. Connection leaks consuming capacity
3. Slow queries holding connections too long

**Actions:**
1. Check for leak warnings in logs
2. Increase pool size: `maxTotal=100`
3. Enable diagnostics to monitor over time
4. Review slow query log

## Integration with Monitoring Systems

### SLF4J Integration

All housekeeping events are logged via SLF4J at appropriate levels:
- **WARN**: Leak detections
- **INFO**: Max lifetime recycling, diagnostics
- **ERROR**: Housekeeping task failures

Configure your logging framework to route these to monitoring systems:
```xml
<!-- Logback example - adjust package name to match your installation -->
<logger name="org.openjproxy.xa.pool.commons.housekeeping" level="INFO"/>
```

### Metrics Integration (Future)

The housekeeping system is designed to support future metrics integration (Micrometer, Dropwizard, etc.):
- Leak detection count
- Max lifetime recycling count
- Pool utilization histogram
- Borrow/return rates

## Disabling Features

### Disable Leak Detection
```properties
xa.leakDetection.enabled=false
```

**Impact:**
- No leak warnings
- No thread created (if diagnostics also disabled)
- 0% overhead

**When to disable:**
- Never recommended for production
- Only for testing or special cases

### Disable Max Lifetime
```properties
xa.maxLifetimeMs=0
```

**Impact:**
- Connections live indefinitely (until pool eviction)
- May lead to stale connections
- 0% overhead (still passive)

**When to disable:**
- Database doesn't have connection limits
- Application manages connection lifecycle explicitly

### Disable Diagnostics
```properties
xa.diagnostics.enabled=false
```

**Impact:**
- No periodic pool state logging
- Reduces log volume

**When to disable:**
- Production (unless troubleshooting)
- Low-priority pools
- When log volume is a concern

## See Also

- [HOUSEKEEPING_COMPLETE_GUIDE.md](../docs/HOUSEKEEPING_COMPLETE_GUIDE.md) - Complete technical documentation with architecture diagrams
- [Apache Commons Pool 2 Documentation](https://commons.apache.org/proper/commons-pool/api-2.11.1/index.html)
- [Agroal Project](https://github.com/agroal/agroal) - Database connection pooling library that inspired the housekeeping design

## Support

For issues or questions about housekeeping features:
1. Check this README and the complete guide
2. Review logs for specific error messages
3. Open an issue on GitHub with:
   - Configuration settings
   - Relevant log output
   - Pool statistics from diagnostics
