package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the optimistic-lock version field ({@code int}, {@code long} or their wrappers). Every
 * UPDATE and DELETE of the entity adds {@code and version = ?} to its WHERE clause and the
 * ORM raises {@code OptimisticLockException} when no row matches.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Version {
}
