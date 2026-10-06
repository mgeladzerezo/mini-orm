package io.github.mgeladzerezo.miniorm.query;

import java.util.List;

/**
 * The right-hand side of a predicate: an operator together with its operand(s), not yet bound
 * to a property. Created through the static factories in {@link Criteria}.
 *
 * <p>The type parameter ties the operand to the property it will be compared with:
 * {@code where(User::getAge, gt(18))} type-checks because both sides are {@code Integer}.
 *
 * @param <V> the type of property this criterion can be applied to
 */
public sealed interface Criterion<V> {

    /** Binary comparison operators and their SQL spelling. */
    enum Operator {
        /** Equal. */
        EQ("="),
        /** Not equal. */
        NE("<>"),
        /** Greater than. */
        GT(">"),
        /** Greater than or equal. */
        GE(">="),
        /** Less than. */
        LT("<"),
        /** Less than or equal. */
        LE("<=");

        private final String sql;

        Operator(String sql) {
            this.sql = sql;
        }

        /**
         * @return the operator as written in SQL
         */
        public String sql() {
            return sql;
        }
    }

    /**
     * {@code property <op> ?}. A null operand with EQ / NE renders as IS [NOT] NULL.
     *
     * @param operator the comparison
     * @param value    the operand
     * @param <V>      property type
     */
    record Compare<V>(Operator operator, V value) implements Criterion<V> {
    }

    /**
     * {@code property [NOT] IN (?, ?, ...)}.
     *
     * @param values  the candidates; an empty list matches nothing (or everything when negated)
     * @param negated whether this is NOT IN
     * @param <V>     property type
     */
    record In<V>(List<V> values, boolean negated) implements Criterion<V> {
    }

    /**
     * {@code property BETWEEN ? AND ?}, bounds inclusive.
     *
     * @param low  lower bound
     * @param high upper bound
     * @param <V>  property type
     */
    record Between<V>(V low, V high) implements Criterion<V> {
    }

    /**
     * {@code property IS [NOT] NULL}.
     *
     * @param negated whether this is IS NOT NULL
     * @param <V>     property type
     */
    record Null<V>(boolean negated) implements Criterion<V> {
    }

    /**
     * {@code property LIKE ?} with backslash as the escape character; the pattern is always a
     * bound parameter.
     *
     * @param pattern    SQL LIKE pattern
     * @param ignoreCase compare case-insensitively
     */
    record Like(String pattern, boolean ignoreCase) implements Criterion<String> {
    }
}
