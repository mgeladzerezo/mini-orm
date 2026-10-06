package io.github.mgeladzerezo.miniorm.mapping;

import io.github.mgeladzerezo.miniorm.annotation.GenerationType;
import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.internal.Reflect;
import io.github.mgeladzerezo.miniorm.proxy.EntityProxy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Everything the ORM knows about one entity class: its table, columns, attributes, key
 * generation and callbacks. Built once by reflection when the session factory starts and
 * immutable afterwards, so it is shared freely between sessions and threads.
 *
 * @param <T> the entity class
 */
public final class EntityMetadata<T> {

    /**
     * How the primary key is generated.
     *
     * @param strategy       generation strategy
     * @param sequenceName   raw sequence name; only for {@link GenerationType#SEQUENCE}
     * @param allocationSize ids reserved per sequence round trip
     */
    public record IdGeneration(GenerationType strategy, String sequenceName, int allocationSize) {
    }

    /**
     * A secondary index declared with {@code @Table(indexes = ...)}.
     *
     * @param name    raw index name
     * @param columns indexed columns in order
     * @param unique  whether the index is unique
     */
    public record IndexDefinition(String name, List<ColumnMapping> columns, boolean unique) {
    }

    private final Class<T> entityClass;
    private final String tableName;
    private final Constructor<T> constructor;
    private final List<ColumnMapping> columns;
    private final List<AttributeMapping> attributes;
    private final Map<String, ColumnMapping> columnsByProperty;
    private final Map<String, AttributeMapping> attributesByName;
    private final ColumnMapping idColumn;
    private final Field idField;
    private final ColumnMapping versionColumn;
    private final Field versionField;
    private final IdGeneration idGeneration;
    private final Field createdAtField;
    private final Field updatedAtField;
    private final List<Method> prePersist;
    private final List<Method> preUpdate;
    private final List<IndexDefinition> indexes;
    private final List<ManyToOneAttribute> manyToOnes;
    private final List<OneToManyAttribute> oneToManys;

    EntityMetadata(Class<T> entityClass, String tableName, Constructor<T> constructor, List<ColumnMapping> columns,
                   List<AttributeMapping> attributes, Map<String, ColumnMapping> columnsByProperty,
                   Field idField, Field versionField, IdGeneration idGeneration, Field createdAtField,
                   Field updatedAtField, List<Method> prePersist, List<Method> preUpdate,
                   List<IndexDefinition> indexes) {
        this.entityClass = entityClass;
        this.tableName = tableName;
        this.constructor = constructor;
        this.columns = List.copyOf(columns);
        this.attributes = List.copyOf(attributes);
        this.columnsByProperty = Map.copyOf(columnsByProperty);
        this.attributesByName = attributes.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(AttributeMapping::name, a -> a));
        this.idField = idField;
        this.idColumn = columnsByProperty.get(idField.getName());
        this.versionField = versionField;
        this.versionColumn = versionField == null ? null : columnsByProperty.get(versionField.getName());
        this.idGeneration = idGeneration;
        this.createdAtField = createdAtField;
        this.updatedAtField = updatedAtField;
        this.prePersist = List.copyOf(prePersist);
        this.preUpdate = List.copyOf(preUpdate);
        this.indexes = List.copyOf(indexes);
        this.manyToOnes = attributes.stream().filter(ManyToOneAttribute.class::isInstance)
                .map(ManyToOneAttribute.class::cast).toList();
        this.oneToManys = attributes.stream().filter(OneToManyAttribute.class::isInstance)
                .map(OneToManyAttribute.class::cast).toList();
    }

    /**
     * @return the mapped class
     */
    public Class<T> entityClass() {
        return entityClass;
    }

    /**
     * @return simple name of the mapped class, used in messages
     */
    public String entityName() {
        return entityClass.getSimpleName();
    }

    /**
     * @return raw (unquoted) table name
     */
    public String tableName() {
        return tableName;
    }

    /**
     * All columns in row order: the layout of the {@code Object[]} used for rows and snapshots.
     *
     * @return the columns
     */
    public List<ColumnMapping> columns() {
        return columns;
    }

    /**
     * @return the mapped fields in declaration order
     */
    public List<AttributeMapping> attributes() {
        return attributes;
    }

    /**
     * @return the {@code @ManyToOne} attributes
     */
    public List<ManyToOneAttribute> manyToOnes() {
        return manyToOnes;
    }

    /**
     * @return the {@code @OneToMany} attributes
     */
    public List<OneToManyAttribute> oneToManys() {
        return oneToManys;
    }

    /**
     * @return the primary key column
     */
    public ColumnMapping idColumn() {
        return idColumn;
    }

    /**
     * @return the {@code @Version} column, or {@code null} if the entity is not versioned
     */
    public ColumnMapping versionColumn() {
        return versionColumn;
    }

    /**
     * @return key generation settings, or {@code null} when the application assigns ids
     */
    public IdGeneration idGeneration() {
        return idGeneration;
    }

    /**
     * @return whether the database assigns the key during INSERT
     */
    public boolean hasIdentityId() {
        return idGeneration != null && idGeneration.strategy() == GenerationType.IDENTITY;
    }

    /**
     * @return indexes declared on {@code @Table}
     */
    public List<IndexDefinition> indexes() {
        return indexes;
    }

    /**
     * Looks up a column by property path.
     *
     * @param property for example {@code email}, {@code address.city}, or a {@code @ManyToOne} field name
     * @return the column
     * @throws MappingException if the entity has no such persistent property
     */
    public ColumnMapping column(String property) {
        ColumnMapping column = columnsByProperty.get(property);
        if (column == null) {
            throw new MappingException(entityName() + " has no persistent property '" + property + "'. Known: "
                    + columnsByProperty.keySet().stream().sorted().toList());
        }
        return column;
    }

    /**
     * @param property a property path
     * @return whether {@link #column(String)} would succeed
     */
    public boolean hasColumn(String property) {
        return columnsByProperty.containsKey(property);
    }

    /**
     * Looks up a mapped field by name.
     *
     * @param name field name
     * @return the attribute
     * @throws MappingException if there is no such mapped field
     */
    public AttributeMapping attribute(String name) {
        AttributeMapping attribute = attributesByName.get(name);
        if (attribute == null) {
            throw new MappingException(entityName() + " has no mapped field '" + name + "'");
        }
        return attribute;
    }

    // ------------------------------------------------------------------ instance access

    /**
     * Creates an empty instance through the no-argument constructor.
     *
     * @return a new, unpopulated entity
     */
    public T newInstance() {
        return Reflect.instantiate(constructor);
    }

    /**
     * Reads the primary key. For a lazy proxy the id is answered without loading the target.
     *
     * @param entity an instance or proxy of the entity
     * @return the id, or {@code null} if not assigned yet
     */
    public Object id(Object entity) {
        if (entity instanceof EntityProxy proxy) {
            return proxy.$$miniOrmInitializer().id();
        }
        return Reflect.get(idField, entity);
    }

    /**
     * Assigns the primary key.
     *
     * @param entity the instance
     * @param id     the new id
     */
    public void setId(Object entity, Object id) {
        Reflect.set(idField, entity, id);
    }

    /**
     * Reads the version field.
     *
     * @param entity the instance
     * @return the version, or {@code null} if the entity is unversioned or the field is unset
     */
    public Object version(Object entity) {
        return versionField == null ? null : Reflect.get(versionField, entity);
    }

    /**
     * Assigns the version field, converting to the field's numeric type.
     *
     * @param entity  the instance
     * @param version the new version
     */
    public void setVersion(Object entity, long version) {
        Class<?> type = versionField.getType();
        Reflect.set(versionField, entity, type == int.class || type == Integer.class
                ? (Object) Math.toIntExact(version) : (Object) version);
    }

    /**
     * Flattens an entity into database-side values, one per column.
     *
     * @param entity the instance
     * @return a new array laid out like {@link #columns()}
     */
    public Object[] extractRow(Object entity) {
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < row.length; i++) {
            row[i] = columns.get(i).extract(entity);
        }
        return row;
    }

    /**
     * Like {@link #extractRow} but safe to keep as a snapshot: a {@code byte[]} is copied,
     * because the entity's own array is mutable and a later in-place change to it would
     * otherwise change the snapshot too, hiding the modification from dirty checking.
     *
     * @param entity the instance
     * @return a new array laid out like {@link #columns()}
     */
    public Object[] snapshot(Object entity) {
        Object[] row = extractRow(entity);
        for (int i = 0; i < row.length; i++) {
            if (row[i] instanceof byte[] bytes) {
                row[i] = bytes.clone();
            }
        }
        return row;
    }

    /**
     * Fills every mapped field of {@code entity} from a row.
     *
     * @param entity   the instance to fill
     * @param row      database-side values laid out like {@link #columns()}
     * @param resolver supplies association values
     */
    public void populate(Object entity, Object[] row, ReferenceResolver resolver) {
        for (AttributeMapping attribute : attributes) {
            attribute.populate(entity, row, resolver);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Runs {@code @PrePersist} callbacks and stamps {@code @CreatedAt} / {@code @UpdatedAt}.
     *
     * @param entity the instance being persisted
     * @param clock  source of the timestamps
     */
    public void beforePersist(Object entity, Clock clock) {
        Instant now = clock.instant();
        stamp(createdAtField, entity, now, clock);
        stamp(updatedAtField, entity, now, clock);
        for (Method callback : prePersist) {
            Reflect.invoke(callback, entity);
        }
    }

    /**
     * Runs {@code @PreUpdate} callbacks and stamps {@code @UpdatedAt}.
     *
     * @param entity the dirty instance about to be updated
     * @param clock  source of the timestamp
     */
    public void beforeUpdate(Object entity, Clock clock) {
        stamp(updatedAtField, entity, clock.instant(), clock);
        for (Method callback : preUpdate) {
            Reflect.invoke(callback, entity);
        }
    }

    private static void stamp(Field field, Object entity, Instant now, Clock clock) {
        if (field == null) {
            return;
        }
        // Microseconds: what the database keeps. See the note in TypeRegistry.
        Instant instant = now.truncatedTo(ChronoUnit.MICROS);
        Class<?> type = field.getType();
        Object value;
        if (type == Instant.class) {
            value = instant;
        } else if (type == LocalDateTime.class) {
            value = LocalDateTime.ofInstant(instant, clock.getZone());
        } else {
            value = OffsetDateTime.ofInstant(instant, clock.getZone());
        }
        Reflect.set(field, entity, value);
    }

    @Override
    public String toString() {
        return "EntityMetadata[" + entityClass.getName() + " -> " + tableName + "]";
    }
}
