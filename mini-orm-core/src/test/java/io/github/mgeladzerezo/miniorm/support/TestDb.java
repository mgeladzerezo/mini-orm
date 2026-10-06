package io.github.mgeladzerezo.miniorm.support;

import io.github.mgeladzerezo.miniorm.MiniOrm;
import io.github.mgeladzerezo.miniorm.model.Address;
import io.github.mgeladzerezo.miniorm.model.Category;
import io.github.mgeladzerezo.miniorm.model.Customer;
import io.github.mgeladzerezo.miniorm.model.OrderLine;
import io.github.mgeladzerezo.miniorm.model.Product;
import io.github.mgeladzerezo.miniorm.model.PurchaseOrder;
import io.github.mgeladzerezo.miniorm.pool.MiniPool;
import io.github.mgeladzerezo.miniorm.pool.PoolConfig;
import io.github.mgeladzerezo.miniorm.sql.QueryCounter;
import io.github.mgeladzerezo.miniorm.type.JsonCodec;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One isolated database per test: a fresh in-memory H2 database, or a fresh schema in a
 * shared PostgreSQL container. The ORM talks to it through the project's own pool, so the two
 * modules are exercised together.
 */
public final class TestDb implements AutoCloseable {

    private static PostgreSQLContainer postgres;

    /** Enough of a JSON codec for a {@code List<String>} column. */
    private static final JsonCodec LIST_CODEC = new JsonCodec() {
        @Override
        public String write(Object value) {
            return "[" + String.join(",", ((List<?>) value).stream().map(Object::toString).toList()) + "]";
        }

        @Override
        public Object read(String json, Type type) {
            String body = json.substring(1, json.length() - 1);
            return body.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(body.split(",")));
        }
    };

    private final Backend backend;
    private final String schema;
    private final MiniPool pool;
    private final MiniOrm orm;
    private final QueryCounter counter = new QueryCounter();

    private TestDb(Backend backend, String schema, MiniPool pool, MiniOrm orm) {
        this.backend = backend;
        this.schema = schema;
        this.pool = pool;
        this.orm = orm;
    }

    private static synchronized PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer("postgres:16-alpine");
            postgres.start();
            Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop));
        }
        return postgres;
    }

    private static final java.util.Map<Backend, TestDb> SHARED = new java.util.EnumMap<>(Backend.class);

    /** Child tables first, so that the rows can be deleted without violating foreign keys. */
    private static final List<String> CLEAN_ORDER = List.of("order_line", "purchase_orders", "customer", "product");

    /**
     * The database shared by all tests of a backend, emptied before use. Creating a schema and
     * a pool per test costs seconds on PostgreSQL; emptying the tables costs milliseconds.
     * Tests that need their own (schema tests, anything that closes the pool) call {@link #open}.
     *
     * @param backend which database
     * @return the shared, empty database
     * @throws Exception if the database cannot be reached
     */
    public static synchronized TestDb shared(Backend backend) throws Exception {
        TestDb db = SHARED.get(backend);
        if (db == null) {
            db = open(backend);
            SHARED.put(backend, db);
            TestDb closing = db;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    closing.close();
                } catch (Exception ignored) {
                    // the JVM is exiting; the container is removed by Testcontainers anyway
                }
            }));
        }
        db.reset();
        return db;
    }

    private void reset() throws Exception {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("update \"category\" set \"parent_id\" = null");
            for (String table : CLEAN_ORDER) {
                statement.executeUpdate("delete from \"" + table + "\"");
            }
            statement.executeUpdate("delete from \"category\"");
        }
        counter.reset();
    }

    /**
     * Opens a database with the test model's tables created.
     *
     * @param backend which database
     * @return an isolated database; close it after the test
     * @throws Exception if the database cannot be reached
     */
    public static TestDb open(Backend backend) throws Exception {
        String schema = "t" + UUID.randomUUID().toString().replace("-", "");
        PoolConfig.Builder pool = PoolConfig.builder().maxSize(8).acquireTimeout(Duration.ofSeconds(20));
        if (backend == Backend.H2) {
            pool.jdbcUrl("jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1");
        } else {
            PostgreSQLContainer container = postgres();
            try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(),
                    container.getUsername(), container.getPassword()); Statement statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
            }
            pool.jdbcUrl(container.getJdbcUrl() + "&currentSchema=" + schema)
                    .username(container.getUsername()).password(container.getPassword());
        }
        MiniPool miniPool = new MiniPool(pool.build());
        MiniOrm orm = MiniOrm.builder().dataSource(miniPool)
                .entities(Customer.class, PurchaseOrder.class, OrderLine.class, Product.class, Category.class)
                .jsonCodec(LIST_CODEC).build();
        TestDb db = new TestDb(backend, schema, miniPool, orm);
        orm.schema().create();
        orm.addListener(db.counter);
        return db;
    }

    public MiniOrm orm() {
        return orm;
    }

    public MiniPool pool() {
        return pool;
    }

    public Backend backend() {
        return backend;
    }

    public QueryCounter counter() {
        return counter;
    }

    /**
     * Opens a raw JDBC connection, to observe what the ORM wrote without going through it.
     *
     * @return a pooled connection; close it
     * @throws Exception if none can be had
     */
    public Connection connection() throws Exception {
        return pool.getConnection();
    }

    /**
     * @param name  customer name
     * @param email customer email
     * @return a customer with an address, ready to persist
     */
    public static Customer customer(String name, String email) {
        Customer customer = new Customer(name, email);
        customer.setAddress(new Address("1 Main St", "Tbilisi"));
        return customer;
    }

    @Override
    public void close() throws Exception {
        pool.close();
        if (backend == Backend.POSTGRES) {
            PostgreSQLContainer container = postgres();
            try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(),
                    container.getUsername(), container.getPassword()); Statement statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
