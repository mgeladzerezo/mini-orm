package io.github.mgeladzerezo.miniorm.pool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory stand-in for a JDBC driver. It lets tests script failures deterministically
 * (dead connections, refused connects, fatal SQLStates) and observe what the pool did to each
 * physical connection.
 */
final class FakeDatabase implements ConnectionFactory {

    final List<FakeConnection> connections = new CopyOnWriteArrayList<>();
    final AtomicInteger live = new AtomicInteger();
    final AtomicInteger peakLive = new AtomicInteger();
    /** When set, {@link #create()} throws. */
    volatile boolean refuseConnections;
    /** Applied to connections opened from now on. */
    volatile long createDelayMillis;

    @Override
    public Connection create() throws SQLException {
        if (refuseConnections) {
            throw new SQLException("connection refused", "08001");
        }
        if (createDelayMillis > 0) {
            try {
                Thread.sleep(createDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", "08001", e);
            }
        }
        FakeConnection connection = new FakeConnection(this);
        connections.add(connection);
        int now = live.incrementAndGet();
        peakLive.accumulateAndGet(now, Math::max);
        return connection.proxy;
    }

    /** Makes every currently open connection fail validation, like a database restart would. */
    void killAll() {
        for (FakeConnection c : connections) {
            c.valid = false;
        }
    }

    /** State and behaviour of one fake physical connection. */
    static final class FakeConnection implements InvocationHandler {
        private final FakeDatabase db;
        final Connection proxy;
        /** The in-use marker: a test thread flips it while it "works" with the connection. */
        final AtomicBoolean inUse = new AtomicBoolean();
        final AtomicInteger rollbacks = new AtomicInteger();
        final AtomicInteger statementsClosed = new AtomicInteger();
        volatile boolean closed;
        volatile boolean valid = true;
        volatile boolean autoCommit = true;
        volatile boolean readOnly;
        volatile int isolation = Connection.TRANSACTION_READ_COMMITTED;
        /** Thrown by the next statement execution, then cleared. */
        volatile SQLException failNextExecute;

        FakeConnection(FakeDatabase db) {
            this.db = db;
            this.proxy = (Connection) Proxy.newProxyInstance(
                    FakeDatabase.class.getClassLoader(), new Class<?>[] {Connection.class}, this);
        }

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close" -> {
                    if (!closed) {
                        closed = true;
                        db.live.decrementAndGet();
                    }
                    return null;
                }
                case "abort" -> {
                    if (!closed) {
                        closed = true;
                        db.live.decrementAndGet();
                    }
                    return null;
                }
                case "isClosed" -> {
                    return closed;
                }
                case "isValid" -> {
                    return valid && !closed;
                }
                case "getAutoCommit" -> {
                    return autoCommit;
                }
                case "setAutoCommit" -> {
                    autoCommit = (Boolean) args[0];
                    return null;
                }
                case "isReadOnly" -> {
                    return readOnly;
                }
                case "setReadOnly" -> {
                    readOnly = (Boolean) args[0];
                    return null;
                }
                case "getTransactionIsolation" -> {
                    return isolation;
                }
                case "setTransactionIsolation" -> {
                    isolation = (Integer) args[0];
                    return null;
                }
                case "getCatalog", "getSchema" -> {
                    return "fake";
                }
                case "rollback" -> {
                    rollbacks.incrementAndGet();
                    return null;
                }
                case "createStatement" -> {
                    return statement(Statement.class);
                }
                case "prepareStatement" -> {
                    return statement(PreparedStatement.class);
                }
                case "unwrap" -> {
                    return this;
                }
                case "isWrapperFor" -> {
                    return args[0] == FakeConnection.class;
                }
                case "hashCode" -> {
                    return System.identityHashCode(self);
                }
                case "equals" -> {
                    return self == args[0];
                }
                case "toString" -> {
                    return "FakeConnection@" + Integer.toHexString(System.identityHashCode(self));
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        }

        private <S extends Statement> S statement(Class<S> type) {
            InvocationHandler handler = (self, method, args) -> {
                String name = method.getName();
                if (name.startsWith("execute")) {
                    SQLException failure = failNextExecute;
                    if (failure != null) {
                        failNextExecute = null;
                        throw failure;
                    }
                }
                if (name.equals("close")) {
                    statementsClosed.incrementAndGet();
                }
                return switch (name) {
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "FakeStatement";
                    default -> defaultValue(method.getReturnType());
                };
            };
            return type.cast(Proxy.newProxyInstance(FakeDatabase.class.getClassLoader(), new Class<?>[] {type}, handler));
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            return null;
        }
    }
}
