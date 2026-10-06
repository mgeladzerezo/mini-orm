package io.github.mgeladzerezo.miniorm;

import java.util.function.Supplier;

/**
 * Handle on the transaction a {@link Session} is running, passed to the callback of
 * {@link Session#inTransaction}. Commit and rollback are not exposed: the transaction commits
 * when the outermost callback returns and rolls back when it throws.
 */
public final class Transaction {

    /** Opaque marker for a point inside a transaction that can be rolled back to. */
    public static final class Savepoint {
        private final java.sql.Savepoint jdbc;

        Savepoint(java.sql.Savepoint jdbc) {
            this.jdbc = jdbc;
        }

        java.sql.Savepoint jdbc() {
            return jdbc;
        }
    }

    private final Session session;

    Transaction(Session session) {
        this.session = session;
    }

    /**
     * Flushes pending changes and creates a savepoint, so that everything written before it is
     * kept if a later part is rolled back.
     *
     * @return the savepoint
     */
    public Savepoint savepoint() {
        return session.createSavepoint();
    }

    /**
     * Undoes everything done since the savepoint. The session's persistence context cannot be
     * rewound, so it is cleared: every entity the session knew becomes detached and must be
     * loaded again.
     *
     * @param savepoint a savepoint of this transaction
     */
    public void rollbackTo(Savepoint savepoint) {
        session.rollbackToSavepoint(savepoint);
    }

    /**
     * Discards a savepoint that is no longer needed.
     *
     * @param savepoint a savepoint of this transaction
     */
    public void release(Savepoint savepoint) {
        session.releaseSavepoint(savepoint);
    }

    /**
     * Runs {@code work} inside a savepoint. If it throws, its changes are rolled back to the
     * savepoint and the exception is rethrown, so the caller may catch it and carry on with the
     * enclosing transaction (unlike a nested {@code inTransaction}, which poisons it).
     *
     * @param work the nested unit of work
     * @param <R>  result type
     * @return what {@code work} returned
     */
    public <R> R inSavepoint(Supplier<? extends R> work) {
        Savepoint savepoint = savepoint();
        try {
            R result = work.get();
            release(savepoint);
            return result;
        } catch (RuntimeException | Error e) {
            rollbackTo(savepoint);
            throw e;
        }
    }

    /** Makes the transaction roll back when the outermost callback returns, even without an exception. */
    public void setRollbackOnly() {
        session.markRollbackOnly();
    }

    /**
     * @return whether the transaction can no longer commit
     */
    public boolean isRollbackOnly() {
        return session.isRollbackOnly();
    }
}
