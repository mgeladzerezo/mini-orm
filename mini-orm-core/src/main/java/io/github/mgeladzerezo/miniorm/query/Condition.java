package io.github.mgeladzerezo.miniorm.query;

import java.util.Objects;

/**
 * A reusable, composable predicate over an entity type. Use it when the flat
 * {@code where(...).and(...).or(...)} chain on {@link Query} is not enough to express
 * grouping:
 *
 * <pre>{@code
 * Condition<User> adultOrAdmin = Condition.where(User::getAge, ge(18)).or(User::getRole, eq(Role.ADMIN));
 * session.from(User.class).where(User::isActive, eq(true)).and(adultOrAdmin).list();
 * // where active = ? and (age >= ? or role = ?)
 * }</pre>
 *
 * <p>Conditions are immutable; every method returns a new one.
 *
 * @param <T> the entity type
 */
public final class Condition<T> {

    private final Expr expr;

    private Condition(Expr expr) {
        this.expr = expr;
    }

    /**
     * Starts a condition.
     *
     * @param property  getter reference or {@link Attribute}
     * @param criterion operator and operand
     * @param <T>       entity type
     * @param <V>       property type
     * @return the condition
     */
    public static <T, V> Condition<T> where(Property<T, V> property, Criterion<V> criterion) {
        return new Condition<>(leaf(PropertyNames.of(property), criterion));
    }

    /**
     * Starts a condition from a property name given as text, for callers that only know the
     * property at run time (derived finders work this way). The name is validated against the
     * entity metadata when the query is rendered and never reaches the SQL text itself.
     *
     * @param property  property name or dotted path
     * @param criterion operator and operand
     * @param <T>       entity type
     * @return the condition
     */
    public static <T> Condition<T> ofProperty(String property, Criterion<?> criterion) {
        return new Condition<>(leaf(property, criterion));
    }

    private static Expr leaf(String property, Criterion<?> criterion) {
        return new Expr.Predicate(Objects.requireNonNull(property, "property"),
                Objects.requireNonNull(criterion, "criterion"));
    }

    /**
     * @param property  getter reference or {@link Attribute}
     * @param criterion operator and operand
     * @param <V>       property type
     * @return {@code this AND property criterion}
     */
    public <V> Condition<T> and(Property<T, V> property, Criterion<V> criterion) {
        return and(where(property, criterion));
    }

    /**
     * @param other another condition
     * @return {@code this AND (other)}
     */
    public Condition<T> and(Condition<T> other) {
        return new Condition<>(Expr.and(expr, other.expr));
    }

    /**
     * @param property  getter reference or {@link Attribute}
     * @param criterion operator and operand
     * @param <V>       property type
     * @return {@code this OR property criterion}
     */
    public <V> Condition<T> or(Property<T, V> property, Criterion<V> criterion) {
        return or(where(property, criterion));
    }

    /**
     * @param other another condition
     * @return {@code this OR (other)}
     */
    public Condition<T> or(Condition<T> other) {
        return new Condition<>(Expr.or(expr, other.expr));
    }

    /**
     * @return {@code NOT (this)}
     */
    public Condition<T> not() {
        return new Condition<>(new Expr.Not(expr));
    }

    /**
     * @return the expression tree behind this condition
     */
    public Expr expr() {
        return expr;
    }
}
