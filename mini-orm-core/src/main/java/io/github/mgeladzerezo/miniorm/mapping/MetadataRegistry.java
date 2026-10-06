package io.github.mgeladzerezo.miniorm.mapping;

import io.github.mgeladzerezo.miniorm.annotation.Entity;
import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.type.JsonCodec;
import io.github.mgeladzerezo.miniorm.type.TypeRegistry;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata of every entity class known to a session factory.
 *
 * <p>Metadata is built in two passes because entities refer to each other, possibly in
 * cycles: first each class is scanned on its own, then associations are linked to the
 * metadata of their targets (a foreign key column takes its JDBC type from the target's id
 * column). Entity classes reachable through associations are registered automatically. After
 * construction the registry is immutable, which is what makes it a safe cache: reflection
 * happens once, at startup.
 */
public final class MetadataRegistry {

    private final Map<Class<?>, EntityMetadata<?>> byClass;

    /**
     * Scans and links the given entity classes and everything they reference.
     *
     * @param types         converter registry used for basic fields
     * @param jsonCodec     codec for {@code @Json} fields, or {@code null} if none is configured
     * @param entityClasses the root set of entity classes
     * @throws MappingException describing the first mapping error found
     */
    public MetadataRegistry(TypeRegistry types, JsonCodec jsonCodec, Collection<Class<?>> entityClasses) {
        MetadataBuilder builder = new MetadataBuilder(types, jsonCodec);
        Map<Class<?>, EntityMetadata<?>> built = new LinkedHashMap<>();
        Deque<Class<?>> pending = new ArrayDeque<>(entityClasses);
        while (!pending.isEmpty()) {
            Class<?> type = pending.poll();
            if (built.containsKey(type)) {
                continue;
            }
            EntityMetadata<?> metadata = builder.build(type);
            built.put(type, metadata);
            metadata.manyToOnes().forEach(a -> pending.add(requireEntity(a.targetClass(), metadata, a)));
            metadata.oneToManys().forEach(a -> pending.add(requireEntity(a.targetClass(), metadata, a)));
        }
        built.values().forEach(metadata -> link(metadata, built));
        this.byClass = Collections.unmodifiableMap(built);
    }

    private static Class<?> requireEntity(Class<?> target, EntityMetadata<?> owner, AttributeMapping attribute) {
        if (!target.isAnnotationPresent(Entity.class)) {
            throw new MappingException(owner.entityName() + "." + attribute.name() + " refers to "
                    + target.getName() + ", which is not an @Entity");
        }
        return target;
    }

    private static void link(EntityMetadata<?> metadata, Map<Class<?>, EntityMetadata<?>> all) {
        for (ManyToOneAttribute association : metadata.manyToOnes()) {
            EntityMetadata<?> target = all.get(association.targetClass());
            association.link(target);
            association.column().link(association, target.idColumn());
        }
        for (OneToManyAttribute collection : metadata.oneToManys()) {
            EntityMetadata<?> target = all.get(collection.targetClass());
            String where = metadata.entityName() + "." + collection.name();
            ManyToOneAttribute inverse = target.manyToOnes().stream()
                    .filter(a -> a.name().equals(collection.mappedBy())).findFirst()
                    .orElseThrow(() -> new MappingException(where + ": mappedBy = \"" + collection.mappedBy()
                            + "\" does not name a @ManyToOne field of " + target.entityName()));
            if (inverse.targetClass() != metadata.entityClass()) {
                throw new MappingException(where + ": " + target.entityName() + "." + inverse.name()
                        + " refers to " + inverse.targetClass().getSimpleName() + ", not to " + metadata.entityName());
            }
            collection.link(target, inverse);
        }
    }

    /**
     * Returns the metadata for an entity class. Generated proxy subclasses resolve to the
     * entity they extend.
     *
     * @param entityClass an entity class or a proxy subclass of one
     * @param <T>         the entity type
     * @return its metadata
     * @throws MappingException if the class was not registered
     */
    @SuppressWarnings("unchecked")
    public <T> EntityMetadata<T> get(Class<T> entityClass) {
        for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
            EntityMetadata<?> metadata = byClass.get(c);
            if (metadata != null) {
                return (EntityMetadata<T>) metadata;
            }
        }
        throw new MappingException(entityClass.getName() + " is not a registered entity. Pass it to"
                + " MiniOrm.builder().entities(...).");
    }

    /**
     * @param type any class
     * @return whether it is (a proxy of) a registered entity
     */
    public boolean isEntity(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (byClass.containsKey(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * All registered entities in registration order.
     *
     * @return the metadata objects
     */
    public List<EntityMetadata<?>> all() {
        return List.copyOf(byClass.values());
    }
}
