/**
 * A bounded, fair JDBC connection pool. Usable on its own; it knows nothing about the ORM.
 */
module io.github.mgeladzerezo.miniorm.pool {
    requires transitive java.sql;

    exports io.github.mgeladzerezo.miniorm.pool;
}
