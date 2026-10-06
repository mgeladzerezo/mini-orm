package io.github.mgeladzerezo.miniorm;

/** Pessimistic row locks that can be requested when reading. */
public enum LockMode {
    /** Plain read. */
    NONE,
    /** {@code SELECT ... FOR UPDATE}: blocks until the row lock is granted. Needs a transaction. */
    FOR_UPDATE,
    /**
     * {@code SELECT ... FOR UPDATE NOWAIT}: fails immediately with
     * {@link io.github.mgeladzerezo.miniorm.error.PessimisticLockException} if another
     * transaction holds the lock.
     */
    FOR_UPDATE_NOWAIT
}
