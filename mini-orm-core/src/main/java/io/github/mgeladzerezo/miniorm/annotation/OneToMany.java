package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The inverse side of a {@link ManyToOne}: a {@code List} or {@code Set} of the entities whose
 * foreign key points at this one. The collection is loaded lazily and is read-only as far as
 * the database is concerned: the foreign key is written from the {@code ManyToOne} side.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface OneToMany {
    /** Name of the {@link ManyToOne} field on the target entity that owns the foreign key. */
    String mappedBy();
}
