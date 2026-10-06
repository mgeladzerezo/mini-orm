package io.github.mgeladzerezo.miniorm;

import io.github.mgeladzerezo.miniorm.annotation.FetchType;
import io.github.mgeladzerezo.miniorm.annotation.GenerationType;
import io.github.mgeladzerezo.miniorm.dialect.Dialect;
import io.github.mgeladzerezo.miniorm.error.EntityNotFoundException;
import io.github.mgeladzerezo.miniorm.error.LazyInitializationException;
import io.github.mgeladzerezo.miniorm.error.OptimisticLockException;
import io.github.mgeladzerezo.miniorm.error.OrmException;
import io.github.mgeladzerezo.miniorm.error.PersistenceException;
import io.github.mgeladzerezo.miniorm.error.TransactionException;
import io.github.mgeladzerezo.miniorm.internal.EntityEntry;
import io.github.mgeladzerezo.miniorm.internal.EntityEntry.State;
import io.github.mgeladzerezo.miniorm.internal.EntityKey;
import io.github.mgeladzerezo.miniorm.internal.EntitySql;
import io.github.mgeladzerezo.miniorm.internal.Flusher;
import io.github.mgeladzerezo.miniorm.internal.LazyCollection;
import io.github.mgeladzerezo.miniorm.internal.LazyList;
import io.github.mgeladzerezo.miniorm.internal.LazySet;
import io.github.mgeladzerezo.miniorm.internal.PersistenceContext;
import io.github.mgeladzerezo.miniorm.internal.QueryContext;
import io.github.mgeladzerezo.miniorm.internal.Reflect;
import io.github.mgeladzerezo.miniorm.internal.RowReader;
import io.github.mgeladzerezo.miniorm.internal.StatementExecutor;
import io.github.mgeladzerezo.miniorm.mapping.AttributeMapping;
import io.github.mgeladzerezo.miniorm.mapping.BasicAttribute;
import io.github.mgeladzerezo.miniorm.mapping.ColumnMapping;
import io.github.mgeladzerezo.miniorm.mapping.EmbeddedAttribute;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.mapping.ManyToOneAttribute;
import io.github.mgeladzerezo.miniorm.mapping.MetadataRegistry;
import io.github.mgeladzerezo.miniorm.mapping.OneToManyAttribute;
import io.github.mgeladzerezo.miniorm.mapping.ReferenceResolver;
import io.github.mgeladzerezo.miniorm.proxy.EntityProxy;
import io.github.mgeladzerezo.miniorm.proxy.LazyInitializer;
import io.github.mgeladzerezo.miniorm.query.Query;
import io.github.mgeladzerezo.miniorm.query.RowMapper;
import io.github.mgeladzerezo.miniorm.repository.Repository;
import io.github.mgeladzerezo.miniorm.repository.RepositoryFactory;
import io.github.mgeladzerezo.miniorm.type.TypeRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A unit of work: a persistence context (identity map plus change tracking) bound to one JDBC
 * connection.
 *
 * <p>Entities passed to {@link #persist} or returned by {@link #find} and queries are
 * <em>managed</em>: the session remembers a snapshot of each one's column values and, at flush,
 * compares it with the current state to decide which UPDATEs to send. Within one session a row
 * is represented by exactly one instance.
 *
 * <p>A session takes its connection from the {@code DataSource} on first use and returns it on
 * {@link #close()}. It is <strong>not thread-safe</strong>; use one per thread or request.
 * Writes are only possible inside {@link #inTransaction}; reads also work outside one.
 */
public final class Session implements AutoCloseable {

    private static final int IN_LIST_LIMIT = 500;

    private final MiniOrm orm;
    private final MetadataRegistry registry;
    private final Dialect dialect;
    private final StatementExecutor executor;
    private final PersistenceContext context = new PersistenceContext();
    private final Flusher flusher;
    private final Context queryContext = new Context();
    private final Resolver resolver = new Resolver();
    private final Transaction transaction = new Transaction(this);
    private final List<Eager> pendingEager = new ArrayList<>();

    private Connection connection;
    private boolean closed;
    private boolean finishingLoad;
    private int transactionDepth;
    private boolean rollbackOnly;

    /** A {@code @ManyToOne(fetch = EAGER)} reference whose target the session still has to load. */
    private record Eager(Object owner, ManyToOneAttribute attribute, Object targetId) {
    }

    Session(MiniOrm orm) {
        this.orm = orm;
        this.registry = orm.metadata();
        this.dialect = orm.dialect();
        this.executor = orm.executor();
        this.flusher = new Flusher(context, orm::sql, executor, this::connection, orm.clock(), orm.typeRanks());
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Rolls back an open transaction, drops the persistence context and returns the connection
     * to the pool. Lazy proxies and collections still held by the application can no longer be
     * initialized afterwards. Closing twice is harmless.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            if (transactionDepth > 0) {
                rollbackAndClear();
            }
        } finally {
            closed = true;
            context.clear();
            pendingEager.clear();
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    throw new PersistenceException("Could not release the connection", null, e);
                } finally {
                    connection = null;
                }
            }
        }
    }

    /**
     * @return whether {@link #close()} has been called
     */
    public boolean isClosed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("The session is closed");
        }
    }

    private Connection connection() {
        ensureOpen();
        if (connection == null) {
            try {
                connection = orm.dataSource().getConnection();
            } catch (SQLException e) {
                throw new PersistenceException("Could not obtain a connection: " + e.getMessage(), null, e);
            }
        }
        return connection;
    }

    // ------------------------------------------------------------------ transactions

    /**
     * Runs {@code work} in a transaction and commits when it returns.
     *
     * <ul>
     *   <li>Pending changes are flushed just before the commit.</li>
     *   <li>If {@code work} throws, the transaction is rolled back, the persistence context is
     *       cleared (its entities no longer match the database) and the exception propagates.</li>
     *   <li>Called while a transaction is already running, it joins it. If the inner work throws,
     *       the whole transaction is marked rollback-only and the outermost commit fails with a
     *       {@link TransactionException}; use {@link Transaction#inSavepoint} for a part that may
     *       fail without ending the transaction.</li>
     * </ul>
     *
     * @param work the unit of work
     * @param <R>  result type
     * @return what {@code work} returned
     */
    public <R> R inTransaction(Function<? super Transaction, ? extends R> work) {
        ensureOpen();
        boolean outermost = transactionDepth == 0;
        if (outermost) {
            begin();
        }
        transactionDepth++;
        R result;
        try {
            result = work.apply(transaction);
        } catch (RuntimeException | Error e) {
            transactionDepth--;
            if (outermost) {
                rollbackAndClear();
            } else {
                rollbackOnly = true;
            }
            throw e;
        }
        transactionDepth--;
        if (outermost) {
            commit();
        }
        return result;
    }

    /**
     * Like {@link #inTransaction(Function)} for work without a result.
     *
     * @param work the unit of work
     */
    public void runInTransaction(Consumer<? super Transaction> work) {
        inTransaction(tx -> {
            work.accept(tx);
            return null;
        });
    }

    /**
     * @return whether a transaction is running
     */
    public boolean isInTransaction() {
        return transactionDepth > 0;
    }

    private void begin() {
        try {
            connection().setAutoCommit(false);
        } catch (SQLException e) {
            throw new TransactionException("Could not begin a transaction: " + e.getMessage(), e);
        }
        rollbackOnly = false;
    }

    private void commit() {
        if (rollbackOnly) {
            rollbackAndClear();
            throw new TransactionException("The transaction was marked rollback-only (an inner unit of work failed"
                    + " or setRollbackOnly was called) and has been rolled back");
        }
        try {
            flusher.flush();
            connection.commit();
            connection.setAutoCommit(true);
        } catch (RuntimeException e) {
            rollbackAndClear();
            throw e;
        } catch (SQLException e) {
            rollbackAndClear();
            throw new TransactionException("Commit failed: " + e.getMessage(), e);
        }
    }

    private void rollbackAndClear() {
        context.clear();
        pendingEager.clear();
        rollbackOnly = false;
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            // The connection is unusable; the pool evicts it on close. The original failure matters more.
            System.getLogger(Session.class.getName()).log(System.Logger.Level.WARNING,
                    "Rollback failed: " + e.getMessage(), e);
        }
    }

    private void requireTransaction(String operation) {
        ensureOpen();
        if (transactionDepth == 0) {
            throw new TransactionException(operation + " needs a transaction. Wrap the work in"
                    + " session.inTransaction(tx -> ...)");
        }
    }

    Transaction.Savepoint createSavepoint() {
        requireTransaction("A savepoint");
        flush();
        try {
            return new Transaction.Savepoint(connection().setSavepoint());
        } catch (SQLException e) {
            throw new TransactionException("Could not create a savepoint: " + e.getMessage(), e);
        }
    }

    void rollbackToSavepoint(Transaction.Savepoint savepoint) {
        requireTransaction("Rolling back to a savepoint");
        try {
            connection().rollback(savepoint.jdbc());
        } catch (SQLException e) {
            throw new TransactionException("Could not roll back to the savepoint: " + e.getMessage(), e);
        }
        context.clear();
        pendingEager.clear();
    }

    void releaseSavepoint(Transaction.Savepoint savepoint) {
        requireTransaction("Releasing a savepoint");
        try {
            connection().releaseSavepoint(savepoint.jdbc());
        } catch (SQLException e) {
            throw new TransactionException("Could not release the savepoint: " + e.getMessage(), e);
        }
    }

    void markRollbackOnly() {
        requireTransaction("setRollbackOnly");
        rollbackOnly = true;
    }

    boolean isRollbackOnly() {
        return rollbackOnly;
    }

    // ------------------------------------------------------------------ persist / remove

    /**
     * Makes a new entity managed. Its INSERT runs at the next flush. Sequence and UUID keys are
     * assigned immediately; an identity key is assigned when the INSERT runs. Persisting an
     * already managed entity does nothing; persisting one scheduled for removal cancels the removal.
     * There is no cascading: persist referenced entities yourself.
     *
     * @param entity a new instance of an entity class
     * @throws OrmException if the entity already has a generated id (it is detached: use {@link #merge})
     *                      or has no id although none is generated
     */
    public void persist(Object entity) {
        ensureOpen();
        Objects.requireNonNull(entity, "entity");
        if (entity instanceof EntityProxy) {
            throw new IllegalArgumentException("A lazy proxy cannot be persisted; it stands for an existing row");
        }
        EntityMetadata<?> metadata = registry.get(entity.getClass());
        EntityEntry existing = context.entryOf(entity);
        if (existing != null) {
            if (existing.state == State.REMOVED) {
                existing.state = State.MANAGED;
            }
            return;
        }
        metadata.beforePersist(entity, orm.clock());
        assignId(metadata, entity);
        initializeVersion(metadata, entity);
        Object id = metadata.id(entity);
        if (id != null && context.entryFor(new EntityKey(metadata.entityClass(), id)) != null) {
            throw new OrmException("Another " + metadata.entityName() + " with id " + id
                    + " is already managed by this session");
        }
        context.add(new EntityEntry(entity, metadata, id, null, State.NEW, context.nextSequence()));
    }

    private void assignId(EntityMetadata<?> metadata, Object entity) {
        EntityMetadata.IdGeneration generation = metadata.idGeneration();
        Object current = metadata.id(entity);
        if (generation == null) {
            if (current == null) {
                throw new OrmException(metadata.entityName() + " has no @GeneratedValue, so its id must be assigned"
                        + " before persist");
            }
            return;
        }
        if (current != null) {
            throw new OrmException(metadata.entityName() + " already has the generated id " + current
                    + ". An instance with an id is detached; use merge to attach its state to the session");
        }
        switch (generation.strategy()) {
            case IDENTITY -> {
                // Assigned by the INSERT at flush.
            }
            case UUID -> metadata.setId(entity, UUID.randomUUID());
            case SEQUENCE -> {
                long next = orm.allocator(metadata).next(() -> nextSequenceValue(generation.sequenceName()));
                metadata.setId(entity, metadata.idColumn().javaType() == Integer.class || metadata.idColumn()
                        .javaType() == int.class ? (Object) Math.toIntExact(next) : (Object) next);
            }
        }
    }

    private long nextSequenceValue(String sequence) {
        return executor.query(connection(), dialect.sequenceNextValue(sequence), List.of(), rs -> {
            rs.next();
            return rs.getLong(1);
        });
    }

    private static void initializeVersion(EntityMetadata<?> metadata, Object entity) {
        if (metadata.versionColumn() != null && metadata.version(entity) == null) {
            metadata.setVersion(entity, 0);
        }
    }

    /**
     * Schedules a managed entity's row for deletion at the next flush. A new entity that was
     * never flushed is simply forgotten. References to the entity from other managed entities
     * must be cleared before the flush.
     *
     * @param entity a managed entity or a proxy of one
     * @throws IllegalArgumentException if the session does not manage the entity
     */
    public void remove(Object entity) {
        ensureOpen();
        Object target = unproxy(Objects.requireNonNull(entity, "entity"));
        EntityEntry entry = context.entryOf(target);
        if (entry == null) {
            throw new IllegalArgumentException("The entity is not managed by this session (it is detached or was"
                    + " never persisted). Use find() to load it, or merge() to attach it, before removing it");
        }
        if (entry.state == State.NEW) {
            context.remove(entry);
        } else {
            entry.state = State.REMOVED;
        }
    }

    private Object unproxy(Object entity) {
        return entity instanceof EntityProxy proxy ? proxy.$$miniOrmInitializer().target() : entity;
    }

    /**
     * Copies the state of a possibly detached instance onto the managed instance with the same
     * id (loading it, or persisting a copy if no row exists) and returns the managed one. The
     * argument itself does not become managed.
     *
     * <p>For versioned entities a stale argument (older version than the row) is rejected with
     * {@link OptimisticLockException}: this is what makes "read, edit offline, save" safe.
     * Collections are not merged, and references are re-pointed at managed instances by id.
     *
     * @param detached the instance whose state is wanted
     * @param <T>      entity type
     * @return the managed instance
     */
    @SuppressWarnings("unchecked")
    public <T> T merge(T detached) {
        ensureOpen();
        Objects.requireNonNull(detached, "entity");
        T source = (T) unproxy(detached);
        EntityEntry entry = context.entryOf(source);
        if (entry != null) {
            return source;
        }
        EntityMetadata<T> metadata = (EntityMetadata<T>) registry.get(source.getClass());
        Object id = metadata.id(source);
        T managed = id == null ? null : (T) find(metadata.entityClass(), id);
        if (managed == null) {
            T copy = metadata.newInstance();
            copyState(metadata, source, copy, true);
            if (metadata.idGeneration() != null) {
                metadata.setId(copy, null);
            }
            persist(copy);
            return copy;
        }
        if (metadata.versionColumn() != null) {
            Object stale = metadata.version(source);
            Object current = metadata.version(managed);
            if (stale != null && !stale.equals(current)) {
                throw new OptimisticLockException(metadata.entityClass(), id, stale);
            }
        }
        copyState(metadata, source, managed, false);
        return managed;
    }

    private void copyState(EntityMetadata<?> metadata, Object from, Object to, boolean includeKeys) {
        for (AttributeMapping attribute : metadata.attributes()) {
            switch (attribute) {
                case BasicAttribute basic -> {
                    ColumnMapping column = basic.column();
                    if (includeKeys || column.role() == ColumnMapping.Role.BASIC) {
                        Reflect.set(basic.field(), to, Reflect.get(basic.field(), from));
                    }
                }
                case EmbeddedAttribute embedded -> Reflect.set(embedded.field(), to, Reflect.get(embedded.field(), from));
                case ManyToOneAttribute toOne -> {
                    Object reference = Reflect.get(toOne.field(), from);
                    Object id = reference == null ? null : toOne.targetId(reference);
                    Reflect.set(toOne.field(), to, id == null ? reference : getReference(toOne.targetClass(), id));
                }
                case OneToManyAttribute ignored -> {
                    // Inverse side: the rows are owned by the elements' foreign keys.
                }
            }
        }
    }

    /**
     * Reloads a managed entity from the database, discarding unflushed changes to it.
     *
     * @param entity a managed entity
     * @throws EntityNotFoundException if its row no longer exists
     */
    public void refresh(Object entity) {
        ensureOpen();
        Object target = unproxy(Objects.requireNonNull(entity, "entity"));
        EntityEntry entry = context.entryOf(target);
        if (entry == null || entry.state == State.NEW) {
            throw new IllegalArgumentException("Only an entity loaded or flushed in this session can be refreshed");
        }
        Object[] row = selectRow(entry.metadata, entry.id, LockMode.NONE);
        if (row == null) {
            context.remove(entry);
            throw new EntityNotFoundException(entry.metadata.entityName() + " with id " + entry.id
                    + " no longer exists");
        }
        reload(entry, row);
    }

    private void reload(EntityEntry entry, Object[] row) {
        entry.metadata.populate(entry.entity, row, resolver);
        entry.state = State.MANAGED;
        context.deferSnapshot(entry);
        afterLoad();
    }

    /**
     * Stops managing an entity: it is no longer flushed and its lazy associations can no longer
     * be loaded. A new entity that was never flushed is never inserted.
     *
     * @param entity the entity to forget; ignored if the session does not manage it
     */
    public void detach(Object entity) {
        ensureOpen();
        EntityEntry entry = context.entryOf(unproxyIfLoaded(entity));
        if (entry != null) {
            context.remove(entry);
        }
    }

    private Object unproxyIfLoaded(Object entity) {
        return entity instanceof EntityProxy proxy && proxy.$$miniOrmInitializer().isInitialized()
                ? proxy.$$miniOrmInitializer().target() : entity;
    }

    /**
     * @param entity any object
     * @return whether the session manages it (it is new, loaded, or scheduled for removal)
     */
    public boolean contains(Object entity) {
        return !closed && context.entryOf(unproxyIfLoaded(entity)) != null;
    }

    /** Detaches every entity. Pending changes are discarded, not written. */
    public void clear() {
        ensureOpen();
        context.clear();
        pendingEager.clear();
    }

    // ------------------------------------------------------------------ flush

    /**
     * Writes pending changes: INSERTs of persisted entities, UPDATEs of entities whose state
     * differs from their snapshot, DELETEs of removed ones. Happens automatically before a
     * query and at commit; call it directly to see database errors at a known point.
     *
     * @throws OptimisticLockException when a versioned row changed under this session
     * @throws TransactionException    when no transaction is running
     */
    public void flush() {
        requireTransaction("flush()");
        flusher.flush();
    }

    /**
     * Runs the dirty check without writing anything.
     *
     * @return whether a flush would send at least one statement
     */
    public boolean isDirty() {
        ensureOpen();
        return flusher.hasWork();
    }

    // ------------------------------------------------------------------ find

    /**
     * Loads an entity by primary key, or returns the instance this session already manages for
     * it without touching the database.
     *
     * @param type entity class
     * @param id   primary key
     * @param <T>  entity type
     * @return the entity, or {@code null} if no row exists or it is scheduled for removal
     */
    public <T> T find(Class<T> type, Object id) {
        return find(type, id, LockMode.NONE);
    }

    /**
     * Loads an entity by primary key, optionally locking its row.
     *
     * <p>With a lock the row is always read from the database, after flushing pending changes,
     * and the managed instance is refreshed from it. The lock is held until the transaction ends.
     *
     * @param type     entity class
     * @param id       primary key
     * @param lockMode the pessimistic lock to take; anything but {@code NONE} needs a transaction
     * @param <T>      entity type
     * @return the entity, or {@code null} if no row exists
     * @throws io.github.mgeladzerezo.miniorm.error.PessimisticLockException for {@code FOR_UPDATE_NOWAIT}
     *         (and for a lock wait timeout) when another transaction holds the lock
     */
    public <T> T find(Class<T> type, Object id, LockMode lockMode) {
        ensureOpen();
        Objects.requireNonNull(id, "id");
        EntityMetadata<T> metadata = metadataOf(type);
        Object key = normalizeId(metadata, id);
        EntityEntry entry = context.entryFor(new EntityKey(metadata.entityClass(), key));
        if (lockMode == LockMode.NONE) {
            if (entry != null) {
                return entry.state == State.REMOVED ? null : type.cast(entry.entity);
            }
        } else {
            requireTransaction("A pessimistic lock");
            flusher.flush();
            entry = context.entryFor(new EntityKey(metadata.entityClass(), key));
        }
        Object[] row = selectRow(metadata, key, lockMode);
        if (row == null) {
            return null;
        }
        if (entry != null) {
            reload(entry, row);
            return type.cast(entry.entity);
        }
        T entity = type.cast(queryContext.materialize(metadata, row));
        afterLoad();
        return entity;
    }

    /**
     * Returns a reference to a row without querying: the managed instance if there is one,
     * otherwise a lazy proxy that loads the row on first use. Useful to set a foreign key
     * without loading the target.
     *
     * @param type entity class
     * @param id   primary key
     * @param <T>  entity type
     * @return an instance or proxy; the row's existence is not checked
     */
    public <T> T getReference(Class<T> type, Object id) {
        ensureOpen();
        EntityMetadata<T> metadata = metadataOf(type);
        Object key = normalizeId(metadata, id);
        EntityEntry entry = context.entryFor(new EntityKey(metadata.entityClass(), key));
        if (entry != null) {
            return type.cast(entry.entity);
        }
        return type.cast(proxyFor(metadata, key));
    }

    private Object[] selectRow(EntityMetadata<?> metadata, Object id, LockMode lockMode) {
        return executor.query(connection(), orm.sql(metadata).selectById(lockMode),
                List.of(metadata.idColumn().toDatabase(id)), rs -> rs.next() ? RowReader.read(rs, metadata, 1) : null);
    }

    @SuppressWarnings("unchecked")
    private <T> EntityMetadata<T> metadataOf(Class<T> type) {
        return (EntityMetadata<T>) registry.get(type);
    }

    private static Object normalizeId(EntityMetadata<?> metadata, Object id) {
        Class<?> expected = TypeRegistry.box(metadata.idColumn().javaType());
        if (expected.isInstance(id)) {
            return id;
        }
        if (id instanceof Number number && expected == Long.class) {
            return number.longValue();
        }
        if (id instanceof Number number && expected == Integer.class) {
            return number.intValue();
        }
        throw new IllegalArgumentException(metadata.entityName() + " ids are of type " + expected.getSimpleName()
                + ", not " + id.getClass().getSimpleName());
    }

    // ------------------------------------------------------------------ queries

    /**
     * Starts a type-safe query over an entity.
     *
     * @param type entity class
     * @param <T>  entity type
     * @return a query that selects every row until narrowed
     */
    public <T> Query<T> from(Class<T> type) {
        ensureOpen();
        return new Query<>(queryContext, metadataOf(type));
    }

    /**
     * Runs a SELECT written by hand. Pending changes are flushed first when a transaction is
     * running. Values are bound as parameters; the mapper is called once per row.
     *
     * @param sql        SQL with {@code ?} placeholders
     * @param mapper     converts a row to a result element
     * @param parameters values for the placeholders in order
     * @param <R>        element type
     * @return the mapped rows
     */
    public <R> List<R> nativeQuery(String sql, RowMapper<R> mapper, Object... parameters) {
        ensureOpen();
        queryContext.autoFlush();
        return executor.query(connection(), sql, List.of(parameters), rs -> {
            List<R> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return rows;
        });
    }

    /**
     * Runs an INSERT, UPDATE, DELETE or DDL statement written by hand. The persistence context
     * is not told about the effect, so entities the statement changed are stale until
     * {@link #refresh} or {@link #clear} is called.
     *
     * @param sql        SQL with {@code ?} placeholders
     * @param parameters values for the placeholders in order
     * @return the number of affected rows
     */
    public int nativeUpdate(String sql, Object... parameters) {
        ensureOpen();
        queryContext.autoFlush();
        return executor.update(connection(), sql, List.of(parameters));
    }

    /**
     * Creates an implementation of a repository interface whose derived finders
     * ({@code findByEmailAndStatusOrderByCreatedAtDesc}) are parsed from the method names.
     *
     * @param repositoryType an interface extending {@link Repository}
     * @param <R>            repository type
     * @return a proxy bound to this session
     */
    public <R extends Repository<?, ?>> R repository(Class<R> repositoryType) {
        ensureOpen();
        return RepositoryFactory.create(this, registry, repositoryType);
    }

    // ------------------------------------------------------------------ loading internals

    /** Where a row from any path (find, query, batch) becomes a managed instance. */
    private Object materialize(EntityMetadata<?> metadata, Object[] row) {
        Object id = metadata.idColumn().toJava(row[metadata.idColumn().index()]);
        EntityKey key = new EntityKey(metadata.entityClass(), id);
        EntityEntry existing = context.entryFor(key);
        if (existing != null) {
            return existing.entity;
        }
        Object entity = metadata.newInstance();
        EntityEntry entry = new EntityEntry(entity, metadata, id, null, State.MANAGED, context.nextSequence());
        // Registered before populate so that a cycle of eager references finds this instance.
        context.add(entry);
        Object proxy = context.removeProxy(key);
        if (proxy != null) {
            ((EntityProxy) proxy).$$miniOrmInitializer().setTarget(entity);
        }
        metadata.populate(entity, row, resolver);
        context.deferSnapshot(entry);
        return entity;
    }

    /**
     * Completes a load: resolves queued eager references, then takes the snapshots. Snapshots
     * are taken from the finished instance (not from the raw row) so that dirty checking
     * compares like with like, for example after a converter normalised a value.
     */
    private void afterLoad() {
        if (finishingLoad) {
            return;
        }
        finishingLoad = true;
        try {
            while (!pendingEager.isEmpty()) {
                List<Eager> batch = new ArrayList<>(pendingEager);
                pendingEager.clear();
                resolveEager(batch);
            }
            for (EntityEntry entry : context.takeUnsnapshotted()) {
                entry.snapshot = entry.metadata.snapshot(entry.entity);
            }
        } finally {
            finishingLoad = false;
        }
    }

    private void resolveEager(List<Eager> batch) {
        Map<ManyToOneAttribute, Set<Object>> idsByAttribute = new LinkedHashMap<>();
        for (Eager eager : batch) {
            idsByAttribute.computeIfAbsent(eager.attribute, k -> new LinkedHashSet<>()).add(eager.targetId);
        }
        idsByAttribute.forEach((attribute, ids) -> loadByIds(attribute.target(), ids));
        for (Eager eager : batch) {
            EntityEntry target = context.entryFor(new EntityKey(eager.attribute.targetClass(), eager.targetId));
            if (target == null) {
                throw new EntityNotFoundException(eager.attribute.target().entityName() + " with id "
                        + eager.targetId + " referenced by " + eager.owner.getClass().getSimpleName() + "."
                        + eager.attribute.name() + " does not exist");
            }
            Reflect.set(eager.attribute.field(), eager.owner, target.entity);
        }
    }

    /** Loads the rows with the given ids that the session does not hold yet, with IN queries. */
    private void loadByIds(EntityMetadata<?> metadata, Collection<?> ids) {
        List<Object> missing = new ArrayList<>();
        for (Object id : ids) {
            if (context.entryFor(new EntityKey(metadata.entityClass(), id)) == null) {
                missing.add(id);
            }
        }
        EntitySql statements = orm.sql(metadata);
        for (int from = 0; from < missing.size(); from += IN_LIST_LIMIT) {
            List<Object> chunk = missing.subList(from, Math.min(from + IN_LIST_LIMIT, missing.size()));
            List<Object> parameters = new ArrayList<>(chunk.size());
            chunk.forEach(id -> parameters.add(metadata.idColumn().toDatabase(id)));
            executor.query(connection(), statements.selectWhereIn(metadata.idColumn(), chunk.size()), parameters,
                    rs -> {
                        while (rs.next()) {
                            materialize(metadata, RowReader.read(rs, metadata, 1));
                        }
                        return null;
                    });
        }
    }

    private Object proxyFor(EntityMetadata<?> metadata, Object id) {
        EntityKey key = new EntityKey(metadata.entityClass(), id);
        Object proxy = context.proxyFor(key);
        if (proxy == null) {
            proxy = orm.proxies().create(metadata, new LazyInitializer(metadata.entityClass(), id, this::loadForProxy));
            context.addProxy(key, proxy);
        }
        return proxy;
    }

    private Object loadForProxy(Class<?> entityClass, Object id) {
        if (closed) {
            throw new LazyInitializationException("Cannot load " + entityClass.getSimpleName() + " with id " + id
                    + ": the session that created this reference is closed. Fetch the association in the query"
                    + " (fetch(...)) or use it before closing the session");
        }
        Object entity = find(entityClass, id);
        if (entity == null) {
            throw new EntityNotFoundException(entityClass.getSimpleName() + " with id " + id + " does not exist");
        }
        return entity;
    }

    private List<Object> loadCollection(OneToManyAttribute attribute, Object owner) {
        String what = owner.getClass().getSimpleName() + "." + attribute.name();
        if (closed) {
            throw new LazyInitializationException("Cannot load " + what + ": the session is closed. Fetch the"
                    + " collection in the query (fetch(...)) or iterate it before closing the session");
        }
        EntityEntry ownerEntry = context.entryOf(owner);
        if (ownerEntry == null) {
            throw new LazyInitializationException("Cannot load " + what + ": its owner is detached from the session");
        }
        queryContext.autoFlush();
        EntityMetadata<?> target = attribute.target();
        ColumnMapping foreignKey = attribute.inverse().column();
        String statement = orm.sql(target).selectFrom() + " where " + orm.sql(target).qualified(foreignKey)
                + " = ? order by " + orm.sql(target).qualified(target.idColumn());
        List<Object> elements = executor.query(connection(), statement,
                List.of(ownerEntry.metadata.idColumn().toDatabase(ownerEntry.id)), rs -> {
                    List<Object> loaded = new ArrayList<>();
                    while (rs.next()) {
                        loaded.add(materialize(target, RowReader.read(rs, target, 1)));
                    }
                    return loaded;
                });
        afterLoad();
        return elements;
    }

    private void batchLoadCollections(Collection<?> owners, OneToManyAttribute attribute) {
        Map<Object, Object> unloadedByOwnerId = new LinkedHashMap<>();
        for (Object owner : owners) {
            EntityEntry entry = context.entryOf(owner);
            if (entry != null && Reflect.get(attribute.field(), owner) instanceof LazyCollection lazy
                    && !lazy.isInitialized()) {
                unloadedByOwnerId.put(entry.id, owner);
            }
        }
        if (unloadedByOwnerId.isEmpty()) {
            return;
        }
        EntityMetadata<?> target = attribute.target();
        ColumnMapping foreignKey = attribute.inverse().column();
        Map<Object, List<Object>> grouped = new LinkedHashMap<>();
        unloadedByOwnerId.keySet().forEach(id -> grouped.put(id, new ArrayList<>()));
        List<Object> ids = new ArrayList<>(unloadedByOwnerId.keySet());
        for (int from = 0; from < ids.size(); from += IN_LIST_LIMIT) {
            List<Object> chunk = ids.subList(from, Math.min(from + IN_LIST_LIMIT, ids.size()));
            List<Object> parameters = new ArrayList<>();
            EntityMetadata<?> ownerMetadata = attribute.inverse().target();
            chunk.forEach(id -> parameters.add(ownerMetadata.idColumn().toDatabase(id)));
            executor.query(connection(), orm.sql(target).selectWhereIn(foreignKey, chunk.size()), parameters, rs -> {
                while (rs.next()) {
                    Object[] row = RowReader.read(rs, target, 1);
                    Object child = materialize(target, row);
                    Object ownerId = foreignKey.toJava(row[foreignKey.index()]);
                    grouped.get(ownerId).add(child);
                }
                return null;
            });
        }
        afterLoad();
        grouped.forEach((id, children) ->
                ((LazyCollection) Reflect.get(attribute.field(), unloadedByOwnerId.get(id))).initialize(children));
    }

    private void batchLoadReferences(Collection<?> owners, ManyToOneAttribute attribute) {
        Set<Object> ids = new LinkedHashSet<>();
        for (Object owner : owners) {
            if (Reflect.get(attribute.field(), owner) instanceof EntityProxy proxy
                    && !proxy.$$miniOrmInitializer().isInitialized()) {
                ids.add(proxy.$$miniOrmInitializer().id());
            }
        }
        loadByIds(attribute.target(), ids);
        afterLoad();
        for (Object owner : owners) {
            if (Reflect.get(attribute.field(), owner) instanceof EntityProxy proxy) {
                EntityEntry target = context.entryFor(new EntityKey(attribute.targetClass(),
                        proxy.$$miniOrmInitializer().id()));
                if (target != null) {
                    Reflect.set(attribute.field(), owner, target.entity);
                }
            }
        }
    }

    // ------------------------------------------------------------------ views for other packages

    /** Lets the metadata layer build association values; kept private so the session API stays small. */
    private final class Resolver implements ReferenceResolver {
        @Override
        public Object reference(ManyToOneAttribute attribute, Object owner, Object targetId) {
            EntityKey key = new EntityKey(attribute.targetClass(), targetId);
            EntityEntry entry = context.entryFor(key);
            if (entry != null) {
                return entry.entity;
            }
            if (attribute.fetch() == FetchType.EAGER) {
                pendingEager.add(new Eager(owner, attribute, targetId));
                return null;
            }
            return proxyFor(attribute.target(), targetId);
        }

        @Override
        public Object collection(OneToManyAttribute attribute, Object owner) {
            return attribute.isSet()
                    ? new LazySet<>(() -> loadCollection(attribute, owner))
                    : new LazyList<>(() -> loadCollection(attribute, owner));
        }
    }

    /** What the query package needs from the session. */
    private final class Context implements QueryContext {
        @Override
        public MetadataRegistry metadata() {
            return registry;
        }

        @Override
        public Dialect dialect() {
            return dialect;
        }

        @Override
        public TypeRegistry types() {
            return orm.types();
        }

        @Override
        public EntitySql sql(EntityMetadata<?> metadata) {
            return orm.sql(metadata);
        }

        @Override
        public boolean inTransaction() {
            return transactionDepth > 0;
        }

        @Override
        public void autoFlush() {
            if (transactionDepth > 0 && !context.isEmpty()) {
                flusher.flush();
            }
        }

        @Override
        public LockMode checkLock(LockMode mode) {
            if (mode != LockMode.NONE) {
                requireTransaction("A pessimistic lock");
            }
            return mode;
        }

        @Override
        public <R> R query(String sql, List<Object> parameters, StatementExecutor.ResultHandler<R> handler) {
            return executor.query(connection(), sql, parameters, handler);
        }

        @Override
        public Object materialize(EntityMetadata<?> metadata, Object[] row) {
            return Session.this.materialize(metadata, row);
        }

        @Override
        public void afterLoad() {
            Session.this.afterLoad();
        }

        @Override
        public void batchLoadReferences(Collection<?> owners, ManyToOneAttribute attribute) {
            Session.this.batchLoadReferences(owners, attribute);
        }

        @Override
        public void batchLoadCollections(Collection<?> owners, OneToManyAttribute attribute) {
            Session.this.batchLoadCollections(owners, attribute);
        }

        @Override
        public void initializeCollection(Object owner, OneToManyAttribute attribute, List<?> elements) {
            if (Reflect.get(attribute.field(), owner) instanceof LazyCollection lazy) {
                lazy.initialize(elements);
            }
        }

        @Override
        public void replaceReference(Object owner, ManyToOneAttribute attribute, Object target) {
            Reflect.set(attribute.field(), owner, target);
        }
    }
}
