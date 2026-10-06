package io.github.mgeladzerezo.miniorm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A reference to another entity, stored as a foreign key column on this entity's table. The
 * column is named {@code <field>_id} unless a {@link Column} annotation says otherwise.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ManyToOne {
    /**
     * {@link FetchType#LAZY} puts a generated proxy in the field and loads the target on first
     * use; {@link FetchType#EAGER} loads the target together with the owner (one extra
     * {@code IN} query per result list, not one per row).
     */
    FetchType fetch() default FetchType.LAZY;
}
