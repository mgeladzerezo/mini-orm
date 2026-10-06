package io.github.mgeladzerezo.miniorm.internal;

import io.github.mgeladzerezo.miniorm.LockMode;
import io.github.mgeladzerezo.miniorm.dialect.Dialect;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.mapping.ManyToOneAttribute;
import io.github.mgeladzerezo.miniorm.mapping.MetadataRegistry;
import io.github.mgeladzerezo.miniorm.mapping.OneToManyAttribute;
import io.github.mgeladzerezo.miniorm.type.TypeRegistry;
import java.util.Collection;
import java.util.List;

/**
 * What the query API needs from a session. Implemented by the session; kept as an interface in
 * a non-exported package so the session's public surface stays small while the query classes,
 * which live in another package, can still reach the persistence context.
 */
public interface QueryContext {

    MetadataRegistry metadata();

    Dialect dialect();

    TypeRegistry types();

    EntitySql sql(EntityMetadata<?> metadata);

    boolean inTransaction();

    /** Verifies that the requested lock can be taken (it needs a transaction); returns it unchanged. */
    LockMode checkLock(LockMode mode);

    /** Points an owner's reference field at the loaded target, replacing a proxy. */
    void replaceReference(Object owner, ManyToOneAttribute attribute, Object target);

    /** Flushes pending changes so the query sees them. */
    void autoFlush();

    <R> R query(String sql, List<Object> parameters, StatementExecutor.ResultHandler<R> handler);

    /** Returns the managed instance for a row, creating and registering it if it is new to the session. */
    Object materialize(EntityMetadata<?> metadata, Object[] row);

    /** Completes a load: resolves eager references queued while rows were materialized. */
    void afterLoad();

    /** Loads, with one IN query, the targets of {@code attribute} that are still uninitialized proxies. */
    void batchLoadReferences(Collection<?> owners, ManyToOneAttribute attribute);

    /** Loads, with one IN query, the still-unloaded {@code attribute} collections of the owners. */
    void batchLoadCollections(Collection<?> owners, OneToManyAttribute attribute);

    /** Hands an already-fetched element list to an owner's lazy collection. */
    void initializeCollection(Object owner, OneToManyAttribute attribute, List<?> elements);
}
