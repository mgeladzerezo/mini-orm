package io.github.mgeladzerezo.miniorm.internal;

import io.github.mgeladzerezo.miniorm.error.OptimisticLockException;
import io.github.mgeladzerezo.miniorm.error.OrmException;
import io.github.mgeladzerezo.miniorm.error.TransientReferenceException;
import io.github.mgeladzerezo.miniorm.internal.EntityEntry.State;
import io.github.mgeladzerezo.miniorm.mapping.ColumnMapping;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.mapping.ManyToOneAttribute;
import io.github.mgeladzerezo.miniorm.proxy.EntityProxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns the difference between the persistence context and the database into SQL.
 *
 * <p>The order is: INSERTs, UPDATEs, DELETEs. Inserts are ordered by foreign-key dependency
 * (parents first, see {@link TopologicalSort}); deletes use the reverse order. Statements for
 * the same table that end up next to each other are sent as one JDBC batch. UPDATE rows are
 * extracted only after the inserts have run, because a changed foreign key may point at an
 * entity whose database-generated id did not exist before.
 */
public final class Flusher {

    private static final int MAX_BATCH = 500;

    private final PersistenceContext context;
    private final Function<EntityMetadata<?>, EntitySql> sql;
    private final StatementExecutor executor;
    private final Supplier<Connection> connection;
    private final Clock clock;
    private final Map<EntityMetadata<?>, Integer> typeRanks;

    /** One entity to UPDATE: the new row and which columns of it differ from the snapshot. */
    private record Update(EntityEntry entry, Object[] row, BitSet changed) {
    }

    /**
     * @param context    the session's persistence context
     * @param sql        statement cache per entity
     * @param executor   runs statements and translates errors
     * @param connection the session's connection, acquired on first use
     * @param clock      source of {@code @UpdatedAt} timestamps
     * @param typeRanks  position of each entity type in the foreign-key order of the whole model
     */
    public Flusher(PersistenceContext context, Function<EntityMetadata<?>, EntitySql> sql, StatementExecutor executor,
                   Supplier<Connection> connection, Clock clock, Map<EntityMetadata<?>, Integer> typeRanks) {
        this.context = context;
        this.sql = sql;
        this.executor = executor;
        this.connection = connection;
        this.clock = clock;
        this.typeRanks = typeRanks;
    }

    /**
     * Whether a flush would send anything. Runs the dirty check and nothing else.
     *
     * @return true if there is a new, removed or modified entity
     */
    public boolean hasWork() {
        for (EntityEntry entry : context.entries()) {
            if (entry.state != State.MANAGED
                    || !changedColumns(entry, entry.metadata.extractRow(entry.entity)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Writes every pending change. */
    public void flush() {
        List<EntityEntry> news = new ArrayList<>();
        List<EntityEntry> managed = new ArrayList<>();
        List<EntityEntry> removed = new ArrayList<>();
        for (EntityEntry entry : context.entries()) {
            switch (entry.state) {
                case NEW -> news.add(entry);
                case MANAGED -> managed.add(entry);
                case REMOVED -> removed.add(entry);
            }
        }
        insert(news);
        update(managed);
        delete(removed);
    }

    // ------------------------------------------------------------------ inserts

    private void insert(List<EntityEntry> news) {
        if (news.isEmpty()) {
            return;
        }
        Map<EntityEntry, List<EntityEntry>> dependencies = new IdentityHashMap<>();
        for (EntityEntry entry : news) {
            dependencies.put(entry, referencedNewEntries(entry));
        }
        Comparator<EntityEntry> priority = Comparator
                .comparingInt((EntityEntry e) -> typeRanks.getOrDefault(e.metadata, 0))
                .thenComparingInt(e -> e.sequence);
        List<EntityEntry> ordered;
        try {
            ordered = TopologicalSort.sort(news, dependencies::get, priority);
        } catch (TopologicalSort.CycleException e) {
            throw new OrmException("Cannot insert " + e.remaining() + ": the new entities reference each other in a"
                    + " cycle. Persist one of them, flush, then set the reference that closes the cycle.");
        }

        List<EntityEntry> group = new ArrayList<>();
        for (EntityEntry entry : ordered) {
            // A row that needs the generated id of a row in the same batch cannot join that batch.
            if (!group.isEmpty() && (group.size() >= MAX_BATCH || group.getFirst().metadata != entry.metadata
                    || entry.metadata.hasIdentityId() && dependsOnAny(dependencies.get(entry), group))) {
                insertGroup(group);
                group = new ArrayList<>();
            }
            group.add(entry);
        }
        insertGroup(group);
    }

    private static boolean dependsOnAny(List<EntityEntry> dependencies, List<EntityEntry> group) {
        for (EntityEntry dependency : dependencies) {
            for (EntityEntry member : group) {
                if (dependency == member) {
                    return true;
                }
            }
        }
        return false;
    }

    private void insertGroup(List<EntityEntry> group) {
        EntityMetadata<?> metadata = group.getFirst().metadata;
        EntitySql statements = sql.apply(metadata);
        List<List<Object>> parameterSets = new ArrayList<>(group.size());
        for (EntityEntry entry : group) {
            Object[] row = metadata.extractRow(entry.entity);
            List<Object> parameters = new ArrayList<>(statements.insertColumns().size());
            for (ColumnMapping column : statements.insertColumns()) {
                parameters.add(row[column.index()]);
            }
            parameterSets.add(parameters);
        }
        if (metadata.hasIdentityId()) {
            ColumnMapping id = metadata.idColumn();
            List<Object> keys = executor.insertReturningKeys(connection.get(), statements.insert(), id.name(),
                    id.databaseType(), parameterSets);
            for (int i = 0; i < group.size(); i++) {
                EntityEntry entry = group.get(i);
                entry.id = id.toJava(keys.get(i));
                metadata.setId(entry.entity, entry.id);
                context.indexKey(entry);
            }
        } else {
            executor.batch(connection.get(), statements.insert(), parameterSets);
        }
        for (EntityEntry entry : group) {
            entry.snapshot = metadata.snapshot(entry.entity);
            entry.state = State.MANAGED;
        }
    }

    // ------------------------------------------------------------------ updates

    private void update(List<EntityEntry> managed) {
        Map<String, List<Update>> bySql = new LinkedHashMap<>();
        for (EntityEntry entry : managed) {
            checkReferences(entry);
            EntityMetadata<?> metadata = entry.metadata;
            Object[] row = metadata.extractRow(entry.entity);
            checkIdUnchanged(entry, row);
            BitSet changed = changedColumns(entry, row);
            if (changed.isEmpty()) {
                continue;
            }
            // Callbacks may change more fields (and @UpdatedAt always does), so look again.
            metadata.beforeUpdate(entry.entity, clock);
            row = metadata.extractRow(entry.entity);
            changed = changedColumns(entry, row);
            bySql.computeIfAbsent(sql.apply(metadata).update(changed), k -> new ArrayList<>())
                    .add(new Update(entry, row, changed));
        }
        for (Map.Entry<String, List<Update>> statement : bySql.entrySet()) {
            List<Update> updates = statement.getValue();
            for (int from = 0; from < updates.size(); from += MAX_BATCH) {
                executeUpdates(statement.getKey(), updates.subList(from, Math.min(from + MAX_BATCH, updates.size())));
            }
        }
    }

    private void executeUpdates(String statement, List<Update> updates) {
        List<List<Object>> parameterSets = new ArrayList<>(updates.size());
        List<Object> newVersions = new ArrayList<>(updates.size());
        for (Update update : updates) {
            EntityMetadata<?> metadata = update.entry.metadata;
            List<Object> parameters = new ArrayList<>();
            for (int i = update.changed.nextSetBit(0); i >= 0; i = update.changed.nextSetBit(i + 1)) {
                parameters.add(update.row[i]);
            }
            ColumnMapping version = metadata.versionColumn();
            Object newVersion = null;
            if (version != null) {
                newVersion = increment(update.entry.snapshot[version.index()]);
                parameters.add(newVersion);
            }
            parameters.add(update.entry.snapshot[metadata.idColumn().index()]);
            if (version != null) {
                parameters.add(update.entry.snapshot[version.index()]);
            }
            parameterSets.add(parameters);
            newVersions.add(newVersion);
        }
        int[] counts = executor.batch(connection.get(), statement, parameterSets);
        for (int i = 0; i < updates.size(); i++) {
            Update update = updates.get(i);
            EntityMetadata<?> metadata = update.entry.metadata;
            requireRowAffected(counts[i], update.entry);
            if (metadata.versionColumn() != null) {
                metadata.setVersion(update.entry.entity, ((Number) newVersions.get(i)).longValue());
            }
            update.entry.snapshot = metadata.snapshot(update.entry.entity);
        }
    }

    private static Object increment(Object version) {
        return version instanceof Integer i ? (Object) (i + 1) : (Object) (((Long) version) + 1);
    }

    /** Columns whose current value differs from the snapshot; only columns an UPDATE may set count. */
    private static BitSet changedColumns(EntityEntry entry, Object[] row) {
        BitSet changed = new BitSet(row.length);
        if (entry.snapshot == null) {
            return changed;
        }
        for (ColumnMapping column : entry.metadata.columns()) {
            if (column.updatable() && !sameValue(entry.snapshot[column.index()], row[column.index()])) {
                changed.set(column.index());
            }
        }
        return changed;
    }

    /** Equality of database-side values: byte arrays by content, decimals by numeric value. */
    private static boolean sameValue(Object a, Object b) {
        if (a instanceof byte[] x && b instanceof byte[] y) {
            return Arrays.equals(x, y);
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static void checkIdUnchanged(EntityEntry entry, Object[] row) {
        int index = entry.metadata.idColumn().index();
        if (!sameValue(entry.snapshot[index], row[index])) {
            throw new OrmException("The id of " + entry + " was changed to " + row[index]
                    + " while the entity was managed; primary keys are immutable. Persist a new entity instead.");
        }
    }

    // ------------------------------------------------------------------ deletes

    private void delete(List<EntityEntry> removed) {
        if (removed.isEmpty()) {
            return;
        }
        // A removed row that is referenced by another removed row must go after it.
        Map<EntityEntry, List<EntityEntry>> referrers = new IdentityHashMap<>();
        for (EntityEntry entry : removed) {
            referrers.put(entry, new ArrayList<>());
        }
        for (EntityEntry entry : removed) {
            for (ManyToOneAttribute attribute : entry.metadata.manyToOnes()) {
                EntityEntry target = entryOfReference(Reflect.get(attribute.field(), entry.entity));
                if (target != null && referrers.containsKey(target)) {
                    referrers.get(target).add(entry);
                }
            }
        }
        Comparator<EntityEntry> priority = Comparator
                .comparingInt((EntityEntry e) -> -typeRanks.getOrDefault(e.metadata, 0))
                .thenComparingInt(e -> e.sequence);
        List<EntityEntry> ordered;
        try {
            ordered = TopologicalSort.sort(removed, referrers::get, priority);
        } catch (TopologicalSort.CycleException e) {
            throw new OrmException("Cannot delete " + e.remaining() + ": the rows reference each other in a cycle."
                    + " Set one of the references to null and flush before removing them.");
        }
        List<EntityEntry> group = new ArrayList<>();
        for (EntityEntry entry : ordered) {
            if (!group.isEmpty() && (group.size() >= MAX_BATCH || group.getFirst().metadata != entry.metadata)) {
                deleteGroup(group);
                group = new ArrayList<>();
            }
            group.add(entry);
        }
        deleteGroup(group);
    }

    private void deleteGroup(List<EntityEntry> group) {
        EntityMetadata<?> metadata = group.getFirst().metadata;
        List<List<Object>> parameterSets = new ArrayList<>(group.size());
        for (EntityEntry entry : group) {
            List<Object> parameters = new ArrayList<>(2);
            parameters.add(entry.snapshot[metadata.idColumn().index()]);
            if (metadata.versionColumn() != null) {
                parameters.add(entry.snapshot[metadata.versionColumn().index()]);
            }
            parameterSets.add(parameters);
        }
        int[] counts = executor.batch(connection.get(), sql.apply(metadata).delete(), parameterSets);
        for (int i = 0; i < group.size(); i++) {
            requireRowAffected(counts[i], group.get(i));
            context.remove(group.get(i));
        }
    }

    /** A count of 0 means the guarded row is gone or has another version. -2 (no info) is trusted. */
    private static void requireRowAffected(int count, EntityEntry entry) {
        if (count == 0) {
            ColumnMapping version = entry.metadata.versionColumn();
            throw new OptimisticLockException(entry.metadata.entityClass(), entry.id,
                    version == null ? "(unversioned)" : entry.snapshot[version.index()]);
        }
    }

    // ------------------------------------------------------------------ references

    /** The NEW entries that {@code entry} points at; fails if it points at an object the session does not know. */
    private List<EntityEntry> referencedNewEntries(EntityEntry entry) {
        List<EntityEntry> news = new ArrayList<>();
        for (ManyToOneAttribute attribute : entry.metadata.manyToOnes()) {
            EntityEntry target = checkReference(entry, attribute);
            if (target != null && target.state == State.NEW) {
                news.add(target);
            }
        }
        return news;
    }

    private void checkReferences(EntityEntry entry) {
        for (ManyToOneAttribute attribute : entry.metadata.manyToOnes()) {
            checkReference(entry, attribute);
        }
    }

    private EntityEntry checkReference(EntityEntry owner, ManyToOneAttribute attribute) {
        Object reference = Reflect.get(attribute.field(), owner.entity);
        if (reference == null) {
            return null;
        }
        EntityEntry target = entryOfReference(reference);
        if (target == null && attribute.targetId(reference) == null) {
            throw new TransientReferenceException(owner.metadata.entityName() + "." + attribute.name()
                    + " refers to a " + attribute.target().entityName() + " that was never persisted. There is no"
                    + " cascading: call persist on it first.");
        }
        if (target != null && target.state == State.REMOVED && owner.state != State.REMOVED) {
            throw new TransientReferenceException(owner.metadata.entityName() + "." + attribute.name()
                    + " refers to " + target + ", which is scheduled for removal");
        }
        return target;
    }

    /** The entry a reference (instance or proxy) stands for, or null if the session does not manage it. */
    private EntityEntry entryOfReference(Object reference) {
        if (reference == null) {
            return null;
        }
        if (reference instanceof EntityProxy proxy) {
            return context.entryFor(new EntityKey(proxy.$$miniOrmInitializer().entityClass(),
                    proxy.$$miniOrmInitializer().id()));
        }
        return context.entryOf(reference);
    }
}
