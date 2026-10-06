package io.github.mgeladzerezo.miniorm;

import io.github.mgeladzerezo.miniorm.dialect.Dialect;
import io.github.mgeladzerezo.miniorm.dialect.Dialects;
import io.github.mgeladzerezo.miniorm.internal.EntitySql;
import io.github.mgeladzerezo.miniorm.internal.LazyCollection;
import io.github.mgeladzerezo.miniorm.internal.ProxyFactory;
import io.github.mgeladzerezo.miniorm.internal.SequenceAllocator;
import io.github.mgeladzerezo.miniorm.internal.StatementExecutor;
import io.github.mgeladzerezo.miniorm.internal.TopologicalSort;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.mapping.ManyToOneAttribute;
import io.github.mgeladzerezo.miniorm.mapping.MetadataRegistry;
import io.github.mgeladzerezo.miniorm.proxy.EntityProxy;
import io.github.mgeladzerezo.miniorm.schema.SchemaManager;
import io.github.mgeladzerezo.miniorm.sql.SqlListener;
import io.github.mgeladzerezo.miniorm.sql.SqlLogger;
import io.github.mgeladzerezo.miniorm.type.JsonCodec;
import io.github.mgeladzerezo.miniorm.type.TypeConverter;
import io.github.mgeladzerezo.miniorm.type.TypeRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * The entry point: entity metadata, dialect and data source, built once and shared by all
 * threads. It hands out {@link Session}s.
 *
 * <pre>{@code
 * MiniOrm orm = MiniOrm.builder().dataSource(pool).entities(User.class, Order.class).build();
 * orm.runInTransaction(session -> session.persist(new User("ada@example.com")));
 * }</pre>
 */
public final class MiniOrm {

    private final DataSource dataSource;
    private final Dialect dialect;
    private final TypeRegistry types;
    private final MetadataRegistry registry;
    private final Clock clock;
    private final List<SqlListener> listeners = new CopyOnWriteArrayList<>();
    private final StatementExecutor executor;
    private final ProxyFactory proxies = new ProxyFactory();
    private final Map<EntityMetadata<?>, EntitySql> statements = new ConcurrentHashMap<>();
    private final Map<String, SequenceAllocator> allocators = new ConcurrentHashMap<>();
    private final Map<EntityMetadata<?>, Integer> typeRanks;

    private MiniOrm(Builder builder) {
        this.dataSource = Objects.requireNonNull(builder.dataSource, "dataSource");
        this.dialect = builder.dialect != null ? builder.dialect : Dialects.detect(dataSource);
        this.types = builder.types;
        this.clock = builder.clock;
        this.listeners.addAll(builder.listeners);
        this.executor = new StatementExecutor(dialect, listeners);
        if (builder.entities.isEmpty()) {
            throw new IllegalStateException("No entity classes registered; call entities(...) on the builder");
        }
        this.registry = new MetadataRegistry(types, builder.jsonCodec, builder.entities);
        for (EntityMetadata<?> metadata : registry.all()) {
            for (ManyToOneAttribute attribute : metadata.manyToOnes()) {
                if (attribute.fetch() == io.github.mgeladzerezo.miniorm.annotation.FetchType.LAZY) {
                    proxies.prepare(attribute.target());
                }
            }
        }
        List<EntityMetadata<?>> all = registry.all();
        Function<EntityMetadata<?>, List<EntityMetadata<?>>> referenced =
                m -> m.manyToOnes().stream().<EntityMetadata<?>>map(ManyToOneAttribute::target).toList();
        this.typeRanks = TopologicalSort.ranks(all, referenced);
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Opens a session. The caller must close it; it holds a connection once used.
     *
     * @return a new session
     */
    public Session openSession() {
        return new Session(this);
    }

    /**
     * Runs {@code work} with a session that is closed afterwards.
     *
     * @param work the unit of work
     * @param <R>  result type
     * @return what {@code work} returned
     */
    public <R> R inSession(Function<? super Session, ? extends R> work) {
        try (Session session = openSession()) {
            return work.apply(session);
        }
    }

    /**
     * Like {@link #inSession(Function)} for work without a result.
     *
     * @param work the unit of work
     */
    public void runInSession(Consumer<? super Session> work) {
        inSession(session -> {
            work.accept(session);
            return null;
        });
    }

    /**
     * Runs {@code work} with a fresh session inside a transaction: commit on return, rollback
     * on exception, session closed in both cases.
     *
     * @param work the unit of work
     * @param <R>  result type
     * @return what {@code work} returned
     */
    public <R> R inTransaction(Function<? super Session, ? extends R> work) {
        return inSession(session -> session.inTransaction(tx -> work.apply(session)));
    }

    /**
     * Like {@link #inTransaction(Function)} for work without a result.
     *
     * @param work the unit of work
     */
    public void runInTransaction(Consumer<? super Session> work) {
        inTransaction(session -> {
            work.accept(session);
            return null;
        });
    }

    /**
     * Registers a listener that sees every statement of every session, including those already open.
     *
     * @param listener for example a {@code QueryCounter} or {@code SqlRecorder}
     * @return this
     */
    public MiniOrm addListener(SqlListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * Removes a listener added earlier.
     *
     * @param listener the listener to remove
     * @return this
     */
    public MiniOrm removeListener(SqlListener listener) {
        listeners.remove(listener);
        return this;
    }

    /**
     * @return schema generation and validation for the registered entities
     */
    public SchemaManager schema() {
        return new SchemaManager(dataSource, dialect, registry);
    }

    /**
     * @return the dialect in use
     */
    public Dialect dialect() {
        return dialect;
    }

    /**
     * @return the entity metadata, built once at startup
     */
    public MetadataRegistry metadata() {
        return registry;
    }

    /**
     * Whether a lazy association has been loaded.
     *
     * @param value an entity, a lazy proxy, or a collection field value
     * @return false for an uninitialized proxy or collection, true otherwise
     */
    public static boolean isInitialized(Object value) {
        if (value instanceof EntityProxy proxy) {
            return proxy.$$miniOrmInitializer().isInitialized();
        }
        return !(value instanceof LazyCollection lazy) || lazy.isInitialized();
    }

    /**
     * Returns the real entity behind a lazy proxy, loading it if necessary.
     *
     * @param value an entity or a proxy
     * @param <T>   entity type
     * @return the underlying instance; {@code value} itself if it is not a proxy
     */
    @SuppressWarnings("unchecked")
    public static <T> T unproxy(T value) {
        return value instanceof EntityProxy proxy ? (T) proxy.$$miniOrmInitializer().target() : value;
    }

    // ------------------------------------------------------------------ for the session

    DataSource dataSource() {
        return dataSource;
    }

    TypeRegistry types() {
        return types;
    }

    Clock clock() {
        return clock;
    }

    StatementExecutor executor() {
        return executor;
    }

    ProxyFactory proxies() {
        return proxies;
    }

    Map<EntityMetadata<?>, Integer> typeRanks() {
        return typeRanks;
    }

    EntitySql sql(EntityMetadata<?> metadata) {
        return statements.computeIfAbsent(metadata, m -> new EntitySql(m, dialect));
    }

    SequenceAllocator allocator(EntityMetadata<?> metadata) {
        EntityMetadata.IdGeneration generation = metadata.idGeneration();
        return allocators.computeIfAbsent(generation.sequenceName(),
                name -> new SequenceAllocator(generation.allocationSize()));
    }

    /** Configures and creates a {@link MiniOrm}. */
    public static final class Builder {
        private DataSource dataSource;
        private Dialect dialect;
        private final TypeRegistry types = new TypeRegistry();
        private final List<Class<?>> entities = new ArrayList<>();
        private JsonCodec jsonCodec;
        private Clock clock = Clock.systemUTC();
        private final List<SqlListener> listeners = new ArrayList<>();

        private Builder() {
        }

        /**
         * @param dataSource where connections come from; a pool, normally
         * @return this
         */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /**
         * Overrides dialect detection.
         *
         * @param dialect the dialect to use
         * @return this
         */
        public Builder dialect(Dialect dialect) {
            this.dialect = dialect;
            return this;
        }

        /**
         * @param entityClasses classes annotated with {@code @Entity}; entities they reference are found automatically
         * @return this
         */
        public Builder entities(Class<?>... entityClasses) {
            entities.addAll(List.of(entityClasses));
            return this;
        }

        /**
         * Registers a converter for a Java type that has none built in.
         *
         * @param javaType  the field type
         * @param converter converts to and from a JDBC-supported type
         * @param <J>       the field type
         * @return this
         */
        public <J> Builder converter(Class<J> javaType, TypeConverter<J, ?> converter) {
            types.register(javaType, converter);
            return this;
        }

        /**
         * @param jsonCodec serializer behind {@code @Json} fields
         * @return this
         */
        public Builder jsonCodec(JsonCodec jsonCodec) {
            this.jsonCodec = jsonCodec;
            return this;
        }

        /**
         * @param clock source of {@code @CreatedAt} and {@code @UpdatedAt} timestamps
         * @return this
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * @param listener observer of every executed statement
         * @return this
         */
        public Builder listener(SqlListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /**
         * Logs every statement with its parameters and timing through {@code System.Logger}.
         *
         * @param slowThreshold statements at least this slow are logged as warnings; zero disables
         * @return this
         */
        public Builder logSql(Duration slowThreshold) {
            return listener(new SqlLogger(slowThreshold));
        }

        /**
         * Logs every statement to a sink such as {@code System.out::println}.
         *
         * @param sink          receives one line per statement
         * @param slowThreshold statements at least this slow are marked as slow; zero disables
         * @return this
         */
        public Builder logSql(Consumer<String> sink, Duration slowThreshold) {
            return listener(new SqlLogger(sink, slowThreshold));
        }

        /**
         * Builds the metadata (reporting any mapping error) and the factory.
         *
         * @return the configured instance
         */
        public MiniOrm build() {
            return new MiniOrm(this);
        }
    }
}
