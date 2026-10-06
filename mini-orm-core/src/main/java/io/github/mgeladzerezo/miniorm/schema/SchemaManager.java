package io.github.mgeladzerezo.miniorm.schema;

import io.github.mgeladzerezo.miniorm.annotation.GenerationType;
import io.github.mgeladzerezo.miniorm.dialect.Dialect;
import io.github.mgeladzerezo.miniorm.error.PersistenceException;
import io.github.mgeladzerezo.miniorm.error.SchemaValidationException;
import io.github.mgeladzerezo.miniorm.internal.TopologicalSort;
import io.github.mgeladzerezo.miniorm.mapping.ColumnMapping;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import io.github.mgeladzerezo.miniorm.mapping.ManyToOneAttribute;
import io.github.mgeladzerezo.miniorm.mapping.MetadataRegistry;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Generates DDL from entity metadata and checks an existing database against it.
 *
 * <p>Generation is meant for tests, prototypes and the first deployment of a fresh database.
 * It is not a migration tool: {@link #create()} does not alter tables that already exist.
 * Use {@link #validate()} at startup against a schema managed by migrations to find drift
 * before the first query does.
 */
public final class SchemaManager {

    private final DataSource dataSource;
    private final Dialect dialect;
    private final MetadataRegistry registry;

    /**
     * @param dataSource where to run DDL and read {@code DatabaseMetaData}
     * @param dialect    type names and quoting
     * @param registry   the entities to generate from
     */
    public SchemaManager(DataSource dataSource, Dialect dialect, MetadataRegistry registry) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ generation

    /**
     * The statements that create the schema: sequences, tables, then foreign keys, then indexes.
     * Foreign keys are separate {@code ALTER TABLE} statements so that circular references
     * between tables need no special handling.
     *
     * @return DDL in execution order
     */
    public List<String> createStatements() {
        List<String> statements = new ArrayList<>();
        List<EntityMetadata<?>> tables = ordered();
        for (EntityMetadata<?> metadata : tables) {
            EntityMetadata.IdGeneration generation = metadata.idGeneration();
            if (generation != null && generation.strategy() == GenerationType.SEQUENCE) {
                statements.add(dialect.createSequence(generation.sequenceName(), generation.allocationSize()));
            }
        }
        tables.forEach(metadata -> statements.add(createTable(metadata)));
        for (EntityMetadata<?> metadata : tables) {
            for (ManyToOneAttribute reference : metadata.manyToOnes()) {
                statements.add("alter table " + dialect.quote(metadata.tableName()) + " add constraint "
                        + dialect.quote(foreignKeyName(metadata, reference)) + " foreign key ("
                        + dialect.quote(reference.column().name()) + ") references "
                        + dialect.quote(reference.target().tableName()) + " ("
                        + dialect.quote(reference.target().idColumn().name()) + ")");
            }
        }
        for (EntityMetadata<?> metadata : tables) {
            for (EntityMetadata.IndexDefinition index : metadata.indexes()) {
                statements.add("create " + (index.unique() ? "unique " : "") + "index if not exists "
                        + dialect.quote(index.name()) + " on " + dialect.quote(metadata.tableName()) + " ("
                        + dialect.quoteAll(index.columns().stream().map(ColumnMapping::name).toList()) + ")");
            }
        }
        return statements;
    }

    /**
     * The statements that remove everything {@link #createStatements()} creates.
     *
     * @return DDL in execution order
     */
    public List<String> dropStatements() {
        List<String> statements = new ArrayList<>();
        List<EntityMetadata<?>> tables = ordered().reversed();
        for (EntityMetadata<?> metadata : tables) {
            statements.add("drop table if exists " + dialect.quote(metadata.tableName()) + " cascade");
        }
        for (EntityMetadata<?> metadata : tables) {
            EntityMetadata.IdGeneration generation = metadata.idGeneration();
            if (generation != null && generation.strategy() == GenerationType.SEQUENCE) {
                statements.add("drop sequence if exists " + dialect.quote(generation.sequenceName()));
            }
        }
        return statements;
    }

    /** Creates the schema. Fails if a table, constraint or index already exists. */
    public void create() {
        execute(createStatements());
    }

    /** Drops the tables and sequences of the registered entities if they exist. */
    public void drop() {
        execute(dropStatements());
    }

    private void execute(List<String> statements) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    throw new PersistenceException("Schema statement failed: " + e.getMessage(), sql, e);
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not run schema statements: " + e.getMessage(), null, e);
        }
    }

    /** Tables ordered so that referenced tables come before the tables that reference them. */
    private List<EntityMetadata<?>> ordered() {
        List<EntityMetadata<?>> all = registry.all();
        Set<EntityMetadata<?>> registered = new HashSet<>(all);
        List<EntityMetadata<?>> sorted = new ArrayList<>();
        try {
            sorted.addAll(TopologicalSort.sort(all,
                    m -> m.manyToOnes().stream().<EntityMetadata<?>>map(ManyToOneAttribute::target)
                            .filter(registered::contains).toList(),
                    Comparator.comparing(EntityMetadata::tableName)));
        } catch (TopologicalSort.CycleException e) {
            // Foreign keys are added afterwards, so any table order is valid.
            sorted.addAll(all);
        }
        return sorted;
    }

    private String createTable(EntityMetadata<?> metadata) {
        List<String> definitions = new ArrayList<>();
        for (ColumnMapping column : metadata.columns()) {
            StringBuilder definition = new StringBuilder(dialect.quote(column.name())).append(' ')
                    .append(dialect.columnType(column.databaseType(), column.length(), column.precision(),
                            column.scale()));
            if (column.isId()) {
                if (metadata.hasIdentityId()) {
                    definition.append(' ').append(dialect.identityClause());
                }
                definition.append(" primary key");
            } else {
                if (!column.nullable()) {
                    definition.append(" not null");
                }
                if (column.unique()) {
                    definition.append(" unique");
                }
            }
            definitions.add(definition.toString());
        }
        return "create table " + dialect.quote(metadata.tableName()) + " (" + String.join(", ", definitions) + ")";
    }

    private static String foreignKeyName(EntityMetadata<?> metadata, ManyToOneAttribute reference) {
        return "fk_" + metadata.tableName() + "_" + reference.column().name();
    }

    // ------------------------------------------------------------------ validation

    /**
     * Compares the entity metadata with the database through {@code DatabaseMetaData}: tables,
     * columns, column types, string lengths, nullability and foreign keys.
     *
     * @return the differences found, empty if the schema matches
     */
    public List<String> diff() {
        List<String> problems = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData database = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            for (EntityMetadata<?> metadata : registry.all()) {
                diffTable(database, catalog, schema, metadata, problems);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not read the database metadata: " + e.getMessage(), null, e);
        }
        return problems;
    }

    /**
     * Like {@link #diff()} but throws.
     *
     * @throws SchemaValidationException listing every difference
     */
    public void validate() {
        List<String> problems = diff();
        if (!problems.isEmpty()) {
            throw new SchemaValidationException("The database schema does not match the entity mapping ("
                    + problems.size() + " problem(s)):\n  " + String.join("\n  ", problems));
        }
    }

    private void diffTable(DatabaseMetaData database, String catalog, String schema, EntityMetadata<?> metadata,
                           List<String> problems) throws SQLException {
        String table = metadata.tableName();
        Map<String, ColumnInfo> actual = new HashMap<>();
        try (ResultSet rs = database.getColumns(catalog, schema, table, null)) {
            while (rs.next()) {
                if (!table.equals(rs.getString("TABLE_NAME"))) {
                    continue; // the table name argument is a LIKE pattern: "_" matches any character
                }
                actual.put(rs.getString("COLUMN_NAME"), new ColumnInfo(rs.getInt("DATA_TYPE"),
                        rs.getInt("COLUMN_SIZE"), "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"))));
            }
        }
        if (actual.isEmpty()) {
            problems.add("Table '" + table + "' (" + metadata.entityName() + ") does not exist");
            return;
        }
        for (ColumnMapping column : metadata.columns()) {
            ColumnInfo info = actual.get(column.name());
            String where = table + "." + column.name();
            if (info == null) {
                problems.add("Column '" + where + "' (" + metadata.entityName() + "." + column.property()
                        + ") is missing");
                continue;
            }
            if (!dialect.jdbcTypes(column.databaseType()).contains(info.type)) {
                problems.add("Column '" + where + "' has JDBC type " + info.type + " but "
                        + column.databaseType().getSimpleName() + " was expected");
            }
            if (column.databaseType() == String.class && column.length() > 0 && info.size > 0
                    && info.size < column.length() && info.size < Integer.MAX_VALUE / 2) {
                problems.add("Column '" + where + "' holds at most " + info.size + " characters but the mapping allows "
                        + column.length());
            }
            if (info.nullable && !column.nullable() && !column.isId()) {
                problems.add("Column '" + where + "' is nullable but the mapping declares it not null");
            }
            if (!info.nullable && column.nullable()) {
                problems.add("Column '" + where + "' is NOT NULL but the mapping allows null");
            }
        }
        Set<String> mapped = metadata.columns().stream().map(ColumnMapping::name).collect(Collectors.toSet());
        // Extra columns are not an error: a table may carry columns this model does not use.
        Set<String> foreignKeys = new HashSet<>();
        try (ResultSet rs = database.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                foreignKeys.add(rs.getString("FKCOLUMN_NAME") + "->" + rs.getString("PKTABLE_NAME"));
            }
        }
        for (ManyToOneAttribute reference : metadata.manyToOnes()) {
            if (mapped.contains(reference.column().name()) && actual.containsKey(reference.column().name())
                    && !foreignKeys.contains(reference.column().name() + "->" + reference.target().tableName())) {
                problems.add("Column '" + table + "." + reference.column().name() + "' has no foreign key to '"
                        + reference.target().tableName() + "'");
            }
        }
    }

    private record ColumnInfo(int type, int size, boolean nullable) {
    }
}
