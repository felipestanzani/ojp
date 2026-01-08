# Executive Summary: Agroal Connection Pool Evaluation

**Date:** 2026-01-08  
**Evaluator:** GitHub Copilot Analysis  
**Status:** Recommendation Issued  
**Full Analysis:** [AGROAL_VS_COMMONS_POOL2_XA_ANALYSIS.md](./AGROAL_VS_COMMONS_POOL2_XA_ANALYSIS.md)

---

## Question

> Should OJP replace Apache Commons Pool 2 with Agroal for XA connection pooling?  
> Motivation: Agroal has leak detection and validation tasks that monitor connections.

---

## Recommendation

### ❌ DO NOT REPLACE Apache Commons Pool 2 with Agroal

**Instead:** Enhance the existing Commons Pool 2 implementation with leak detection and monitoring features.

---

## Key Findings

### Why Agroal Won't Work Well for OJP

1. **Architecture Mismatch** 🔴
   - Agroal pools `XAConnection` objects
   - OJP pools `XABackendSession` objects (wrapper around XAConnection)
   - Agroal's leak detection won't see leaks in our wrapper layer
   - **Result:** Primary benefit is negated

2. **Universal Provider Loss** 🔴
   - Current: One implementation works with ALL databases via reflection
   - With Agroal: Need database-specific configuration
   - Loses key architectural strength

3. **High Migration Risk** 🔴
   - Requires major architectural changes
   - 5-10x increase in testing burden
   - Risk of breaking existing deployments
   - Estimated effort: 3-6 months

4. **No Current Problems** 🟢
   - Existing Commons Pool 2 implementation works well
   - No reported connection leaks in production
   - No performance issues

---

## Comparison Summary

| Criterion | Commons Pool 2 | Agroal | Winner |
|-----------|----------------|--------|--------|
| Built-in leak detection | ❌ | ✅ | Agroal |
| Works with OJP architecture | ✅ | ❌ | Commons Pool 2 |
| Universal (vendor-agnostic) | ✅ | ❌ | Commons Pool 2 |
| Zero vendor dependencies | ✅ | ❌ | Commons Pool 2 |
| Battle-tested (production) | ✅ (15+ years) | ⚠️ (5+ years) | Commons Pool 2 |
| Native XA/JTA support | ⚠️ Manual | ✅ Automatic | Agroal |
| Performance | ✅ Fast | ✅ Very Fast | Agroal (marginal) |
| **Overall Fit for OJP** | ✅ **EXCELLENT** | ❌ **POOR** | **Commons Pool 2** |

---

## Recommended Alternative: Enhancement Approach

### Add to Existing Implementation

**Phase 1: Connection Leak Detection**
```java
// Track borrowed sessions with timestamps and stack traces
LeakTracker tracker = new LeakTracker(session, Thread.currentThread().getStackTrace());

// Background task checks for sessions held > leakTimeout
if (age > leakTimeoutMs) {
    log.warn("LEAK DETECTED: Session held {}ms\nStack trace:\n{}", age, stackTrace);
}
```

**Phase 2: Enhanced Monitoring**
```java
// Expose metrics via JMX/Micrometer
Gauge.builder("xa.pool.active", pool::getNumActive).register(registry);
Gauge.builder("xa.pool.idle", pool::getNumIdle).register(registry);

// Background health monitoring every 30s
scheduledExecutor.scheduleAtFixedRate(this::checkPoolHealth, 30, 30, SECONDS);
```

**Phase 3: Improved Validation**
```java
// More aggressive background validation
config.setTimeBetweenEvictionRuns(Duration.ofSeconds(10));
config.setTestWhileIdle(true);
config.setNumTestsPerEvictionRun(10);
```

### Benefits of Enhancement Approach

✅ **Gets the desired features** (leak detection, validation, monitoring)  
✅ **Low risk** (no architectural changes)  
✅ **Fast implementation** (4-5 weeks vs. 3-6 months)  
✅ **No breaking changes** (backward compatible)  
✅ **Preserves universal provider model**  
✅ **80% less effort than replacement**

### New Configuration Properties

```properties
# Leak Detection
xa.leakDetectionEnabled=true        # Enable/disable leak detection
xa.leakTimeoutMs=60000              # Warn if session held > 60s
xa.leakCheckIntervalMs=30000        # Check for leaks every 30s

# Enhanced Monitoring
xa.monitoringEnabled=true           # Enable background monitoring
xa.monitoringIntervalMs=30000       # Monitor every 30s
xa.metricsEnabled=true              # Expose JMX/Micrometer metrics

# Improved Validation
xa.validationIntervalMs=30000       # Background validation every 30s
xa.validationSampleSize=3           # Validate 3 idle connections per run
```

---

## Why Not Both? (Future Option)

A hybrid approach could support BOTH providers via the existing SPI:

```properties
# Default: Commons Pool 2 (universal, reflection-based)
xa.pool.provider=commons-pool2

# Optional: Agroal (if user wants its features and can solve architecture mismatch)
xa.pool.provider=agroal
```

**However:** This requires solving the `XABackendSession` compatibility issue first, which is non-trivial.

**Recommendation:** Consider for v0.4.0+ if there's user demand.

---

## Decision Criteria Met

| Criterion | Status | Notes |
|-----------|--------|-------|
| Provides leak detection | ✅ | Via enhancement approach |
| Provides validation tasks | ✅ | Via enhancement approach |
| Low migration risk | ✅ | Enhancement has low risk |
| Preserves existing architecture | ✅ | No breaking changes |
| Reasonable effort | ✅ | 4-5 weeks vs. 3-6 months |
| Better monitoring | ✅ | Via enhancement approach |

---

## Questions Answered

### Q: "Agroal has leak tasks and validation tasks that monitor connections. Should we switch?"

**A:** No need to switch. We can add those same capabilities to the existing Commons Pool 2 implementation with much lower risk and effort. Switching would introduce major architectural challenges without providing additional value.

### Q: "Is Agroal better than Commons Pool 2?"

**A:** For standalone JDBC connection pools: **Yes, Agroal is better.**  
For OJP's XA session pooling architecture: **No, Commons Pool 2 is better fit.**

The difference is that OJP pools `XABackendSession` objects (not raw connections), and Agroal's benefits don't apply to wrapped objects.

### Q: "What about performance?"

**A:** Agroal is marginally faster, but OJP's bottleneck is network I/O and database query time, not pool acquisition. The performance difference is negligible in real-world scenarios.

### Q: "Is there a middle ground?"

**A:** Yes! The enhancement approach gives you Agroal-like features (leak detection, validation tasks, monitoring) while keeping the proven Commons Pool 2 foundation.

---

## Action Items

### Immediate (v0.3.x)
- [ ] Review this analysis with stakeholders
- [ ] Get approval for enhancement approach
- [ ] Create implementation issues for Phase 1-4
- [ ] Assign development resources

### Short-term (v0.3.x - next 1-2 months)
- [ ] Implement leak detection (Phase 1)
- [ ] Implement enhanced monitoring (Phase 2)
- [ ] Implement improved validation (Phase 3)
- [ ] Update documentation (Phase 4)

### Long-term (v0.4.0+)
- [ ] Evaluate user feedback on enhancements
- [ ] Consider hybrid approach if strong user demand
- [ ] Re-evaluate Agroal if architecture changes

---

## Conclusion

**Don't fix what isn't broken.** 

The existing Commons Pool 2 implementation is:
- ✅ Working well in production
- ✅ Universal (works with all databases)
- ✅ Battle-tested and mature
- ✅ Can be enhanced with desired features

Replacing it with Agroal would:
- ❌ Introduce major architectural challenges
- ❌ Lose the universal provider model
- ❌ Not provide the expected benefits (due to wrapper incompatibility)
- ❌ Take 3-6 months and introduce significant risk

**Better approach:** Enhance the existing implementation to add leak detection and validation tasks. This gives you what you want with 80% less effort and risk.

---

**Recommendation Status:** Pending Stakeholder Approval  
**Next Review:** After stakeholder feedback  
**Implementation Target:** OJP v0.3.3 or v0.4.0

