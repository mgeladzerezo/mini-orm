package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Flattens the fields of a value object (a class or a record) into the owning entity's table.
 * A null embedded value is stored as all-null columns and read back as null.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Embedded {
    /** Prefix prepended to every column of the embedded object, for example {@code "billing_"}. */
    String prefix() default "";
}
