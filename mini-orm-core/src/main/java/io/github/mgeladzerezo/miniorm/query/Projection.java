package io.github.mgeladzerezo.miniorm.query;

import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.internal.Jdbc;
import io.github.mgeladzerezo.miniorm.internal.QueryContext;
import io.github.mgeladzerezo.miniorm.internal.Reflect;
import io.github.mgeladzerezo.miniorm.internal.EntitySql;
import io.github.mgeladzerezo.miniorm.mapping.ColumnMapping;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.type.TypeRegistry;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A query that selects columns and aggregates instead of entities, obtained from
 * {@link Query#select}. Results are plain values (or records), never managed by the session.
 *
 * <pre>{@code
 * record OrdersPerCustomer(Long customerId, long orders, BigDecimal total) {}
 *
 * List<OrdersPerCustomer> report = session.from(Order.class)
 *         .select(col(Order::getCustomer), count(), sum(Order::getTotal))
 *         .groupBy(Order::getCustomer)
 *         .into(OrdersPerCustomer.class);
 * }</pre>
 *
 * @param <T> the queried entity type
 */
public final class Projection<T> {

    private final QueryContext context;
    private final EntityMetadata<T> metadata;
    private final SqlRenderer renderer;
    private final Expr where;
    private final Sort sort;
    private final Integer limit;
    private final Integer offset;
    private final List<Selection<T>> selections;
    private final List<String> groupBy = new ArrayList<>();

    Projection(QueryContext context, EntityMetadata<T> metadata, SqlRenderer renderer, Expr where, Sort sort,
               Integer limit, Integer offset, List<Selection<T>> selections) {
        if (selections.isEmpty()) {
            throw new IllegalArgumentException("select(...) needs at least one column or aggregate");
        }
        this.context = context;
        this.metadata = metadata;
        this.renderer = renderer;
        this.where = where;
        this.sort = sort;
        this.limit = limit;
        this.offset = offset;
        this.selections = List.copyOf(selections);
    }

    /**
     * Groups the result. Every plain column in the select list must be grouped.
     *
     * @param properties getter references or {@link Attribute}s
     * @return this projection
     */
    @SafeVarargs
    public final Projection<T> groupBy(Property<T, ?>... properties) {
        for (Property<T, ?> property : properties) {
            groupBy.add(PropertyNames.of(property));
        }
        return this;
    }

    /**
     * Runs the query and returns each row as an array of converted values, in select order.
     * Columns arrive as their Java type (an enum, a {@code LocalDate}); {@code count} as
     * {@code Long}; {@code sum} as {@code Long}, {@code BigDecimal} or {@code Double} following
     * the column; {@code avg} as {@code Double} or {@code BigDecimal}.
     *
     * @return one array per row
     */
    public List<Object[]> rows() {
        context.autoFlush();
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select ");
        sql.append(selections.stream().map(this::render).collect(Collectors.joining(", ")));
        sql.append(" from ").append(context.dialect().quote(metadata.tableName())).append(' ').append(EntitySql.ROOT);
        if (where != null) {
            sql.append(" where ").append(renderer.where(where, parameters));
        }
        if (!groupBy.isEmpty()) {
            sql.append(" group by ").append(groupBy.stream().map(renderer::column).collect(Collectors.joining(", ")));
        }
        if (sort.isSorted()) {
            sql.append(" order by ").append(renderer.orderBy(sort.orders()));
        }
        String statement = context.dialect().paginate(sql.toString(), limit, offset, parameters);
        return context.query(statement, parameters, rs -> {
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                Object[] row = new Object[selections.size()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = read(rs, i + 1, selections.get(i));
                }
                rows.add(row);
            }
            return rows;
        });
    }

    /**
     * Runs the query and builds one record per row through its canonical constructor. The record
     * must have exactly as many components as there are selections; numeric values are widened
     * or narrowed to the component type.
     *
     * @param recordType the record class
     * @param <R>        record type
     * @return one record per row
     */
    public <R extends Record> List<R> into(Class<R> recordType) {
        Class<?>[] types = Arrays.stream(recordType.getRecordComponents())
                .map(c -> c.getType()).toArray(Class<?>[]::new);
        if (types.length != selections.size()) {
            throw new MappingException(recordType.getSimpleName() + " has " + types.length
                    + " components but the query selects " + selections.size());
        }
        Constructor<R> constructor;
        try {
            constructor = recordType.getDeclaredConstructor(types);
        } catch (NoSuchMethodException e) {
            throw new MappingException(recordType.getName() + " has no canonical constructor", e);
        }
        Reflect.open(constructor, recordType);
        List<R> result = new ArrayList<>();
        for (Object[] row : rows()) {
            Object[] arguments = new Object[row.length];
            for (int i = 0; i < row.length; i++) {
                arguments[i] = coerce(row[i], types[i], recordType.getRecordComponents()[i].getName());
            }
            result.add(Reflect.instantiate(constructor, arguments));
        }
        return result;
    }

    /**
     * Runs a query that selects one value (typically an aggregate) and returns it.
     *
     * @param type the expected Java type
     * @param <R>  that type
     * @return the value of the first row, possibly {@code null} (for example {@code max} of no rows)
     */
    public <R> R scalar(Class<R> type) {
        if (selections.size() != 1) {
            throw new IllegalStateException("scalar() needs exactly one selection, got " + selections.size());
        }
        List<Object[]> rows = rows();
        return rows.isEmpty() ? null : type.cast(coerce(rows.getFirst()[0], type, "result"));
    }

    // ------------------------------------------------------------------ rendering and reading

    private String render(Selection<T> selection) {
        return switch (selection) {
            case Selection.Column<T> column -> renderer.column(column.property());
            case Selection.Aggregate<T> aggregate -> {
                String function = aggregate.function().name().toLowerCase(java.util.Locale.ROOT);
                if (aggregate.property() == null) {
                    yield function + "(*)";
                }
                yield function + "(" + (aggregate.distinct() ? "distinct " : "") + renderer.column(aggregate.property())
                        + ")";
            }
        };
    }

    private Object read(ResultSet rs, int index, Selection<T> selection) throws SQLException {
        return switch (selection) {
            case Selection.Column<T> column -> {
                ColumnMapping mapping = metadata.column(column.property());
                yield mapping.toJava(Jdbc.read(rs, index, mapping.databaseType()));
            }
            case Selection.Aggregate<T> aggregate -> readAggregate(rs, index, aggregate);
        };
    }

    private Object readAggregate(ResultSet rs, int index, Selection.Aggregate<T> aggregate) throws SQLException {
        if (aggregate.function() == Selection.Function.COUNT) {
            return Jdbc.read(rs, index, Long.class);
        }
        ColumnMapping column = metadata.column(aggregate.property());
        Class<?> databaseType = column.databaseType();
        switch (aggregate.function()) {
            case MIN, MAX -> {
                return column.toJava(Jdbc.read(rs, index, databaseType));
            }
            case SUM -> {
                return Jdbc.read(rs, index, databaseType == BigDecimal.class ? BigDecimal.class
                        : databaseType == Double.class || databaseType == Float.class ? Double.class : Long.class);
            }
            default -> {
                return Jdbc.read(rs, index, databaseType == BigDecimal.class ? BigDecimal.class : Double.class);
            }
        }
    }

    /** Adapts a value to a record component or requested type; numbers are converted, other mismatches rejected. */
    private static Object coerce(Object value, Class<?> target, String name) {
        Class<?> boxed = TypeRegistry.box(target);
        if (value == null) {
            if (target.isPrimitive()) {
                throw new MappingException("The query returned null for the primitive component '" + name
                        + "'; declare it with the wrapper type");
            }
            return null;
        }
        if (boxed.isInstance(value)) {
            return value;
        }
        if (value instanceof Number number) {
            if (boxed == Long.class) {
                return number.longValue();
            } else if (boxed == Integer.class) {
                return number.intValue();
            } else if (boxed == Double.class) {
                return number.doubleValue();
            } else if (boxed == Float.class) {
                return number.floatValue();
            } else if (boxed == Short.class) {
                return number.shortValue();
            } else if (boxed == BigDecimal.class) {
                return new BigDecimal(number.toString());
            }
        }
        throw new MappingException("Cannot assign a " + value.getClass().getSimpleName() + " to '" + name + "' of type "
                + target.getSimpleName());
    }
}
