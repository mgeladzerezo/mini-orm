package io.github.mgeladzerezo.miniorm.pool;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.util.Set;

/**
 * Decides whether an {@link SQLException} means the physical connection is unusable.
 *
 * <p>SQLState class {@code 08} is "connection exception" in the SQL standard. The remaining
 * entries are vendor states that mean the same thing: PostgreSQL's {@code 57P01..57P03}
 * (admin shutdown, crash shutdown, cannot connect now - what a client sees after
 * {@code pg_terminate_backend}), and H2's {@code 90067} / {@code 90098} (connection broken,
 * database closed). Constraint violations, deadlocks and syntax errors are deliberately not
 * here: the connection survives those.
 */
final class FatalSqlStates {

    private static final Set<String> STATES = Set.of(
            "57P01", "57P02", "57P03", "01002", "JZ0C0", "JZ0C1", "90067", "90098");

    /** Bound on the walk through chained exceptions, in case a driver builds a cycle. */
    private static final int MAX_CHAIN = 16;

    private FatalSqlStates() {
    }

    static boolean isFatal(SQLException e, Set<String> extra) {
        SQLException current = e;
        for (int i = 0; current != null && i < MAX_CHAIN; i++) {
            if (current instanceof SQLNonTransientConnectionException
                    || current instanceof SQLRecoverableException) {
                return true;
            }
            String state = current.getSQLState();
            if (state != null && (state.startsWith("08") || STATES.contains(state) || extra.contains(state))) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }
}
