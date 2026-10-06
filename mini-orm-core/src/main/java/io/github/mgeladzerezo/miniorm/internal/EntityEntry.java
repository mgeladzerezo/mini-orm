package io.github.mgeladzerezo.miniorm.internal;

import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;

/**
 * What the session remembers about one managed instance.
 *
 * <p>{@code snapshot} is the row as the database last knew it (one database-side value per
 * column). Dirty checking is a comparison of that array with a fresh extraction from the
 * instance; nothing is intercepted and entities need no base class.
 */
public final class EntityEntry {

    /** Lifecycle of an instance inside a session. */
    public enum State {
        /** Passed to {@code persist}; its INSERT runs at the next flush. */
        NEW,
        /** In sync with a row, apart from changes the next flush will detect. */
        MANAGED,
        /** Passed to {@code remove}; its DELETE runs at the next flush. */
        REMOVED
    }

    public final Object entity;
    public final EntityMetadata<?> metadata;
    public Object id;
    public Object[] snapshot;
    public State state;
    /** Order of {@code persist} calls, the tie-break when ordering inserts. */
    public final int sequence;

    public EntityEntry(Object entity, EntityMetadata<?> metadata, Object id, Object[] snapshot, State state,
                       int sequence) {
        this.entity = entity;
        this.metadata = metadata;
        this.id = id;
        this.snapshot = snapshot;
        this.state = state;
        this.sequence = sequence;
    }

    public EntityKey key() {
        return new EntityKey(metadata.entityClass(), id);
    }

    @Override
    public String toString() {
        return metadata.entityName() + "#" + id + " (" + state + ")";
    }
}
