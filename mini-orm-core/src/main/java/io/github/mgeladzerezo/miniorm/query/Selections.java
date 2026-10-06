package io.github.mgeladzerezo.miniorm.query;

import io.github.mgeladzerezo.miniorm.query.Selection.Aggregate;
import io.github.mgeladzerezo.miniorm.query.Selection.Function;

/** Static factories for {@link Selection}; meant to be imported statically. */
public final class Selections {

    private Selections() {
    }

    /**
     * @param property getter reference or {@link Attribute}
     * @param <T>      entity type
     * @return the property itself
     */
    public static <T> Selection<T> col(Property<T, ?> property) {
        return new Selection.Column<>(PropertyNames.of(property));
    }

    /**
     * @param <T> entity type
     * @return {@code count(*)}
     */
    public static <T> Selection<T> count() {
        return new Aggregate<>(Function.COUNT, null, false);
    }

    /**
     * @param property getter reference or {@link Attribute}
     * @param <T>      entity type
     * @return {@code count(distinct property)}
     */
    public static <T> Selection<T> countDistinct(Property<T, ?> property) {
        return new Aggregate<>(Function.COUNT, PropertyNames.of(property), true);
    }

    /**
     * @param property a numeric property
     * @param <T>      entity type
     * @return {@code sum(property)}
     */
    public static <T> Selection<T> sum(Property<T, ? extends Number> property) {
        return new Aggregate<>(Function.SUM, PropertyNames.of(property), false);
    }

    /**
     * @param property a numeric property
     * @param <T>      entity type
     * @return {@code avg(property)}
     */
    public static <T> Selection<T> avg(Property<T, ? extends Number> property) {
        return new Aggregate<>(Function.AVG, PropertyNames.of(property), false);
    }

    /**
     * @param property getter reference or {@link Attribute}
     * @param <T>      entity type
     * @return {@code min(property)}
     */
    public static <T> Selection<T> min(Property<T, ?> property) {
        return new Aggregate<>(Function.MIN, PropertyNames.of(property), false);
    }

    /**
     * @param property getter reference or {@link Attribute}
     * @param <T>      entity type
     * @return {@code max(property)}
     */
    public static <T> Selection<T> max(Property<T, ?> property) {
        return new Aggregate<>(Function.MAX, PropertyNames.of(property), false);
    }
}
