package io.github.mgeladzerezo.miniorm.pool;

import java.time.Duration;

/**
 * Callbacks for pool events. All methods are invoked synchronously on pool threads (a borrower
 * thread or the housekeeper), so implementations must be fast and must not throw.
 */
public interface PoolListener {

    /** A listener that ignores every event. */
    PoolListener NONE = new PoolListener() { };

    /**
     * A connection has been held longer than {@link PoolConfig#leakDetectionThreshold()}.
     *
     * @param leak who took it, for how long, and from where
     */
    default void onLeakDetected(Leak leak) {
    }

    /** A physical connection was opened. */
    default void onConnectionCreated() {
    }

    /**
     * A physical connection was closed.
     *
     * @param reason why the pool closed it
     */
    default void onConnectionClosed(EvictionReason reason) {
    }

    /**
     * Describes a suspected connection leak.
     *
     * @param poolName   name of the reporting pool
     * @param threadName name of the thread that borrowed the connection
     * @param heldFor    how long the connection had been out when the report was made
     * @param borrowedAt a throwable whose stack trace is the call site of {@code getConnection()}
     */
    record Leak(String poolName, String threadName, Duration heldFor, Throwable borrowedAt) {
    }
}
