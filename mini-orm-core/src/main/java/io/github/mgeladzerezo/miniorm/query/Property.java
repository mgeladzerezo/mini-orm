package io.github.mgeladzerezo.miniorm.query;

import java.io.Serializable;

/**
 * A reference to a persistent property, written as a getter method reference:
 * {@code User::getEmail}, or {@code Point::x} for record-style accessors.
 *
 * <p>The interface is {@link Serializable} on purpose. The compiler then emits a
 * {@code writeReplace} method on the lambda object that returns a
 * {@link java.lang.invoke.SerializedLambda}, from which the query API reads the name of the
 * referenced method and maps it to a column. The getter itself is never called.
 *
 * @param <T> the entity type
 * @param <V> the property type
 */
@FunctionalInterface
public interface Property<T, V> extends Serializable {

    /**
     * Reads the property. Present so that a getter reference fits this interface; the query
     * API does not call it.
     *
     * @param entity an entity instance
     * @return the property value
     */
    V get(T entity);
}
