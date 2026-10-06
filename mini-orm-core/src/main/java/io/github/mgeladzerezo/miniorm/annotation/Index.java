package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A secondary index, declared inside {@link Table#indexes()}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Index {
    /** Index name; empty means {@code idx_<table>_<columns>}. */
    String name() default "";

    /** Property names (not column names) in index order. */
    String[] properties();

    /** Whether the index enforces uniqueness. */
    boolean unique() default false;
}
