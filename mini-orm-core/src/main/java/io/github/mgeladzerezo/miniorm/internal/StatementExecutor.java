package io.github.mgeladzerezo.miniorm.internal;

import io.github.mgeladzerezo.miniorm.dialect.Dialect;
import io.github.mgeladzerezo.miniorm.error.ConstraintViolationException;
import io.github.mgeladzerezo.miniorm.error.PersistenceException;
import io.github.mgeladzerezo.miniorm.error.PessimisticLockException;
import io.github.mgeladzerezo.miniorm.sql.SqlEvent;
import io.github.mgeladzerezo.miniorm.sql.SqlListener;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Runs every statement the ORM issues. Having one funnel gives three things for free: each
 * round trip is timed and reported to the {@link SqlListener}s, every {@link SQLException} is
 * translated the same way, and values can only reach the database as bound parameters.
 */
public final class StatementExecutor {

    /** Turns a result set into a value. */
    @FunctionalInterface
    public interface ResultHandler<R> {
        R handle(ResultSet rs) throws SQLException;
    }

    private final Dialect dialect;
    private final List<SqlListener> listeners;

    public StatementExecutor(Dialect dialect, List<SqlListener> listeners) {
        this.dialect = dialect;
        this.listeners = listeners;
    }

    /**
     * Executes a query and hands the open result set to {@code handler}. The reported row
     * count is the size of the handler's result when that is a collection, else 0 or 1.
     */
    public <R> R query(Connection connection, String sql, List<Object> parameters, ResultHandler<R> handler) {
        long start = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Jdbc.bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                R result = handler.handle(rs);
                long rows = result instanceof Collection<?> c ? c.size() : result == null ? 0 : 1;
                publish(sql, List.of(parameters), start, rows, null);
                return result;
            }
        } catch (SQLException e) {
            publish(sql, List.of(parameters), start, 0, e);
            throw translate(e, sql);
        }
    }

    public int update(Connection connection, String sql, List<Object> parameters) {
        long start = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Jdbc.bind(statement, parameters);
            int affected = statement.executeUpdate();
            publish(sql, List.of(parameters), start, affected, null);
            return affected;
        } catch (SQLException e) {
            publish(sql, List.of(parameters), start, 0, e);
            throw translate(e, sql);
        }
    }

    /** Sends all parameter sets in one JDBC batch and returns the per-row update counts. */
    public int[] batch(Connection connection, String sql, List<List<Object>> parameterSets) {
        if (parameterSets.size() == 1) {
            return new int[] {update(connection, sql, parameterSets.getFirst())};
        }
        long start = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (List<Object> parameters : parameterSets) {
                Jdbc.bind(statement, parameters);
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            publish(sql, parameterSets, start, affected(counts), null);
            return counts;
        } catch (SQLException e) {
            publish(sql, parameterSets, start, 0, e);
            throw translate(e, sql);
        }
    }

    /** Batched INSERT that returns the database-generated key of each row, in row order. */
    public List<Object> insertReturningKeys(Connection connection, String sql, String idColumn,
                                            Class<?> keyType, List<List<Object>> parameterSets) {
        long start = System.nanoTime();
        try (PreparedStatement statement = dialect.prepareInsert(connection, sql, idColumn)) {
            if (parameterSets.size() == 1) {
                Jdbc.bind(statement, parameterSets.getFirst());
                statement.executeUpdate();
            } else {
                for (List<Object> parameters : parameterSets) {
                    Jdbc.bind(statement, parameters);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            List<Object> keys = new ArrayList<>(parameterSets.size());
            try (ResultSet rs = statement.getGeneratedKeys()) {
                while (rs.next()) {
                    keys.add(Jdbc.read(rs, 1, keyType));
                }
            }
            if (keys.size() != parameterSets.size()) {
                throw new SQLException("Driver returned " + keys.size() + " generated keys for "
                        + parameterSets.size() + " inserted rows");
            }
            publish(sql, parameterSets, start, keys.size(), null);
            return keys;
        } catch (SQLException e) {
            publish(sql, parameterSets, start, 0, e);
            throw translate(e, sql);
        }
    }

    private static long affected(int[] counts) {
        long total = 0;
        for (int count : counts) {
            total += Math.max(count, 0);
        }
        return total;
    }

    private void publish(String sql, List<List<Object>> parameterSets, long start, long rows, Throwable failure) {
        if (listeners.isEmpty()) {
            return;
        }
        SqlEvent event = new SqlEvent(sql, parameterSets, System.nanoTime() - start, rows, failure);
        for (SqlListener listener : listeners) {
            listener.onStatement(event);
        }
    }

    /** Maps a driver exception to the ORM's hierarchy; chained exceptions are checked too. */
    public PersistenceException translate(SQLException e, String sql) {
        for (SQLException current = e; current != null; current = current.getNextException()) {
            if (dialect.isLockFailure(current)) {
                return new PessimisticLockException("Could not obtain row lock: " + current.getMessage(), sql, e);
            }
            String state = current.getSQLState();
            if (state != null && state.startsWith("23")) {
                return new ConstraintViolationException("Constraint violated: " + current.getMessage(), sql, e);
            }
        }
        return new PersistenceException("Statement failed: " + e.getMessage(), sql, e);
    }
}
