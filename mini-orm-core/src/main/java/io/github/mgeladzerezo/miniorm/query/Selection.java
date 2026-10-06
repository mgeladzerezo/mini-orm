package io.github.mgeladzerezo.miniorm.query;

/**
 * One item of a projection's SELECT list: a plain column or an aggregate.
 * Created through {@link Selections}.
 *
 * @param <T> the entity type being queried
 */
public sealed interface Selection<T> {

    /** SQL aggregate functions. */
    enum Function {
        /** Row or value count. */
        COUNT,
        /** Sum. */
        SUM,
        /** Arithmetic mean. */
        AVG,
        /** Minimum. */
        MIN,
        /** Maximum. */
        MAX
    }

    /**
     * A property selected as is.
     *
     * @param property property name or dotted path
     * @param <T>      entity type
     */
    record Column<T>(String property) implements Selection<T> {
    }

    /**
     * An aggregate over a property, or {@code count(*)} when {@code property} is null.
     *
     * @param function the aggregate function
     * @param property property name, or {@code null} for {@code count(*)}
     * @param distinct whether to aggregate distinct values only
     * @param <T>      entity type
     */
    record Aggregate<T>(Function function, String property, boolean distinct) implements Selection<T> {
    }
}
