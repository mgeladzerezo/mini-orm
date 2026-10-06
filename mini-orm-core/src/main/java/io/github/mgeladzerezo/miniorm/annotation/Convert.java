package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Uses a specific {@code TypeConverter} for this field instead of the one registered for its type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Convert {
    /** Converter class with an accessible no-argument constructor. */
    Class<? extends io.github.mgeladzerezo.miniorm.type.TypeConverter<?, ?>> value();
}
