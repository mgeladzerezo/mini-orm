package io.github.mgeladzerezo.miniorm.query;

import java.util.Objects;

/**
 * A named, typed property: the hand-written equivalent of a generated metamodel field.
 *
 * <pre>{@code
 * static final Attribute<User, String> EMAIL = Attribute.of(User::getEmail);
 * static final Attribute<User, String> CITY  = Attribute.of(User::getAddress).then(Address::city);
 * }</pre>
 *
 * <p>Besides naming nested properties of embedded objects, an {@code Attribute} gives the
 * strictest compile-time checking the query API offers. Its value type is fixed by the
 * declaration, so {@code where(EMAIL, eq(42))} does not compile. With an inline method
 * reference the compiler is free to infer a common supertype of the getter's return type and
 * the operand, so the same mistake is caught at run time instead (before any SQL is sent).
 *
 * @param <T> the entity type
 * @param <V> the property type
 */
public final class Attribute<T, V> implements Property<T, V> {

    private static final long serialVersionUID = 1L;

    private final String name;

    private Attribute(String name) {
        this.name = name;
    }

    /**
     * Creates an attribute from a getter reference.
     *
     * @param getter for example {@code User::getEmail}
     * @param <T>    the entity type
     * @param <V>    the property type
     * @return the attribute
     */
    public static <T, V> Attribute<T, V> of(Property<T, V> getter) {
        return new Attribute<>(PropertyNames.of(Objects.requireNonNull(getter, "getter")));
    }

    /**
     * Creates an attribute from a property path given as text. The path is checked against
     * the entity metadata when a query is rendered; it is never copied into SQL.
     *
     * @param name a property name or dotted path such as {@code address.city}
     * @param <T>  the entity type
     * @param <V>  the property type
     * @return the attribute
     */
    public static <T, V> Attribute<T, V> named(String name) {
        return new Attribute<>(Objects.requireNonNull(name, "name"));
    }

    /**
     * Descends into an embedded object.
     *
     * @param nested getter on the embedded type, for example {@code Address::city}
     * @param <W>    type of the nested property
     * @return an attribute for the dotted path
     */
    public <W> Attribute<T, W> then(Property<V, W> nested) {
        return new Attribute<>(name + "." + PropertyNames.of(nested));
    }

    /**
     * @return the property name or dotted path
     */
    public String name() {
        return name;
    }

    /** Attributes are descriptors; they do not read values. */
    @Override
    public V get(T entity) {
        throw new UnsupportedOperationException("Attribute '" + name + "' names a property; it does not read it");
    }

    @Override
    public String toString() {
        return name;
    }
}
