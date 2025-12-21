package org.openjproxy.xa.pool;

import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for managing XA transaction contexts and their lifecycle.
 * <p>
 * This class orchestrates XA transaction state transitions, manages the binding of
 * Xids to backend sessions, and delegates durability to the backend database's
 * native XA implementation.
 * </p>
 * <p>
 * Thread-safe: All operations are synchronized appropriately. Multiple threads can
 * call XA methods concurrently for different Xids.
 * </p>
 *
 * <h3>Responsibilities:</h3>
 * <ul>
 *   <li>Maintain in-memory map of XidKey → TxContext</li>
 *   <li>Enforce XA state machine transitions</li>
 *   <li>Borrow/return backend sessions from pool</li>
 *   <li>Delegate prepare/commit/rollback to backend XAResource</li>
 *   <li>Handle recovery by querying backend XAResource.recover()</li>
 * </ul>
 *
 * <h3>Invariants:</h3>
 * <ul>
 *   <li>I1: One backend session is bound to at most one Xid at a time</li>
 *   <li>I2: All SQL for a given Xid routes to its bound session</li>
 *   <li>I3: A session is returned to pool only after COMPLETED (commit/rollback)</li>
 *   <li>I4: After prepare(Xid)=XA_OK, recover() returns Xid until completion</li>
 *   <li>I5: commit/rollback are idempotent</li>
 * </ul>
 */
public class XATransactionRegistry {
    private static final Logger log = LoggerFactory.getLogger(XATransactionRegistry.class);
    
    private final ConcurrentMap<XidKey, TxContext> contexts = new ConcurrentHashMap<>();
    private final XAConnectionPoolProvider poolProvider;
    private final Object poolDataSource; // XADataSource instance from provider
    
    /**
     * Creates a new XA transaction registry.
     *
     * @param poolProvider the XA connection pool provider
     * @param poolDataSource the XADataSource instance from the provider
     */
    public XATransactionRegistry(XAConnectionPoolProvider poolProvider, Object poolDataSource) {
        this.poolProvider = poolProvider;
        this.poolDataSource = poolDataSource;
    }
    
    /**
     * Gets the pooled XA DataSource managed by this registry.
     *
     * @return the pooled XA DataSource instance
     */
    public Object getPooledXADataSource() {
        return poolDataSource;
    }
    
    /**
     * Registers an existing BackendSession with a new XA transaction.
     * <p>
     * Used when BackendSession is allocated eagerly (during connect) rather than 
     * lazily (during xaStart). This avoids double allocation from the pool.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @param session the existing BackendSession to register
     * @param flags XA start flags (must be TMNOFLAGS for new transaction)
     * @throws XAException if flags are invalid, xid already exists, or XA start fails
     */
    public void registerExistingSession(XidKey xid, BackendSession session, int flags) throws XAException {
        log.debug("registerExistingSession: xid={}, flags={}", xid, flagsToString(flags));
        
        // Validate flags - only TMNOFLAGS allowed for new transaction
        if (flags != XAResource.TMNOFLAGS) {
            throw new XAException(XAException.XAER_INVAL);
        }
        
        // Create context and ensure no duplicate
        TxContext existing = contexts.putIfAbsent(xid, new TxContext(xid));
        if (existing != null) {
            throw new XAException(XAException.XAER_DUPID);
        }
        
        TxContext ctx = contexts.get(xid);
        try {
            // Register the existing session
            ctx.transitionToActive(session);
            
            // Create Xid object once and store it for reuse
            javax.transaction.xa.Xid actualXid = xid.toXid();
            ctx.setActualXid(actualXid);
            
            // Call XAResource.start on backend with the stored Xid
            session.getXAResource().start(actualXid, flags);
            
            log.info("XA transaction registered with existing session: xid={}", xid);
        } catch (XAException e) {
            contexts.remove(xid);
            throw e;
        } catch (Exception e) {
            contexts.remove(xid);
            throw new XAException(XAException.XAER_RMERR);
        }
    }
    
    /**
     * Starts an XA transaction branch.
     * <p>
     * For TMNOFLAGS: Creates new TxContext and borrows a backend session.<br>
     * For TMJOIN: Reuses existing TxContext and session.<br>
     * For TMRESUME: Resumes suspended TxContext and session.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @param flags XA start flags (TMNOFLAGS, TMJOIN, TMRESUME)
     * @throws XAException if state transition is invalid or session cannot be borrowed
     */
    public void xaStart(XidKey xid, int flags) throws XAException {
        log.debug("xaStart: xid={}, flags={}", xid, flagsToString(flags));
        
        boolean isTmNoFlags = (flags == XAResource.TMNOFLAGS);
        boolean isTmJoin = (flags == XAResource.TMJOIN);
        boolean isTmResume = (flags == XAResource.TMRESUME);
        
        if (isTmNoFlags) {
            // New transaction branch - create context and borrow session
            TxContext existing = contexts.putIfAbsent(xid, new TxContext(xid));
            if (existing != null) {
                throw new XAException(XAException.XAER_DUPID);
            }
            
            TxContext ctx = contexts.get(xid);
            try {
                BackendSession session = poolProvider.borrowSession(poolDataSource);
                ctx.transitionToActive(session);
                
                // Create Xid object once and store it for reuse
                javax.transaction.xa.Xid actualXid = xid.toXid();
                ctx.setActualXid(actualXid);
                
                // Call XAResource.start on backend with the stored Xid
                session.getXAResource().start(actualXid, flags);
                
                log.info("XA transaction started: xid={}", xid);
            } catch (Exception e) {
                contexts.remove(xid);
                throw new XAException(XAException.XAER_RMERR);
            }
            
        } else if (isTmJoin || isTmResume) {
            // Join or resume existing transaction branch
            TxContext ctx = contexts.get(xid);
            if (ctx == null) {
                throw new XAException(XAException.XAER_NOTA);
            }
            
            try {
                ctx.transitionToActiveFromEnded(isTmJoin);
                
                // Reuse the same Xid object that was used in original start()
                javax.transaction.xa.Xid actualXid = ctx.getActualXid();
                if (actualXid == null) {
                    throw new XAException(XAException.XAER_PROTO);
                }
                
                // Call XAResource.start on backend with the stored Xid
                ctx.getSession().getXAResource().start(actualXid, flags);
                
                log.debug("XA transaction {} resumed: xid={}", isTmJoin ? "joined" : "resumed", xid);
            } catch (IllegalStateException e) {
                throw new XAException(XAException.XAER_PROTO);
            }
            
        } else {
            throw new XAException(XAException.XAER_INVAL);
        }
    }
    
    /**
     * Ends an XA transaction branch.
     * <p>
     * For TMSUCCESS/TMFAIL: Disassociates session from thread, keeps it bound.<br>
     * For TMSUSPEND: Suspends transaction, session remains bound for later TMRESUME.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @param flags XA end flags (TMSUCCESS, TMFAIL, TMSUSPEND)
     * @throws XAException if state transition is invalid
     */
    public void xaEnd(XidKey xid, int flags) throws XAException {
        log.debug("xaEnd: xid={}, flags={}", xid, flagsToString(flags));
        
        TxContext ctx = contexts.get(xid);
        if (ctx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        
        boolean isTmSuccess = (flags == XAResource.TMSUCCESS);
        boolean isTmFail = (flags == XAResource.TMFAIL);
        boolean isTmSuspend = (flags == XAResource.TMSUSPEND);
        
        if (!isTmSuccess && !isTmFail && !isTmSuspend) {
            throw new XAException(XAException.XAER_INVAL);
        }
        
        try {
            // Reuse the same Xid object that was used in start()
            javax.transaction.xa.Xid actualXid = ctx.getActualXid();
            if (actualXid == null) {
                throw new XAException(XAException.XAER_PROTO);
            }
            
            // Call XAResource.end on backend with the stored Xid
            ctx.getSession().getXAResource().end(actualXid, flags);
            
            ctx.transitionToEnded(isTmSuspend);
            
            log.debug("XA transaction ended: xid={}, flags={}", xid, flagsToString(flags));
        } catch (IllegalStateException e) {
            throw new XAException(XAException.XAER_PROTO);
        }
    }
    
    /**
     * Prepares an XA transaction branch for commit (phase 1 of 2PC).
     * <p>
     * Delegates to backend XAResource.prepare(). If prepare returns XA_RDONLY,
     * the transaction is optimized away (no second phase needed).
     * </p>
     *
     * @param xid the transaction branch identifier
     * @return XA_OK if prepared successfully, XA_RDONLY if read-only optimization applies
     * @throws XAException if state transition is invalid or prepare fails
     */
    public int xaPrepare(XidKey xid) throws XAException {
        log.debug("xaPrepare: xid={}", xid);
        
        TxContext ctx = contexts.get(xid);
        if (ctx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        
        try {
            // Reuse the same Xid object that was used in start()
            javax.transaction.xa.Xid actualXid = ctx.getActualXid();
            if (actualXid == null) {
                throw new XAException(XAException.XAER_PROTO);
            }
            
            // Call XAResource.prepare on backend
            int result = ctx.getSession().getXAResource().prepare(actualXid);
            
            if (result == XAResource.XA_RDONLY) {
                // Read-only optimization: no prepare needed, transaction can complete immediately
                ctx.transitionToCommitted();
                returnSessionToPool(ctx);
                contexts.remove(xid);
                
                log.info("XA transaction prepared (read-only optimization): xid={}", xid);
                return XAResource.XA_RDONLY;
            } else {
                // Normal 2PC: transition to PREPARED state
                ctx.transitionToPrepared();
                
                log.info("XA transaction prepared: xid={}", xid);
                return XAResource.XA_OK;
            }
        } catch (IllegalStateException e) {
            throw new XAException(XAException.XAER_PROTO);
        }
    }
    
    /**
     * Commits an XA transaction branch.
     * <p>
     * For onePhase=true: 1PC optimization (combine prepare + commit).<br>
     * For onePhase=false: Phase 2 of 2PC (after successful prepare).
     * </p>
     * <p>
     * Idempotent: If already committed, returns success.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @param onePhase true for 1PC optimization, false for 2PC
     * @throws XAException if state transition is invalid or commit fails
     */
    public void xaCommit(XidKey xid, boolean onePhase) throws XAException {
        log.debug("xaCommit: xid={}, onePhase={}", xid, onePhase);
        
        TxContext ctx = contexts.get(xid);
        if (ctx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        
        // Idempotency: if already committed, succeed silently
        if (ctx.getState() == TxState.COMMITTED) {
            log.debug("XA transaction already committed (idempotent): xid={}", xid);
            return;
        }
        
        try {
            // Reuse the same Xid object that was used in start()
            javax.transaction.xa.Xid actualXid = ctx.getActualXid();
            if (actualXid == null) {
                throw new XAException(XAException.XAER_PROTO);
            }
            
            // Call XAResource.commit on backend
            ctx.getSession().getXAResource().commit(actualXid, onePhase);
            
            ctx.transitionToCommitted();
            returnSessionToPool(ctx);
            contexts.remove(xid);
            
            log.info("XA transaction committed: xid={}, onePhase={}", xid, onePhase);
        } catch (IllegalStateException e) {
            throw new XAException(XAException.XAER_PROTO);
        }
    }
    
    /**
     * Rolls back an XA transaction branch.
     * <p>
     * Can be called from ACTIVE, ENDED, or PREPARED state.
     * Idempotent: If already rolled back, returns success.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @throws XAException if rollback fails
     */
    public void xaRollback(XidKey xid) throws XAException {
        log.debug("xaRollback: xid={}", xid);
        
        TxContext ctx = contexts.get(xid);
        if (ctx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        
        // Idempotency: if already rolled back, succeed silently
        if (ctx.getState() == TxState.ROLLEDBACK) {
            log.debug("XA transaction already rolled back (idempotent): xid={}", xid);
            return;
        }
        
        try {
            // Reuse the same Xid object that was used in start()
            javax.transaction.xa.Xid actualXid = ctx.getActualXid();
            if (actualXid == null) {
                throw new XAException(XAException.XAER_PROTO);
            }
            
            // Call XAResource.rollback on backend
            ctx.getSession().getXAResource().rollback(actualXid);
            
            ctx.transitionToRolledBack();
            returnSessionToPool(ctx);
            contexts.remove(xid);
            
            log.info("XA transaction rolled back: xid={}", xid);
        } catch (Exception e) {
            log.error("Error during rollback for xid=" + xid, e);
            // Best effort: still clean up context and return session
            try {
                returnSessionToPool(ctx);
            } catch (Exception e2) {
                log.error("Failed to return session to pool after rollback error", e2);
            }
            contexts.remove(xid);
            throw new XAException(XAException.XAER_RMERR);
        }
    }
    
    /**
     * Recovers prepared XA transaction branches from the backend database.
     * <p>
     * Delegates to backend XAResource.recover(). This queries the database's
     * transaction log for transactions in PREPARED state.
     * </p>
     * <p>
     * For multinode deployments: Each proxy node calls recover() on its backend
     * sessions. The Transaction Manager broadcasts recovery to all nodes and
     * completes transactions based on its own log.
     * </p>
     *
     * @param flag XA recover flags (TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS)
     * @return list of Xids in PREPARED state
     * @throws XAException if recovery fails
     */
    public List<XidKey> xaRecover(int flag) throws XAException {
        log.debug("xaRecover: flag={}", flag);
        
        List<XidKey> preparedXids = new ArrayList<>();
        
        // Delegate to backend XAResource.recover()
        // In a real implementation, we'd need a backend session to call recover() on
        BackendSession session = null;
        try {
            session = poolProvider.borrowSession(poolDataSource);
            javax.transaction.xa.Xid[] xids = session.getXAResource().recover(flag);
            
            if (xids != null) {
                for (javax.transaction.xa.Xid xid : xids) {
                    preparedXids.add(XidKey.from(xid));
                }
            }
            
            log.info("XA recover returned {} prepared transactions", preparedXids.size());
            return preparedXids;
            
        } catch (Exception e) {
            log.error("Failed to recover prepared transactions", e);
            throw new XAException(XAException.XAER_RMERR);
        } finally {
            if (session != null) {
                try {
                    poolProvider.returnSession(poolDataSource, session);
                } catch (Exception e) {
                    log.error("Failed to return session after recover()", e);
                }
            }
        }
    }
    
    /**
     * Forgets a heuristically completed XA transaction branch.
     * <p>
     * Currently not implemented. Most databases don't require explicit forget.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @throws XAException with XAER_NOTA if not supported
     */
    public void xaForget(XidKey xid) throws XAException {
        log.debug("xaForget: xid={} (not implemented)", xid);
        throw new XAException(XAException.XAER_NOTA);
    }
    
    /**
     * Returns the transaction context for a given Xid.
     * Used by SQL execution path to route queries to the correct backend session.
     *
     * @param xid the transaction branch identifier
     * @return the transaction context, or null if not found
     */
    public TxContext getContext(XidKey xid) {
        return contexts.get(xid);
    }
    
    /**
     * Returns the backend session associated with a transaction.
     * <p>
     * This is called by SQL execution handlers to route queries to the correct
     * backend session for the active transaction.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @return the backend session, or null if transaction not found or not in valid state
     */
    public BackendSession getSessionForTransaction(XidKey xid) {
        TxContext ctx = contexts.get(xid);
        if (ctx != null && ctx.getState().canPerformWork()) {
            return ctx.getSession();
        }
        return null;
    }
    
    /**
     * Closes the registry and releases all resources.
     * <p>
     * Should be called during proxy shutdown. Logs warnings for any active transactions.
     * </p>
     */
    public void close() {
        if (!contexts.isEmpty()) {
            log.warn("Closing XATransactionRegistry with {} active transactions", contexts.size());
            for (XidKey xid : contexts.keySet()) {
                log.warn("Active transaction at shutdown: xid={}", xid);
            }
        }
        contexts.clear();
    }
    
    // Private helper methods
    
    private void returnSessionToPool(TxContext ctx) {
        BackendSession session = ctx.getSession();
        if (session != null) {
            try {
                poolProvider.returnSession(poolDataSource, session);
            } catch (Exception e) {
                log.error("Failed to return session to pool", e);
                // Best effort: try to invalidate
                try {
                    poolProvider.invalidateSession(poolDataSource, session);
                } catch (Exception e2) {
                    log.error("Failed to invalidate session", e2);
                }
            }
        }
    }
    
    private static String flagsToString(int flags) {
        switch (flags) {
            case XAResource.TMNOFLAGS: return "TMNOFLAGS";
            case XAResource.TMJOIN: return "TMJOIN";
            case XAResource.TMRESUME: return "TMRESUME";
            case XAResource.TMSUCCESS: return "TMSUCCESS";
            case XAResource.TMFAIL: return "TMFAIL";
            case XAResource.TMSUSPEND: return "TMSUSPEND";
            case XAResource.TMSTARTRSCAN: return "TMSTARTRSCAN";
            case XAResource.TMENDRSCAN: return "TMENDRSCAN";
            default: return "UNKNOWN(" + flags + ")";
        }
    }
}
