package io.github.mgeladzerezo.miniorm.error;

import java.sql.SQLException;

/**
 * A row lock requested with {@code LockMode.FOR_UPDATE_NOWAIT} was not available, or waiting
 * for a lock timed out.
 */
public class PessimisticLockException extends PersistenceException {

    /**
     * @param message what went wrong
     * @param sql     the locking statement
     * @param cause   the driver exception
     */
    public PessimisticLockException(String message, String sql, SQLException cause) {
        super(message, sql, cause);
    }
}
