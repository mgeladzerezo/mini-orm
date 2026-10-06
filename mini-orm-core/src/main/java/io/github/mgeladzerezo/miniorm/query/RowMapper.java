package io.github.mgeladzerezo.miniorm.query;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps the current row of a native query's result set to an object.
 *
 * @param <R> the result type
 */
@FunctionalInterface
public interface RowMapper<R> {

    /**
     * Reads one row. Must not call {@code next()}.
     *
     * @param rs result set positioned on the row
     * @return the mapped object
     * @throws SQLException if a column cannot be read
     */
    R map(ResultSet rs) throws SQLException;
}
