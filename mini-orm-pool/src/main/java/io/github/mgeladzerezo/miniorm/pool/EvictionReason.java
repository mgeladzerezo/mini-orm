package io.github.mgeladzerezo.miniorm.pool;

/** Why the pool closed a physical connection. Reported in {@link PoolMetrics} and to listeners. */
public enum EvictionReason {
    /** The connection reached {@link PoolConfig#maxLifetime()}. */
    MAX_LIFETIME,
    /** The connection sat idle longer than {@link PoolConfig#idleTimeout()} while above min idle. */
    IDLE_TIMEOUT,
    /** {@code Connection.isValid} failed when the connection was about to be handed out. */
    VALIDATION_FAILED,
    /** A statement or the connection threw an {@code SQLException} with a fatal SQLState. */
    BROKEN,
    /** Rolling back or restoring session state on return failed. */
    RESET_FAILED,
    /** The pool was shut down. */
    POOL_CLOSED
}
