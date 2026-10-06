package io.github.mgeladzerezo.miniorm.query;

import io.github.mgeladzerezo.miniorm.query.Criterion.Operator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Static factories for {@link Criterion}; meant to be imported statically.
 *
 * <pre>{@code
 * import static io.github.mgeladzerezo.miniorm.query.Criteria.*;
 *
 * session.from(User.class).where(User::getEmail, eq("a@b.c")).and(User::getAge, between(18, 65)).list();
 * }</pre>
 */
public final class Criteria {

    private Criteria() {
    }

    /**
     * Equality; {@code eq(null)} means IS NULL.
     *
     * @param value operand
     * @param <V>   property type
     * @return the criterion
     */
    public static <V> Criterion<V> eq(V value) {
        return new Criterion.Compare<>(Operator.EQ, value);
    }

    /**
     * Inequality; {@code ne(null)} means IS NOT NULL.
     *
     * @param value operand
     * @param <V>   property type
     * @return the criterion
     */
    public static <V> Criterion<V> ne(V value) {
        return new Criterion.Compare<>(Operator.NE, value);
    }

    /**
     * Greater than.
     *
     * @param value operand
     * @param <V>   property type
     * @return the criterion
     */
    public static <V extends Comparable<? super V>> Criterion<V> gt(V value) {
        return new Criterion.Compare<>(Operator.GT, value);
    }

    /**
     * Greater than or equal.
     *
     * @param value operand
     * @param <V>   property type
     * @return the criterion
     */
    public static <V extends Comparable<? super V>> Criterion<V> ge(V value) {
        return new Criterion.Compare<>(Operator.GE, value);
    }

    /**
     * Less than.
     *
     * @param value operand
     * @param <V>   property type
     * @return the criterion
     */
    public static <V extends Comparable<? super V>> Criterion<V> lt(V value) {
        return new Criterion.Compare<>(Operator.LT, value);
    }

    /**
     * Less than or equal.
     *
     * @param value operand
     * @param <V>   property type
     * @return the criterion
     */
    public static <V extends Comparable<? super V>> Criterion<V> le(V value) {
        return new Criterion.Compare<>(Operator.LE, value);
    }

    /**
     * Inclusive range.
     *
     * @param low  lower bound
     * @param high upper bound
     * @param <V>  property type
     * @return the criterion
     */
    public static <V extends Comparable<? super V>> Criterion<V> between(V low, V high) {
        return new Criterion.Between<>(low, high);
    }

    /**
     * Membership.
     *
     * @param values candidates
     * @param <V>    property type
     * @return the criterion
     */
    @SafeVarargs
    public static <V> Criterion<V> in(V... values) {
        return new Criterion.In<>(Collections.unmodifiableList(new ArrayList<>(List.of(values))), false);
    }

    /**
     * Membership.
     *
     * @param values candidates; an empty collection matches no row
     * @param <V>    property type
     * @return the criterion
     */
    public static <V> Criterion<V> in(Collection<? extends V> values) {
        return new Criterion.In<>(Collections.unmodifiableList(new ArrayList<>(values)), false);
    }

    /**
     * Non-membership.
     *
     * @param values excluded values; an empty collection matches every row
     * @param <V>    property type
     * @return the criterion
     */
    public static <V> Criterion<V> notIn(Collection<? extends V> values) {
        return new Criterion.In<>(Collections.unmodifiableList(new ArrayList<>(values)), true);
    }

    /**
     * IS NULL.
     *
     * @param <V> property type
     * @return the criterion
     */
    public static <V> Criterion<V> isNull() {
        return new Criterion.Null<>(false);
    }

    /**
     * IS NOT NULL.
     *
     * @param <V> property type
     * @return the criterion
     */
    public static <V> Criterion<V> isNotNull() {
        return new Criterion.Null<>(true);
    }

    /**
     * LIKE with a caller-supplied pattern ({@code %} and {@code _} are wildcards, backslash escapes).
     *
     * @param pattern the pattern
     * @return the criterion
     */
    public static Criterion<String> like(String pattern) {
        return new Criterion.Like(pattern, false);
    }

    /**
     * Case-insensitive LIKE.
     *
     * @param pattern the pattern
     * @return the criterion
     */
    public static Criterion<String> ilike(String pattern) {
        return new Criterion.Like(pattern, true);
    }

    /**
     * Substring match. Wildcard characters in {@code text} are escaped and match literally.
     *
     * @param text the substring
     * @return the criterion
     */
    public static Criterion<String> contains(String text) {
        return new Criterion.Like("%" + escapeLike(text) + "%", false);
    }

    /**
     * Case-insensitive substring match with wildcards escaped.
     *
     * @param text the substring
     * @return the criterion
     */
    public static Criterion<String> containsIgnoreCase(String text) {
        return new Criterion.Like("%" + escapeLike(text) + "%", true);
    }

    /**
     * Prefix match with wildcards escaped.
     *
     * @param prefix the prefix
     * @return the criterion
     */
    public static Criterion<String> startsWith(String prefix) {
        return new Criterion.Like(escapeLike(prefix) + "%", false);
    }

    /**
     * Suffix match with wildcards escaped.
     *
     * @param suffix the suffix
     * @return the criterion
     */
    public static Criterion<String> endsWith(String suffix) {
        return new Criterion.Like("%" + escapeLike(suffix), false);
    }

    /**
     * Escapes LIKE wildcards so user input matches literally.
     *
     * @param text raw text
     * @return the text with backslash, percent and underscore each preceded by a backslash
     */
    public static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
