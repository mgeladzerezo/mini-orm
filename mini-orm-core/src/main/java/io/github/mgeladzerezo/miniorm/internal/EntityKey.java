package io.github.mgeladzerezo.miniorm.internal;

/**
 * Identity of a row within a session: entity class plus primary key. This is the key of the
 * identity map (first-level cache).
 */
public record EntityKey(Class<?> entityClass, Object id) {
}
