package io.github.mgeladzerezo.miniorm.query;

import java.util.ArrayList;
import java.util.List;

/**
 * The WHERE clause as a tree. Leaves compare one property with a {@link Criterion}; inner
 * nodes are AND, OR and NOT. The tree holds property names and operand values only; SQL text
 * is produced later, per dialect, by a renderer that turns every operand into a bind
 * parameter.
 */
public sealed interface Expr {

    /**
     * A property compared with a criterion.
     *
     * @param property  property name or dotted path
     * @param criterion operator and operand(s)
     */
    record Predicate(String property, Criterion<?> criterion) implements Expr {
    }

    /**
     * Conjunction.
     *
     * @param operands at least two sub-expressions
     */
    record And(List<Expr> operands) implements Expr {
        /** Copies the operand list. */
        public And {
            operands = List.copyOf(operands);
        }
    }

    /**
     * Disjunction.
     *
     * @param operands at least two sub-expressions
     */
    record Or(List<Expr> operands) implements Expr {
        /** Copies the operand list. */
        public Or {
            operands = List.copyOf(operands);
        }
    }

    /**
     * Negation.
     *
     * @param operand the negated sub-expression
     */
    record Not(Expr operand) implements Expr {
    }

    /**
     * Combines two expressions with AND, flattening nested ANDs so the SQL has no redundant
     * parentheses.
     *
     * @param left  first operand, or {@code null} for "no condition yet"
     * @param right second operand
     * @return the conjunction
     */
    static Expr and(Expr left, Expr right) {
        if (left == null) {
            return right;
        }
        if (left instanceof And(List<Expr> operands)) {
            return new And(append(operands, right));
        }
        return new And(List.of(left, right));
    }

    /**
     * Combines two expressions with OR, flattening nested ORs.
     *
     * @param left  first operand, or {@code null} for "no condition yet"
     * @param right second operand
     * @return the disjunction
     */
    static Expr or(Expr left, Expr right) {
        if (left == null) {
            return right;
        }
        if (left instanceof Or(List<Expr> operands)) {
            return new Or(append(operands, right));
        }
        return new Or(List.of(left, right));
    }

    private static List<Expr> append(List<Expr> operands, Expr extra) {
        List<Expr> all = new ArrayList<>(operands);
        all.add(extra);
        return all;
    }
}
