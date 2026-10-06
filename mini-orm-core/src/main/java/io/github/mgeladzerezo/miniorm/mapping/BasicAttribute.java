package io.github.mgeladzerezo.miniorm.mapping;

import io.github.mgeladzerezo.miniorm.internal.Reflect;
import java.lang.reflect.Field;

/**
 * A field stored in a single column through a {@code TypeConverter}.
 *
 * @param field  the entity field
 * @param column its column
 */
public record BasicAttribute(Field field, ColumnMapping column) implements AttributeMapping {

    @Override
    public void populate(Object entity, Object[] row, ReferenceResolver resolver) {
        Object value = column.toJava(row[column.index()]);
        if (value == null && field.getType().isPrimitive()) {
            // SQL NULL in a primitive field: keep the field's default rather than fail.
            value = Reflect.defaultValue(field.getType());
        }
        Reflect.set(field, entity, value);
    }
}
