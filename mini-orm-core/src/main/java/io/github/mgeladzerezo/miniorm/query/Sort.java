package io.github.mgeladzerezo.miniorm.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An ORDER BY specification: zero or more properties, each ascending or descending.
 *
 * @param orders the sort keys in priority order
 */
public record Sort(List<Order> orders) {

    /** Sort direction. */
    public enum Direction {
        /** Ascending. */
        ASC,
        /** Descending. */
        DESC
    }

    /**
     * One sort key.
     *
     * @param property  property name or dotted path
     * @param direction ascending or descending
     */
    public record Order(String property, Direction direction) {
        /** Validates the key. */
        public Order {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(direction, "direction");
        }
    }

    /** Copies the list. */
    public Sort {
        orders = List.copyOf(orders);
    }

    /**
     * @return a sort with no keys
     */
    public static Sort unsorted() {
        return new Sort(List.of());
    }

    /**
     * @param property getter reference or {@link Attribute}
     * @param <T>      entity type
     * @return ascending sort by the property
     */
    public static <T> Sort asc(Property<T, ?> property) {
        return by(PropertyNames.of(property), Direction.ASC);
    }

    /**
     * @param property getter reference or {@link Attribute}
     * @param <T>      entity type
     * @return descending sort by the property
     */
    public static <T> Sort desc(Property<T, ?> property) {
        return by(PropertyNames.of(property), Direction.DESC);
    }

    /**
     * Sort by a property named as text. The name is validated against the entity metadata
     * when the query is rendered.
     *
     * @param property  property name or dotted path
     * @param direction ascending or descending
     * @return the sort
     */
    public static Sort by(String property, Direction direction) {
        return new Sort(List.of(new Order(property, direction)));
    }

    /**
     * @param next lower-priority keys
     * @return this sort followed by {@code next}
     */
    public Sort then(Sort next) {
        List<Order> all = new ArrayList<>(orders);
        all.addAll(next.orders);
        return new Sort(all);
    }

    /**
     * @return whether there is at least one sort key
     */
    public boolean isSorted() {
        return !orders.isEmpty();
    }
}
