package io.github.mgeladzerezo.miniorm.mapping;

import io.github.mgeladzerezo.miniorm.type.TypeConverter;
import java.util.Optional;
import java.util.function.Function;

/**
 * One column of an entity table and the path from the entity object to its value.
 *
 * <p>Columns are the unit that SQL generation, snapshots and dirty checking work with. An
 * entity's state is flattened into an {@code Object[]} with one slot per column, holding
 * <em>database-side</em> values (after the {@link TypeConverter}): embedded objects contribute
 * one slot per component and a {@code @ManyToOne} contributes the referenced entity's id. Using
 * database-side values for snapshots is what makes dirty checking independent of how mutable
 * the Java-side object is.
 */
public final class ColumnMapping {

    /** What part a column plays in its table. */
    public enum Role {
        /** The primary key. */
        ID,
        /** The optimistic-lock version. */
        VERSION,
        /** An ordinary value. */
        BASIC,
        /** The foreign key of a {@code @ManyToOne}. */
        FOREIGN_KEY
    }

    private final int index;
    private final String property;
    private final String name;
    private final Class<?> javaType;
    private final Role role;
    private final boolean nullable;
    private final boolean unique;
    private final boolean updatable;
    private final boolean optional;
    private int length;
    private int precision;
    private int scale;
    private final Function<Object, Object> reader;
    private TypeConverter<Object, Object> converter;
    private ManyToOneAttribute association;

    ColumnMapping(int index, String property, String name, Class<?> javaType, Role role, boolean nullable,
                  boolean unique, boolean updatable, boolean optional, int length, int precision, int scale,
                  Function<Object, Object> reader, TypeConverter<Object, Object> converter) {
        this.index = index;
        this.property = property;
        this.name = name;
        this.javaType = javaType;
        this.role = role;
        this.nullable = nullable;
        this.unique = unique;
        this.updatable = updatable;
        this.optional = optional;
        this.length = length;
        this.precision = precision;
        this.scale = scale;
        this.reader = reader;
        this.converter = converter;
    }

    /** Completes a foreign-key column: it takes the converter and the size of the referenced id column. */
    void link(ManyToOneAttribute association, ColumnMapping targetId) {
        this.association = association;
        this.converter = targetId.converter;
        this.length = targetId.length;
        this.precision = targetId.precision;
        this.scale = targetId.scale;
    }

    TypeConverter<Object, Object> converter() {
        return converter;
    }

    /**
     * Position of this column in the entity's row array.
     *
     * @return zero-based index into {@link EntityMetadata#columns()}
     */
    public int index() {
        return index;
    }

    /**
     * Dotted path of the Java property, for example {@code email}, {@code address.city} or
     * (for a foreign key) the name of the {@code @ManyToOne} field.
     *
     * @return the property path
     */
    public String property() {
        return property;
    }

    /**
     * Raw (unquoted) column name.
     *
     * @return the column name
     */
    public String name() {
        return name;
    }

    /**
     * Declared type of the field. For an {@code Optional<T>} field this is {@code T}; for a
     * foreign key it is the target entity class.
     *
     * @return the Java-side type
     */
    public Class<?> javaType() {
        return javaType;
    }

    /**
     * The JDBC-side type, which decides binding and the DDL type.
     *
     * @return one of {@code TypeRegistry.databaseTypes()}
     */
    public Class<?> databaseType() {
        return converter.databaseType();
    }

    /**
     * @return the part this column plays
     */
    public Role role() {
        return role;
    }

    /**
     * @return whether this is the primary key
     */
    public boolean isId() {
        return role == Role.ID;
    }

    /**
     * @return whether the column accepts NULL
     */
    public boolean nullable() {
        return nullable;
    }

    /**
     * @return whether the schema generator adds a unique constraint
     */
    public boolean unique() {
        return unique;
    }

    /**
     * @return whether the column may appear in the SET clause of an UPDATE
     */
    public boolean updatable() {
        return updatable;
    }

    /**
     * @return length for string columns, 0 for unbounded
     */
    public int length() {
        return length;
    }

    /**
     * @return total digits for decimal columns
     */
    public int precision() {
        return precision;
    }

    /**
     * @return fraction digits for decimal columns
     */
    public int scale() {
        return scale;
    }

    /**
     * The association this column is the foreign key of.
     *
     * @return the {@code @ManyToOne} attribute, or {@code null} for non-FK columns
     */
    public ManyToOneAttribute association() {
        return association;
    }

    /**
     * Converts a Java-side value (the field value, or for a foreign key the referenced id) to
     * what JDBC binds. {@code null} and empty {@code Optional} become {@code null}.
     *
     * @param javaValue the Java-side value
     * @return the database-side value
     */
    public Object toDatabase(Object javaValue) {
        Object value = javaValue;
        if (optional && value instanceof Optional<?> opt) {
            value = opt.orElse(null);
        }
        return value == null ? null : converter.toDatabase(value);
    }

    /**
     * Converts a value read from JDBC to what the field holds. For a foreign key the result is
     * the referenced id, not the entity.
     *
     * @param databaseValue the database-side value, possibly {@code null}
     * @return the Java-side value; an {@code Optional} for optional fields
     */
    public Object toJava(Object databaseValue) {
        Object value = databaseValue == null ? null : converter.fromDatabase(databaseValue);
        return optional ? Optional.ofNullable(value) : value;
    }

    /**
     * Converts a value used in a query predicate. Like {@link #toDatabase} but a foreign-key
     * column also accepts an instance (or proxy) of the target entity and uses its id.
     *
     * @param value the predicate operand
     * @return the value to bind
     */
    public Object toDatabaseForQuery(Object value) {
        if (association != null && value != null && association.targetClass().isInstance(value)) {
            return toDatabase(association.targetId(value));
        }
        return toDatabase(value);
    }

    /**
     * Reads this column's current database-side value out of an entity instance.
     *
     * @param entity an instance of the owning entity class
     * @return the value that would be written to the column
     */
    public Object extract(Object entity) {
        Object value = reader.apply(entity);
        if (association != null && value != null) {
            value = association.targetId(value);
        }
        return toDatabase(value);
    }

    @Override
    public String toString() {
        return property + " -> " + name;
    }
}
