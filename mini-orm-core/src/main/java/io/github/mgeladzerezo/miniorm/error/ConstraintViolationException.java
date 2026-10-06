package io.github.mgeladzerezo.miniorm.error;

import java.sql.SQLException;

/**
 * The database rejected a statement because of an integrity constraint (SQLState class 23):
 * unique, foreign key, not-null or check.
 */
public class ConstraintViolationException extends PersistenceException {

    /**
     * @param message what went wrong
     * @param sql     the statement that failed
     * @param cause   the driver exception
     */
    public ConstraintViolationException(String message, String sql, SQLException cause) {
        super(message, sql, cause);
    }
}
