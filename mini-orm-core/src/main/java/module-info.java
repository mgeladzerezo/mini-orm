/**
 * The ORM. Depends only on {@code java.sql}; bring your own {@code DataSource} and JDBC driver.
 *
 * <p>Applications that are themselves named modules must open the packages containing their
 * entities, embeddables, projection records and query lambdas to this module, because mapping
 * is done by reflection and lazy proxies are defined inside the entity's package:
 * {@code opens com.example.domain to io.github.mgeladzerezo.miniorm.core;}
 */
module io.github.mgeladzerezo.miniorm.core {
    requires transitive java.sql;

    exports io.github.mgeladzerezo.miniorm;
    exports io.github.mgeladzerezo.miniorm.annotation;
    exports io.github.mgeladzerezo.miniorm.dialect;
    exports io.github.mgeladzerezo.miniorm.error;
    exports io.github.mgeladzerezo.miniorm.mapping;
    exports io.github.mgeladzerezo.miniorm.proxy;
    exports io.github.mgeladzerezo.miniorm.query;
    exports io.github.mgeladzerezo.miniorm.repository;
    exports io.github.mgeladzerezo.miniorm.schema;
    exports io.github.mgeladzerezo.miniorm.sql;
    exports io.github.mgeladzerezo.miniorm.type;
}
