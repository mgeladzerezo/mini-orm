package io.github.mgeladzerezo.miniorm.proxy;

import java.util.Objects;

/**
 * State of one lazy proxy: which entity it stands for and, once loaded, the real instance that
 * every proxied method call is forwarded to.
 *
 * <p>Not thread-safe, like the session it belongs to.
 */
public final class LazyInitializer {

    /** Loads the real entity for a proxy. Implemented by the session. */
    @FunctionalInterface
    public interface Loader {

        /**
         * Loads the entity or fails.
         *
         * @param entityClass mapped class of the target
         * @param id          primary key of the target
         * @return the managed instance, never {@code null}
         * @throws io.github.mgeladzerezo.miniorm.error.LazyInitializationException if the session is closed
         * @throws io.github.mgeladzerezo.miniorm.error.EntityNotFoundException     if the row is gone
         */
        Object load(Class<?> entityClass, Object id);
    }

    private final Class<?> entityClass;
    private final Object id;
    private final Loader loader;
    private Object target;

    /**
     * @param entityClass mapped class the proxy stands for
     * @param id          primary key of the target row
     * @param loader      how to fetch the target
     */
    public LazyInitializer(Class<?> entityClass, Object id, Loader loader) {
        this.entityClass = Objects.requireNonNull(entityClass, "entityClass");
        this.id = Objects.requireNonNull(id, "id");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Returns the real entity, loading it on first call. Called by generated code at the start
     * of every proxied method.
     *
     * @return the loaded entity
     */
    public Object target() {
        if (target == null) {
            target = loader.load(entityClass, id);
        }
        return target;
    }

    /**
     * The primary key, available without touching the database.
     *
     * @return the id the proxy was created with
     */
    public Object id() {
        return id;
    }

    /**
     * The mapped entity class (not the generated subclass).
     *
     * @return the entity class
     */
    public Class<?> entityClass() {
        return entityClass;
    }

    /**
     * Whether the target has been loaded.
     *
     * @return {@code true} once {@link #target()} has succeeded or {@link #setTarget} was called
     */
    public boolean isInitialized() {
        return target != null;
    }

    /**
     * Supplies the target without a query, used when the session happens to load the row
     * through another path (a query, a batch fetch).
     *
     * @param target the managed entity this proxy stands for
     */
    public void setTarget(Object target) {
        this.target = target;
    }
}
