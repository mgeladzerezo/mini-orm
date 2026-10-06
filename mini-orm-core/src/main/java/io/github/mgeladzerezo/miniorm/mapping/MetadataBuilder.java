package io.github.mgeladzerezo.miniorm.mapping;

import io.github.mgeladzerezo.miniorm.annotation.Column;
import io.github.mgeladzerezo.miniorm.annotation.Convert;
import io.github.mgeladzerezo.miniorm.annotation.CreatedAt;
import io.github.mgeladzerezo.miniorm.annotation.Embedded;
import io.github.mgeladzerezo.miniorm.annotation.Entity;
import io.github.mgeladzerezo.miniorm.annotation.EnumType;
import io.github.mgeladzerezo.miniorm.annotation.Enumerated;
import io.github.mgeladzerezo.miniorm.annotation.GeneratedValue;
import io.github.mgeladzerezo.miniorm.annotation.GenerationType;
import io.github.mgeladzerezo.miniorm.annotation.Id;
import io.github.mgeladzerezo.miniorm.annotation.Index;
import io.github.mgeladzerezo.miniorm.annotation.Json;
import io.github.mgeladzerezo.miniorm.annotation.ManyToOne;
import io.github.mgeladzerezo.miniorm.annotation.OneToMany;
import io.github.mgeladzerezo.miniorm.annotation.PrePersist;
import io.github.mgeladzerezo.miniorm.annotation.PreUpdate;
import io.github.mgeladzerezo.miniorm.annotation.Table;
import io.github.mgeladzerezo.miniorm.annotation.Transient;
import io.github.mgeladzerezo.miniorm.annotation.UpdatedAt;
import io.github.mgeladzerezo.miniorm.annotation.Version;
import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.internal.Reflect;
import io.github.mgeladzerezo.miniorm.mapping.ColumnMapping.Role;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata.IdGeneration;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata.IndexDefinition;
import io.github.mgeladzerezo.miniorm.type.JsonCodec;
import io.github.mgeladzerezo.miniorm.type.TypeConverter;
import io.github.mgeladzerezo.miniorm.type.TypeRegistry;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads the annotations of one entity class into an {@link EntityMetadata}. All mapping
 * mistakes are reported here, with the class and field named, so they surface when the
 * session factory is built instead of at the first query that happens to touch the field.
 */
final class MetadataBuilder {

    private static final Set<Class<?>> VERSION_TYPES = Set.of(int.class, long.class, Integer.class, Long.class);
    private static final Set<Class<?>> TIMESTAMP_TYPES = Set.of(Instant.class, LocalDateTime.class, OffsetDateTime.class);

    private final TypeRegistry types;
    private final JsonCodec jsonCodec;

    MetadataBuilder(TypeRegistry types, JsonCodec jsonCodec) {
        this.types = types;
        this.jsonCodec = jsonCodec;
    }

    /** Mutable state while one class is being scanned. */
    private static final class Scan {
        final List<ColumnMapping> columns = new ArrayList<>();
        final List<AttributeMapping> attributes = new ArrayList<>();
        final Map<String, ColumnMapping> byProperty = new LinkedHashMap<>();
        Field id;
        Field version;
        Field createdAt;
        Field updatedAt;
    }

    <T> EntityMetadata<T> build(Class<T> type) {
        if (!type.isAnnotationPresent(Entity.class)) {
            throw new MappingException(type.getName() + " is not annotated with @Entity");
        }
        if (type.isInterface() || type.isRecord() || type.isEnum() || Modifier.isAbstract(type.getModifiers())) {
            throw new MappingException(type.getName() + " cannot be an entity: entities are concrete classes."
                    + " Records are supported as embeddables and as query projections.");
        }
        Constructor<T> constructor = Reflect.noArgConstructor(type);
        Table table = type.getAnnotation(Table.class);
        String tableName = table != null && !table.name().isEmpty() ? table.name() : Naming.snakeCase(type.getSimpleName());

        Scan scan = new Scan();
        for (Field field : Reflect.instanceFields(type)) {
            if (Modifier.isTransient(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            if (Modifier.isFinal(field.getModifiers())) {
                throw error(field, "is final; persistent fields must be assignable (or mark it @Transient)");
            }
            Reflect.open(field, type);
            scanField(field, scan);
        }
        if (scan.id == null) {
            throw new MappingException(type.getName() + " has no @Id field");
        }
        return new EntityMetadata<>(type, tableName, constructor, scan.columns, scan.attributes, scan.byProperty,
                scan.id, scan.version, idGeneration(scan.id, tableName), scan.createdAt, scan.updatedAt,
                callbacks(type, PrePersist.class), callbacks(type, PreUpdate.class), indexes(type, tableName, scan));
    }

    private void scanField(Field field, Scan scan) {
        Function<Object, Object> reader = entity -> Reflect.get(field, entity);
        if (field.isAnnotationPresent(OneToMany.class)) {
            scan.attributes.add(oneToMany(field));
        } else if (field.isAnnotationPresent(ManyToOne.class)) {
            Column column = field.getAnnotation(Column.class);
            String name = column != null && !column.name().isEmpty()
                    ? column.name() : Naming.snakeCase(field.getName()) + "_id";
            ColumnMapping fk = new ColumnMapping(scan.columns.size(), field.getName(), name, field.getType(),
                    Role.FOREIGN_KEY, column == null || column.nullable(), column != null && column.unique(),
                    column == null || column.updatable(), false, 0, 0, 0, reader, null);
            add(scan, fk, field);
            scan.attributes.add(new ManyToOneAttribute(field, field.getAnnotation(ManyToOne.class).fetch(), fk));
        } else if (field.isAnnotationPresent(Embedded.class)) {
            scan.attributes.add(embedded(field, scan));
        } else {
            Role role = Role.BASIC;
            if (field.isAnnotationPresent(Id.class)) {
                if (scan.id != null) {
                    throw error(field, "is a second @Id; composite keys are not supported");
                }
                scan.id = field;
                role = Role.ID;
            } else if (field.isAnnotationPresent(Version.class)) {
                if (!VERSION_TYPES.contains(field.getType())) {
                    throw error(field, "is a @Version field and must be int, long, Integer or Long");
                }
                scan.version = field;
                role = Role.VERSION;
            }
            if (field.isAnnotationPresent(CreatedAt.class)) {
                scan.createdAt = timestampField(field, CreatedAt.class);
            }
            if (field.isAnnotationPresent(UpdatedAt.class)) {
                scan.updatedAt = timestampField(field, UpdatedAt.class);
            }
            boolean notNull = role != Role.BASIC || field.getType().isPrimitive();
            ColumnMapping column = basicColumn(scan.columns.size(), field.getName(), "", field, role, reader, notNull);
            add(scan, column, field);
            scan.attributes.add(new BasicAttribute(field, column));
        }
    }

    private static void add(Scan scan, ColumnMapping column, Field field) {
        for (ColumnMapping existing : scan.columns) {
            if (existing.name().equalsIgnoreCase(column.name())) {
                throw error(field, "maps to column '" + column.name() + "', which is already used by property '"
                        + existing.property() + "'");
            }
        }
        scan.columns.add(column);
        scan.byProperty.put(column.property(), column);
    }

    /** Builds the column of a basic field; {@code prefix} is the embedded prefix, if any. */
    private ColumnMapping basicColumn(int index, String property, String prefix, Field field, Role role,
                                      Function<Object, Object> reader, boolean notNull) {
        Column column = field.getAnnotation(Column.class);
        Class<?> javaType = field.getType();
        boolean optional = javaType == Optional.class;
        if (optional) {
            javaType = typeArgument(field, "Optional");
        }
        boolean json = field.isAnnotationPresent(Json.class);
        String name = prefix + (column != null && !column.name().isEmpty()
                ? column.name() : Naming.snakeCase(field.getName()));
        int length = column != null ? column.length() : json ? 0 : 255;
        return new ColumnMapping(index, property, name, javaType, role,
                !notNull && (column == null || column.nullable()), column != null && column.unique(),
                role == Role.BASIC && (column == null || column.updatable()), optional, length,
                column != null ? column.precision() : 19, column != null ? column.scale() : 2,
                reader, converter(field, javaType));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private TypeConverter<Object, Object> converter(Field field, Class<?> javaType) {
        TypeConverter<?, ?> converter;
        Convert convert = field.getAnnotation(Convert.class);
        if (convert != null) {
            converter = Reflect.instantiate(Reflect.noArgConstructor(convert.value()));
            TypeRegistry.validate(converter, Reflect.describe(field));
        } else if (field.isAnnotationPresent(Json.class)) {
            if (jsonCodec == null) {
                throw error(field, "is annotated with @Json but no JsonCodec was configured on the builder");
            }
            Type genericType = field.getGenericType();
            converter = TypeConverter.of(String.class, jsonCodec::write, text -> jsonCodec.read(text, genericType));
        } else if (javaType.isEnum()) {
            Enumerated enumerated = field.getAnnotation(Enumerated.class);
            converter = enumerated != null && enumerated.value() == EnumType.ORDINAL
                    ? TypeRegistry.enumByOrdinal((Class) javaType) : TypeRegistry.enumByName((Class) javaType);
        } else if (types.supports(javaType)) {
            converter = types.converterFor(javaType);
        } else {
            throw error(field, "has type " + javaType.getName() + ", for which no TypeConverter is registered."
                    + " Register one, or use @Convert, @Json, @Embedded, @ManyToOne or @Transient");
        }
        return (TypeConverter<Object, Object>) converter;
    }

    private EmbeddedAttribute embedded(Field field, Scan scan) {
        Class<?> type = field.getType();
        String prefix = field.getAnnotation(Embedded.class).prefix();
        List<Field> components = new ArrayList<>();
        List<ColumnMapping> columns = new ArrayList<>();
        for (Field component : Reflect.instanceFields(type)) {
            if (Modifier.isTransient(component.getModifiers()) || component.isAnnotationPresent(Transient.class)) {
                if (type.isRecord()) {
                    throw error(component, "is a record component and cannot be @Transient");
                }
                continue;
            }
            if (component.isAnnotationPresent(Embedded.class) || component.isAnnotationPresent(ManyToOne.class)
                    || component.isAnnotationPresent(OneToMany.class) || component.isAnnotationPresent(Id.class)) {
                throw error(component, "is inside an embeddable; embeddables may only contain basic fields");
            }
            Reflect.open(component, type);
            Function<Object, Object> reader = entity -> {
                Object embeddable = Reflect.get(field, entity);
                return embeddable == null ? null : Reflect.get(component, embeddable);
            };
            // notNull=false even for primitives: a null embedded object is stored as all-NULL columns.
            ColumnMapping column = basicColumn(scan.columns.size(), field.getName() + "." + component.getName(),
                    prefix, component, Role.BASIC, reader, false);
            add(scan, column, component);
            components.add(component);
            columns.add(column);
        }
        if (columns.isEmpty()) {
            throw error(field, "is @Embedded but " + type.getName() + " has no persistent fields");
        }
        Constructor<?> constructor;
        if (type.isRecord()) {
            Class<?>[] signature = Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getType).toArray(Class<?>[]::new);
            try {
                constructor = Reflect.open(type.getDeclaredConstructor(signature), type);
            } catch (NoSuchMethodException e) {
                throw new MappingException("Record " + type.getName() + " has no canonical constructor", e);
            }
        } else {
            constructor = Reflect.noArgConstructor(type);
        }
        return new EmbeddedAttribute(field, type, List.copyOf(columns), List.copyOf(components), constructor);
    }

    private static OneToManyAttribute oneToMany(Field field) {
        Class<?> type = field.getType();
        if (type != List.class && type != Set.class && type != Collection.class) {
            throw error(field, "is @OneToMany and must be declared as List, Set or Collection, not " + type.getName());
        }
        return new OneToManyAttribute(field, typeArgument(field, "the collection"), field.getAnnotation(OneToMany.class).mappedBy());
    }

    private static Class<?> typeArgument(Field field, String what) {
        if (field.getGenericType() instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> argument) {
            return argument;
        }
        throw error(field, "must declare a concrete type argument for " + what);
    }

    private static Field timestampField(Field field, Class<? extends Annotation> annotation) {
        if (!TIMESTAMP_TYPES.contains(field.getType())) {
            throw error(field, "is @" + annotation.getSimpleName()
                    + " and must be Instant, LocalDateTime or OffsetDateTime");
        }
        return field;
    }

    private static IdGeneration idGeneration(Field id, String tableName) {
        GeneratedValue generated = id.getAnnotation(GeneratedValue.class);
        if (generated == null) {
            return null;
        }
        Class<?> type = id.getType();
        if (generated.strategy() == GenerationType.UUID) {
            if (type != UUID.class) {
                throw error(id, "uses GenerationType.UUID and must be of type java.util.UUID");
            }
        } else if (type != Long.class && type != Integer.class) {
            throw error(id, "is a generated id and must be Long or Integer (a wrapper, because null means"
                    + " 'not assigned yet')");
        }
        if (generated.allocationSize() < 1) {
            throw error(id, "has allocationSize " + generated.allocationSize() + "; it must be at least 1");
        }
        String sequence = generated.sequence().isEmpty() ? tableName + "_seq" : generated.sequence();
        return new IdGeneration(generated.strategy(), sequence, generated.allocationSize());
    }

    private static List<Method> callbacks(Class<?> type, Class<? extends Annotation> annotation) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(annotation)) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                    throw new MappingException(type.getName() + "." + method.getName() + "() is @"
                            + annotation.getSimpleName() + " and must be a no-argument instance method");
                }
                methods.addFirst(Reflect.open(method, type));
            }
        }
        return methods;
    }

    private static List<IndexDefinition> indexes(Class<?> type, String tableName, Scan scan) {
        Table table = type.getAnnotation(Table.class);
        if (table == null) {
            return List.of();
        }
        List<IndexDefinition> indexes = new ArrayList<>();
        for (Index index : table.indexes()) {
            List<ColumnMapping> columns = new ArrayList<>();
            for (String property : index.properties()) {
                ColumnMapping column = scan.byProperty.get(property);
                if (column == null) {
                    throw new MappingException(type.getName() + ": @Index refers to unknown property '" + property
                            + "'. Known: " + scan.byProperty.keySet());
                }
                columns.add(column);
            }
            if (columns.isEmpty()) {
                throw new MappingException(type.getName() + ": @Index needs at least one property");
            }
            String name = !index.name().isEmpty() ? index.name()
                    : "idx_" + tableName + "_" + String.join("_", columns.stream().map(ColumnMapping::name).toList());
            indexes.add(new IndexDefinition(name, List.copyOf(columns), index.unique()));
        }
        return indexes;
    }

    private static MappingException error(Field field, String problem) {
        return new MappingException(field.getDeclaringClass().getName() + "." + field.getName() + " " + problem);
    }
}
