package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the table name and declares secondary indexes. Without it the table is the
 * snake_case form of the class name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {
    /** Table name; empty means "derive from the class name". */
    String name() default "";

    /** Indexes created by the schema generator in addition to primary key and unique constraints. */
    Index[] indexes() default {};
}
