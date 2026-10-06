package io.github.mgeladzerezo.miniorm.mapping;

/**
 * Supplies the values of association fields while an entity is being built from a row.
 * Implemented by the session, which knows its identity map and how to make lazy placeholders.
 */
public interface ReferenceResolver {

    /**
     * Resolves the target of a {@code @ManyToOne}.
     *
     * @param attribute the association
     * @param owner     the entity being populated
     * @param targetId  the non-null id read from the foreign key column
     * @return the managed target, a lazy proxy for it, or {@code null} if the session will fill
     *         the field itself once the whole result has been read (eager fetching)
     */
    Object reference(ManyToOneAttribute attribute, Object owner, Object targetId);

    /**
     * Creates the collection for a {@code @OneToMany}.
     *
     * @param attribute the association
     * @param owner     the entity being populated
     * @return a lazy list or set
     */
    Object collection(OneToManyAttribute attribute, Object owner);
}
