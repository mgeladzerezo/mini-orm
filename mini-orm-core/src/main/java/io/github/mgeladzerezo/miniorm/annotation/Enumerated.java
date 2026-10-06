package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Chooses how an enum field is stored. The default, without this annotation, is by name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Enumerated {
    /** Storage form. */
    EnumType value() default EnumType.STRING;
}
