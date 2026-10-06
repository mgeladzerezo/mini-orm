package io.github.mgeladzerezo.miniorm.mapping;

import io.github.mgeladzerezo.miniorm.internal.Reflect;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/**
 * A value object whose components are stored in columns of the owning entity's table. The
 * embeddable may be a record (built through its canonical constructor) or a class with a
 * no-argument constructor (built field by field).
 *
 * @param field       the entity field holding the value object
 * @param type        the embeddable class
 * @param columns     one column per component, in component order
 * @param components  the embeddable's own fields, parallel to {@code columns}; for a record
 *                    these are the fields backing its components
 * @param constructor canonical constructor for records, no-argument constructor otherwise
 */
public record EmbeddedAttribute(Field field, Class<?> type, List<ColumnMapping> columns, List<Field> components,
                                Constructor<?> constructor) implements AttributeMapping {

    @Override
    public void populate(Object entity, Object[] row, ReferenceResolver resolver) {
        Reflect.set(field, entity, instantiate(row));
    }

    /** All-null columns mean "no embedded object", mirroring how a null one is written. */
    private Object instantiate(Object[] row) {
        Object[] values = new Object[columns.size()];
        boolean allNull = true;
        for (int i = 0; i < values.length; i++) {
            ColumnMapping column = columns.get(i);
            Object databaseValue = row[column.index()];
            allNull &= databaseValue == null;
            Object value = column.toJava(databaseValue);
            values[i] = value != null ? value : Reflect.defaultValue(components.get(i).getType());
        }
        if (allNull) {
            return null;
        }
        if (type.isRecord()) {
            return Reflect.instantiate(constructor, values);
        }
        Object embeddable = Reflect.instantiate(constructor);
        for (int i = 0; i < values.length; i++) {
            Reflect.set(components.get(i), embeddable, values[i]);
        }
        return embeddable;
    }
}
