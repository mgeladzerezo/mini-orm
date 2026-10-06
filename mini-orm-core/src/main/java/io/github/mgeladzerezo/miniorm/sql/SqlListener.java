package io.github.mgeladzerezo.miniorm.sql;

/**
 * Observes every statement a session factory executes. Listeners are called on the thread
 * that ran the statement, after it finished (or failed), and must be thread-safe because
 * sessions on different threads share them.
 */
@FunctionalInterface
public interface SqlListener {

    /**
     * Called once per JDBC round trip (a batch is one event).
     *
     * @param event what was executed and how it went
     */
    void onStatement(SqlEvent event);
}
