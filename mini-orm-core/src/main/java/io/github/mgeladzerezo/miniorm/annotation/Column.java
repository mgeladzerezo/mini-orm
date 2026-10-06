package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Customises the column of a basic field, of a {@link ManyToOne} foreign key, or of a field
 * inside an embeddable.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {
    /** Column name; empty means the snake_case form of the field name. */
    String name() default "";

    /** Whether the column accepts NULL. Primitive fields are never nullable. */
    boolean nullable() default true;

    /** Length for string columns; {@code 0} asks the dialect for an unbounded text type. */
    int length() default 255;

    /** Whether the schema generator adds a unique constraint. */
    boolean unique() default false;

    /** Total digits for {@code BigDecimal} columns. */
    int precision() default 19;

    /** Fraction digits for {@code BigDecimal} columns. */
    int scale() default 2;

    /** Whether the column may appear in UPDATE statements. */
    boolean updatable() default true;
}
