package io.github.mgeladzerezo.miniorm.error;

import java.sql.SQLException;

/**
 * A statement failed. Carries the SQL text and the driver's {@link SQLException} as cause.
 */
public class PersistenceException extends OrmException {

    private final String sql;

    /**
     * @param message what went wrong
     * @param sql     the statement that failed, or {@code null} if no statement was involved
     * @param cause   the driver exception
     */
    public PersistenceException(String message, String sql, SQLException cause) {
        super(message, cause);
        this.sql = sql;
    }

    /**
     * The SQL text of the failed statement.
     *
     * @return the SQL, or {@code null}
     */
    public String sql() {
        return sql;
    }

    /**
     * The driver's SQLState.
     *
     * @return the five-character state, or {@code null} if the driver gave none
     */
    public String sqlState() {
        return getCause() instanceof SQLException e ? e.getSQLState() : null;
    }
}
