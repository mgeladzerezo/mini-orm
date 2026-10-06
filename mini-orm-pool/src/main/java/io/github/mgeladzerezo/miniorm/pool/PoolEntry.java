package io.github.mgeladzerezo.miniorm.pool;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * One physical connection plus the bookkeeping the pool needs for it. An entry is owned by
 * exactly one party at a time: the idle deque, a borrower (through its {@link Lease}), or the
 * thread that is in the middle of creating or destroying it.
 */
final class PoolEntry {

    final Connection physical;
    final long createdAtNanos;
    /** Individual lifetime; slightly shorter than the configured one so a pool does not expire at once. */
    final long lifetimeNanos;

    final boolean defaultAutoCommit;
    final boolean defaultReadOnly;
    final int defaultIsolation;
    final String defaultCatalog;
    final String defaultSchema;

    /** Written by the returning thread, read by the next borrower and the housekeeper. */
    volatile long lastReturnedNanos;
    /** Non-null exactly while the connection is borrowed. */
    volatile Lease lease;

    PoolEntry(Connection physical, long nowNanos, long lifetimeNanos) throws SQLException {
        this.physical = physical;
        this.createdAtNanos = nowNanos;
        this.lastReturnedNanos = nowNanos;
        this.lifetimeNanos = lifetimeNanos;
        if (!physical.getAutoCommit()) {
            physical.setAutoCommit(true);
        }
        this.defaultAutoCommit = true;
        this.defaultReadOnly = physical.isReadOnly();
        this.defaultIsolation = physical.getTransactionIsolation();
        this.defaultCatalog = physical.getCatalog();
        this.defaultSchema = physical.getSchema();
    }

    boolean isExpired(long nowNanos) {
        return lifetimeNanos > 0 && nowNanos - createdAtNanos >= lifetimeNanos;
    }

    /**
     * Who holds the connection. One immutable-ish object behind a single volatile reference so
     * the leak scanner never pairs one borrower's timestamp with another borrower's stack.
     */
    static final class Lease {
        final Thread thread;
        final long sinceNanos;
        /** Call site of {@code getConnection()}; only captured when leak detection is on. */
        final Throwable trace;
        volatile boolean leakReported;

        Lease(Thread thread, long sinceNanos, Throwable trace) {
            this.thread = thread;
            this.sinceNanos = sinceNanos;
            this.trace = trace;
        }
    }
}
