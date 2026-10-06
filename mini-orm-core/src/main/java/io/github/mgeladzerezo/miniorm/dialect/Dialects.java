package io.github.mgeladzerezo.miniorm.dialect;

import io.github.mgeladzerezo.miniorm.error.OrmException;
import io.github.mgeladzerezo.miniorm.error.PersistenceException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/** Picks a {@link Dialect} from what the JDBC driver reports. */
public final class Dialects {

    private Dialects() {
    }

    /**
     * Opens a connection, reads the database product name and returns the matching dialect.
     *
     * @param dataSource where to connect
     * @return the dialect for that database
     * @throws OrmException if the database is not one of the supported ones
     */
    public static Dialect detect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return forProductName(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new PersistenceException("Could not connect to detect the SQL dialect", null, e);
        }
    }

    /**
     * Maps a {@code DatabaseMetaData.getDatabaseProductName()} value to a dialect.
     *
     * @param productName the reported product name
     * @return the dialect
     * @throws OrmException if no dialect matches
     */
    public static Dialect forProductName(String productName) {
        String name = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (name.contains("postgresql")) {
            return new PostgresDialect();
        }
        if (name.equals("h2")) {
            return new H2Dialect();
        }
        throw new OrmException("No built-in dialect for database '" + productName
                + "'. Implement Dialect and pass it to MiniOrm.builder().dialect(...).");
    }
}
