package io.github.mgeladzerezo.miniorm.proxy;

/**
 * Implemented by every generated lazy proxy class. Application code normally has no reason to
 * touch this; use {@code MiniOrm.isInitialized} and {@code MiniOrm.unproxy} instead.
 *
 * <p>This package is exported only because generated proxy classes live in the application's
 * package (they subclass the entity) and must be able to link against these two types.
 */
public interface EntityProxy {

    /**
     * The object that knows the proxy's id and loads its target on demand.
     *
     * @return the initializer, never {@code null} once the session has created the proxy
     */
    LazyInitializer $$miniOrmInitializer();

    /**
     * Attaches the initializer. Called once by the session right after instantiation.
     *
     * @param initializer the proxy's state
     */
    void $$miniOrmBind(LazyInitializer initializer);
}
