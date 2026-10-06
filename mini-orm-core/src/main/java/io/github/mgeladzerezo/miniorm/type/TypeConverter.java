package io.github.mgeladzerezo.miniorm.type;

import java.util.Objects;
import java.util.function.Function;

/**
 * Converts between the Java type of a mapped field and one of the types JDBC can bind.
 *
 * <p>Neither method is ever called with {@code null}: null handling (including
 * {@code Optional} fields) is done by the mapping layer, so converters stay total functions
 * over real values.
 *
 * @param <J> the Java-side type, as declared on the entity field
 * @param <D> the database-side type; must be one of {@link TypeRegistry#databaseTypes()}
 */
public interface TypeConverter<J, D> {

    /**
     * The type handed to and received from JDBC. It also decides the DDL column type.
     *
     * @return one of {@link TypeRegistry#databaseTypes()}
     */
    Class<D> databaseType();

    /**
     * Converts a field value for binding.
     *
     * @param value a non-null field value
     * @return the value to bind
     */
    D toDatabase(J value);

    /**
     * Converts a column value for assignment to the field.
     *
     * @param value a non-null column value
     * @return the field value
     */
    J fromDatabase(D value);

    /**
     * Builds a converter from two functions.
     *
     * @param databaseType the JDBC-side type
     * @param toDatabase   field value to column value
     * @param fromDatabase column value to field value
     * @param <J>          Java-side type
     * @param <D>          database-side type
     * @return a converter backed by the functions
     */
    static <J, D> TypeConverter<J, D> of(Class<D> databaseType, Function<? super J, ? extends D> toDatabase,
                                         Function<? super D, ? extends J> fromDatabase) {
        Objects.requireNonNull(databaseType, "databaseType");
        Objects.requireNonNull(toDatabase, "toDatabase");
        Objects.requireNonNull(fromDatabase, "fromDatabase");
        return new TypeConverter<>() {
            @Override
            public Class<D> databaseType() {
                return databaseType;
            }

            @Override
            public D toDatabase(J value) {
                return toDatabase.apply(value);
            }

            @Override
            public J fromDatabase(D value) {
                return fromDatabase.apply(value);
            }
        };
    }
}
