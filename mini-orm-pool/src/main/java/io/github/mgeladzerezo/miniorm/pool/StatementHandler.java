package io.github.mgeladzerezo.miniorm.pool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Invocation handler behind the {@code Statement}, {@code PreparedStatement} and
 * {@code CallableStatement} objects handed out by a {@link PooledConnection}.
 *
 * <p>A dynamic proxy is used here, unlike for the connection itself, because the three
 * statement interfaces have about 250 methods between them and the reflective call is noise
 * next to a database round trip. The handler does three things: it un-registers the statement
 * from its connection on {@code close()}, it answers {@code getConnection()} with the pooled
 * connection instead of leaking the physical one, and it passes every {@link SQLException}
 * through {@link PooledConnection#inspect} so a dead connection is noticed at the first failed
 * statement rather than at the next borrow.
 */
final class StatementHandler implements InvocationHandler {

    private final PooledConnection owner;
    private final Statement delegate;

    StatementHandler(PooledConnection owner, Statement delegate) {
        this.owner = owner;
        this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "close" -> owner.untrack(delegate);
            case "getConnection" -> {
                return owner;
            }
            case "equals" -> {
                if (method.getParameterCount() == 1 && method.getDeclaringClass() == Object.class) {
                    return proxy == args[0];
                }
            }
            case "hashCode" -> {
                if (method.getParameterCount() == 0) {
                    return System.identityHashCode(proxy);
                }
            }
            case "toString" -> {
                if (method.getParameterCount() == 0) {
                    return "PooledStatement -> " + delegate;
                }
            }
            case "unwrap" -> {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(delegate)) {
                    return delegate;
                }
            }
            case "isWrapperFor" -> {
                if (((Class<?>) args[0]).isInstance(delegate)) {
                    return true;
                }
            }
            default -> {
                // plain delegation below
            }
        }
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) {
                owner.inspect(sql);
            }
            throw cause;
        }
    }
}
