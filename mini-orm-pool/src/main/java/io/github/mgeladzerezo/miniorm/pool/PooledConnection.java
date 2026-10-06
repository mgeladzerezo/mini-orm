package io.github.mgeladzerezo.miniorm.pool;

import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link Connection} a borrower sees. A new instance is created for every borrow, so a
 * stale reference kept after {@code close()} can never reach the physical connection once it
 * has been handed to someone else.
 *
 * <p>Responsibilities beyond delegation:
 * <ul>
 *   <li>{@link #close()} returns the physical connection to the pool instead of closing it;</li>
 *   <li>session state the borrower changed (auto-commit, isolation, read-only, catalog, schema)
 *       is restored and an open transaction is rolled back before the next borrower sees it;</li>
 *   <li>statements created through this connection are tracked and closed on return;</li>
 *   <li>every {@link SQLException} is inspected, and a fatal SQLState marks the connection so
 *       it is discarded rather than pooled again.</li>
 * </ul>
 *
 * <p>The class is written by hand rather than as a dynamic proxy because it sits on the
 * borrow/return hot path. It is as thread-safe as the driver's connection is, except for
 * {@code close()}, which is safe to call from any thread and takes effect once.
 */
final class PooledConnection implements Connection {

    private static final int DIRTY_AUTO_COMMIT = 1;
    private static final int DIRTY_READ_ONLY = 1 << 1;
    private static final int DIRTY_ISOLATION = 1 << 2;
    private static final int DIRTY_CATALOG = 1 << 3;
    private static final int DIRTY_SCHEMA = 1 << 4;

    private final MiniPool pool;
    private final PoolEntry entry;
    private final Connection physical;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Statement> openStatements = new ArrayList<>(2);

    private int dirty;
    private boolean autoCommit;
    private volatile boolean broken;

    PooledConnection(MiniPool pool, PoolEntry entry) {
        this.pool = pool;
        this.entry = entry;
        this.physical = entry.physical;
        this.autoCommit = entry.defaultAutoCommit;
    }

    // ------------------------------------------------------------------ pool integration

    @Override
    public void close() throws SQLException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EvictionReason failure = broken ? EvictionReason.BROKEN : null;
        if (failure == null) {
            try {
                closeStatements();
                resetState();
            } catch (SQLException e) {
                failure = isFatal(e) ? EvictionReason.BROKEN : EvictionReason.RESET_FAILED;
            }
            if (broken) {
                failure = EvictionReason.BROKEN;
            }
        }
        pool.release(entry, failure);
    }

    private void closeStatements() {
        // Iterate backwards: a statement's close() removes it from the list.
        for (int i = openStatements.size() - 1; i >= 0; i--) {
            try {
                openStatements.get(i).close();
            } catch (SQLException e) {
                inspect(e);
            }
        }
        openStatements.clear();
    }

    /**
     * Puts the session back to the state it had when the pool created it. The rollback comes
     * first on purpose: {@code setAutoCommit(true)} on a connection with an open transaction
     * commits it, which would silently persist work the borrower abandoned.
     */
    private void resetState() throws SQLException {
        if (!autoCommit) {
            physical.rollback();
        }
        if ((dirty & DIRTY_AUTO_COMMIT) != 0) {
            physical.setAutoCommit(entry.defaultAutoCommit);
        }
        if ((dirty & DIRTY_READ_ONLY) != 0) {
            physical.setReadOnly(entry.defaultReadOnly);
        }
        if ((dirty & DIRTY_ISOLATION) != 0) {
            physical.setTransactionIsolation(entry.defaultIsolation);
        }
        if ((dirty & DIRTY_CATALOG) != 0 && entry.defaultCatalog != null) {
            physical.setCatalog(entry.defaultCatalog);
        }
        if ((dirty & DIRTY_SCHEMA) != 0 && entry.defaultSchema != null) {
            physical.setSchema(entry.defaultSchema);
        }
        physical.clearWarnings();
    }

    private Connection open() throws SQLException {
        if (closed.get()) {
            throw new SQLException("Connection is closed (it was returned to the pool)", "08003");
        }
        return physical;
    }

    private boolean isFatal(SQLException e) {
        return FatalSqlStates.isFatal(e, pool.config().extraFatalSqlStates());
    }

    /** Marks the connection for eviction if the exception says it is dead. Returns the argument. */
    SQLException inspect(SQLException e) {
        if (isFatal(e)) {
            broken = true;
        }
        return e;
    }

    void untrack(Statement raw) {
        // Statements are usually closed in reverse order of creation.
        for (int i = openStatements.size() - 1; i >= 0; i--) {
            if (openStatements.get(i) == raw) {
                openStatements.remove(i);
                return;
            }
        }
    }

    private <S extends Statement> S track(S raw, Class<S> type) {
        openStatements.add(raw);
        Object proxy = Proxy.newProxyInstance(
                PooledConnection.class.getClassLoader(), new Class<?>[] {type}, new StatementHandler(this, raw));
        return type.cast(proxy);
    }

    // ------------------------------------------------------------------ statements

    @Override
    public Statement createStatement() throws SQLException {
        try {
            return track(open().createStatement(), Statement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Statement createStatement(int type, int concurrency) throws SQLException {
        try {
            return track(open().createStatement(type, concurrency), Statement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Statement createStatement(int type, int concurrency, int holdability) throws SQLException {
        try {
            return track(open().createStatement(type, concurrency, holdability), Statement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        try {
            return track(open().prepareStatement(sql), PreparedStatement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int type, int concurrency) throws SQLException {
        try {
            return track(open().prepareStatement(sql, type, concurrency), PreparedStatement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int type, int concurrency, int holdability)
            throws SQLException {
        try {
            return track(open().prepareStatement(sql, type, concurrency, holdability), PreparedStatement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        try {
            return track(open().prepareStatement(sql, autoGeneratedKeys), PreparedStatement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        try {
            return track(open().prepareStatement(sql, columnIndexes), PreparedStatement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        try {
            return track(open().prepareStatement(sql, columnNames), PreparedStatement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        try {
            return track(open().prepareCall(sql), CallableStatement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public CallableStatement prepareCall(String sql, int type, int concurrency) throws SQLException {
        try {
            return track(open().prepareCall(sql, type, concurrency), CallableStatement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public CallableStatement prepareCall(String sql, int type, int concurrency, int holdability)
            throws SQLException {
        try {
            return track(open().prepareCall(sql, type, concurrency, holdability), CallableStatement.class);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    // ------------------------------------------------------------------ tracked session state

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        try {
            open().setAutoCommit(autoCommit);
            this.autoCommit = autoCommit;
            dirty |= DIRTY_AUTO_COMMIT;
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        open();
        return autoCommit;
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        try {
            open().setReadOnly(readOnly);
            dirty |= DIRTY_READ_ONLY;
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        try {
            return open().isReadOnly();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        try {
            open().setTransactionIsolation(level);
            dirty |= DIRTY_ISOLATION;
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        try {
            return open().getTransactionIsolation();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        try {
            open().setCatalog(catalog);
            dirty |= DIRTY_CATALOG;
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public String getCatalog() throws SQLException {
        try {
            return open().getCatalog();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        try {
            open().setSchema(schema);
            dirty |= DIRTY_SCHEMA;
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public String getSchema() throws SQLException {
        try {
            return open().getSchema();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    // ------------------------------------------------------------------ transactions

    @Override
    public void commit() throws SQLException {
        try {
            open().commit();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void rollback() throws SQLException {
        try {
            open().rollback();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        try {
            return open().setSavepoint();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        try {
            return open().setSavepoint(name);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        try {
            open().rollback(savepoint);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        try {
            open().releaseSavepoint(savepoint);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    // ------------------------------------------------------------------ lifecycle queries

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (closed.get()) {
            return false;
        }
        try {
            boolean valid = physical.isValid(timeout);
            if (!valid) {
                broken = true;
            }
            return valid;
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    /**
     * Aborts the physical connection and gives its slot back to the pool. The connection is
     * never reused.
     */
    @Override
    public void abort(Executor executor) throws SQLException {
        broken = true;
        try {
            physical.abort(executor);
        } finally {
            close();
        }
    }

    // ------------------------------------------------------------------ plain delegation

    @Override
    public String nativeSQL(String sql) throws SQLException {
        try {
            return open().nativeSQL(sql);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    /**
     * Returns the driver's metadata object. Note that its {@code getConnection()} is the
     * physical connection, not this proxy.
     */
    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        try {
            return open().getMetaData();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        try {
            return open().getWarnings();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void clearWarnings() throws SQLException {
        try {
            open().clearWarnings();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        try {
            return open().getTypeMap();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        try {
            open().setTypeMap(map);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        try {
            open().setHoldability(holdability);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public int getHoldability() throws SQLException {
        try {
            return open().getHoldability();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Clob createClob() throws SQLException {
        try {
            return open().createClob();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Blob createBlob() throws SQLException {
        try {
            return open().createBlob();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public NClob createNClob() throws SQLException {
        try {
            return open().createNClob();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        try {
            return open().createSQLXML();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        if (closed.get()) {
            throw new SQLClientInfoException("Connection is closed", "08003", Map.of());
        }
        physical.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        if (closed.get()) {
            throw new SQLClientInfoException("Connection is closed", "08003", Map.of());
        }
        physical.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        try {
            return open().getClientInfo(name);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        try {
            return open().getClientInfo();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        try {
            return open().createArrayOf(typeName, elements);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        try {
            return open().createStruct(typeName, attributes);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        try {
            open().setNetworkTimeout(executor, milliseconds);
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        try {
            return open().getNetworkTimeout();
        } catch (SQLException e) {
            throw inspect(e);
        }
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        Connection target = open();
        if (iface.isInstance(target)) {
            return iface.cast(target);
        }
        return target.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        Connection target = open();
        return iface.isInstance(this) || iface.isInstance(target) || target.isWrapperFor(iface);
    }

    @Override
    public String toString() {
        return "PooledConnection[" + pool.config().poolName() + (closed.get() ? ", closed" : "") + "] -> " + physical;
    }
}
