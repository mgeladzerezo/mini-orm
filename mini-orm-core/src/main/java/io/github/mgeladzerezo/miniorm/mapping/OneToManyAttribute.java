package io.github.mgeladzerezo.miniorm.mapping;

import io.github.mgeladzerezo.miniorm.internal.Reflect;
import java.lang.reflect.Field;
import java.util.Set;

/**
 * The inverse side of a {@code @ManyToOne}: the collection of entities whose foreign key
 * points at the owner. It owns no column.
 */
public final class OneToManyAttribute implements AttributeMapping {

    private final Field field;
    private final Class<?> targetClass;
    private final String mappedBy;
    private EntityMetadata<?> target;
    private ManyToOneAttribute inverse;

    OneToManyAttribute(Field field, Class<?> targetClass, String mappedBy) {
        this.field = field;
        this.targetClass = targetClass;
        this.mappedBy = mappedBy;
    }

    void link(EntityMetadata<?> target, ManyToOneAttribute inverse) {
        this.target = target;
        this.inverse = inverse;
    }

    @Override
    public Field field() {
        return field;
    }

    /**
     * @return the element entity class
     */
    public Class<?> targetClass() {
        return targetClass;
    }

    /**
     * @return name of the {@code @ManyToOne} field on the element class
     */
    public String mappedBy() {
        return mappedBy;
    }

    /**
     * @return metadata of the element entity
     */
    public EntityMetadata<?> target() {
        return target;
    }

    /**
     * @return the {@code @ManyToOne} on the element class whose foreign key defines membership
     */
    public ManyToOneAttribute inverse() {
        return inverse;
    }

    /**
     * @return {@code true} if the field is declared as a {@code Set}, {@code false} for lists
     */
    public boolean isSet() {
        return Set.class.isAssignableFrom(field.getType());
    }

    @Override
    public void populate(Object entity, Object[] row, ReferenceResolver resolver) {
        Reflect.set(field, entity, resolver.collection(this, entity));
    }
}
