package io.github.mgeladzerezo.miniorm.pool;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens physical connections for the pool. The pool calls this from borrower threads and from
 * its housekeeping thread, so implementations must be thread-safe.
 */
@FunctionalInterface
public interface ConnectionFactory {

    /**
     * Opens a new physical connection.
     *
     * @return a connection that is not shared with anyone else
     * @throws SQLException if the database refuses or cannot be reached
     */
    Connection create() throws SQLException;
}
