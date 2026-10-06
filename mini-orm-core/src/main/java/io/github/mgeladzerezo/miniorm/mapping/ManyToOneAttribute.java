package io.github.mgeladzerezo.miniorm.mapping;

import io.github.mgeladzerezo.miniorm.annotation.FetchType;
import io.github.mgeladzerezo.miniorm.internal.Reflect;
import io.github.mgeladzerezo.miniorm.proxy.EntityProxy;
import java.lang.reflect.Field;

/**
 * A reference to another entity, stored as a foreign key column.
 */
public final class ManyToOneAttribute implements AttributeMapping {

    private final Field field;
    private final Class<?> targetClass;
    private final FetchType fetch;
    private final ColumnMapping column;
    private EntityMetadata<?> target;

    ManyToOneAttribute(Field field, FetchType fetch, ColumnMapping column) {
        this.field = field;
        this.targetClass = field.getType();
        this.fetch = fetch;
        this.column = column;
    }

    void link(EntityMetadata<?> target) {
        this.target = target;
    }

    @Override
    public Field field() {
        return field;
    }

    /**
     * @return the referenced entity class
     */
    public Class<?> targetClass() {
        return targetClass;
    }

    /**
     * @return metadata of the referenced entity
     */
    public EntityMetadata<?> target() {
        return target;
    }

    /**
     * @return whether the target is loaded lazily or with the owner
     */
    public FetchType fetch() {
        return fetch;
    }

    /**
     * @return the foreign key column
     */
    public ColumnMapping column() {
        return column;
    }

    /**
     * Reads the id of a referenced object without initializing it if it is a lazy proxy.
     *
     * @param reference an instance or proxy of the target class
     * @return its primary key, or {@code null} if it has none yet
     */
    public Object targetId(Object reference) {
        if (reference instanceof EntityProxy proxy) {
            return proxy.$$miniOrmInitializer().id();
        }
        return target.id(reference);
    }

    @Override
    public void populate(Object entity, Object[] row, ReferenceResolver resolver) {
        Object foreignKey = row[column.index()];
        Object reference = foreignKey == null ? null : resolver.reference(this, entity, column.toJava(foreignKey));
        Reflect.set(field, entity, reference);
    }
}
