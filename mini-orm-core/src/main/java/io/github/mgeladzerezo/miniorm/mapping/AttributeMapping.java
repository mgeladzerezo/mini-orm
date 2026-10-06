package io.github.mgeladzerezo.miniorm.mapping;

import java.lang.reflect.Field;

/**
 * A mapped field of an entity class. The four kinds differ in how many columns they own and in
 * how a field value is rebuilt from a row, which is why this is a sealed hierarchy rather than
 * a class with a type flag: code that handles attributes switches over the kinds and the
 * compiler checks that none is forgotten.
 */
public sealed interface AttributeMapping
        permits BasicAttribute, EmbeddedAttribute, ManyToOneAttribute, OneToManyAttribute {

    /**
     * The entity field.
     *
     * @return the reflected field, already accessible
     */
    Field field();

    /**
     * Name of the field, which is the property name used in queries.
     *
     * @return the property name
     */
    default String name() {
        return field().getName();
    }

    /**
     * Assigns this attribute's field on {@code entity} from a row of database-side values.
     *
     * @param entity   the instance being filled
     * @param row      one value per column of the entity, indexed by {@link ColumnMapping#index()}
     * @param resolver supplies references and collections for association attributes
     */
    void populate(Object entity, Object[] row, ReferenceResolver resolver);
}
