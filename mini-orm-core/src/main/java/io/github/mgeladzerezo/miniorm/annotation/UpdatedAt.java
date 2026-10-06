package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Set to the current time when the entity is persisted and again whenever an UPDATE is issued
 * for it. Supported types: {@code Instant}, {@code LocalDateTime}, {@code OffsetDateTime}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface UpdatedAt {
}
