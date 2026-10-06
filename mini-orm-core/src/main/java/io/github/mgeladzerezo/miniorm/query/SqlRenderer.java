package io.github.mgeladzerezo.miniorm.query;

import io.github.mgeladzerezo.miniorm.dialect.Dialect;
import io.github.mgeladzerezo.miniorm.mapping.ColumnMapping;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.type.TypeRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders expression trees to SQL for one dialect. The only text that comes from the caller is
 * a property name, and that is resolved against the entity metadata to a column name (an
 * unknown name is an error), so no caller-supplied string can reach the SQL. Every operand
 * becomes a {@code ?} and is appended to {@code parameters} in order of appearance.
 */
final class SqlRenderer {

    private final Dialect dialect;
    private final EntityMetadata<?> metadata;
    private final String alias;

    SqlRenderer(Dialect dialect, EntityMetadata<?> metadata, String alias) {
        this.dialect = dialect;
        this.metadata = metadata;
        this.alias = alias;
    }

    /** {@code alias."column"} for a property path. */
    String column(String property) {
        return alias + "." + dialect.quote(metadata.column(property).name());
    }

    String where(Expr expr, List<Object> parameters) {
        return render(expr, parameters, false);
    }

    private String render(Expr expr, List<Object> parameters, boolean nested) {
        return switch (expr) {
            case Expr.Predicate predicate -> predicate(predicate, parameters);
            case Expr.And and -> join(and.operands(), " and ", parameters, nested);
            case Expr.Or or -> join(or.operands(), " or ", parameters, nested);
            case Expr.Not not -> "not (" + render(not.operand(), parameters, false) + ")";
        };
    }

    private String join(List<Expr> operands, String operator, List<Object> parameters, boolean nested) {
        String joined = operands.stream().map(e -> render(e, parameters, true)).collect(Collectors.joining(operator));
        return nested ? "(" + joined + ")" : joined;
    }

    private String predicate(Expr.Predicate predicate, List<Object> parameters) {
        ColumnMapping column = metadata.column(predicate.property());
        String lhs = alias + "." + dialect.quote(column.name());
        return switch (predicate.criterion()) {
            case Criterion.Compare<?> compare -> compare(compare, column, lhs, parameters);
            case Criterion.In<?> in -> in(in, column, lhs, parameters);
            case Criterion.Between<?> between -> {
                parameters.add(operand(column, between.low()));
                parameters.add(operand(column, between.high()));
                yield lhs + " between ? and ?";
            }
            case Criterion.Null<?> isNull -> lhs + (isNull.negated() ? " is not null" : " is null");
            case Criterion.Like like -> {
                parameters.add(like.pattern());
                yield like.ignoreCase() ? "lower(" + lhs + ") like lower(?) escape '\\'" : lhs + " like ? escape '\\'";
            }
        };
    }

    private String compare(Criterion.Compare<?> compare, ColumnMapping column, String lhs, List<Object> parameters) {
        if (compare.value() == null) {
            return switch (compare.operator()) {
                case EQ -> lhs + " is null";
                case NE -> lhs + " is not null";
                default -> throw new IllegalArgumentException("Cannot compare " + column.property() + " "
                        + compare.operator().sql() + " null; use isNull() / isNotNull() or eq(null)");
            };
        }
        parameters.add(operand(column, compare.value()));
        return lhs + " " + compare.operator().sql() + " ?";
    }

    private String in(Criterion.In<?> in, ColumnMapping column, String lhs, List<Object> parameters) {
        if (in.values().isEmpty()) {
            return in.negated() ? "1 = 1" : "1 = 0";
        }
        for (Object value : in.values()) {
            parameters.add(operand(column, value));
        }
        return lhs + (in.negated() ? " not in (" : " in (") + "?, ".repeat(in.values().size() - 1) + "?)";
    }

    /** Checks the operand against the property type, then converts it to its database form. */
    private Object operand(ColumnMapping column, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("A null operand is only allowed for eq(null) and ne(null); "
                    + "use isNull() for " + column.property());
        }
        Class<?> expected = TypeRegistry.box(column.javaType());
        boolean fits = expected.isInstance(value);
        if (!fits && column.association() != null) {
            fits = column.association().targetClass().isInstance(value) || TypeRegistry.box(
                    column.association().target().idColumn().javaType()).isInstance(value);
        }
        if (!fits) {
            throw new IllegalArgumentException("Property " + metadata.entityName() + "." + column.property()
                    + " is a " + expected.getSimpleName() + " but the operand is a "
                    + value.getClass().getSimpleName() + " (" + value + ")");
        }
        return column.toDatabaseForQuery(value);
    }

    /** {@code alias.a asc, alias.b desc}; empty for no keys. */
    String orderBy(List<Sort.Order> orders) {
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : orders) {
            parts.add(column(order.property()) + (order.direction() == Sort.Direction.DESC ? " desc" : " asc"));
        }
        return String.join(", ", parts);
    }
}
