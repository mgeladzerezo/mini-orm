package io.github.mgeladzerezo.miniorm.internal;

import io.github.mgeladzerezo.miniorm.LockMode;
import io.github.mgeladzerezo.miniorm.dialect.Dialect;
import io.github.mgeladzerezo.miniorm.mapping.ColumnMapping;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The SQL statements for one entity, generated from its metadata and cached. UPDATE statements
 * are generated per set of changed columns and cached by that set, so an application that
 * updates the same few shapes pays for string building once.
 */
public final class EntitySql {

    /** Alias of the root table in every generated SELECT. */
    public static final String ROOT = "t0";

    private final EntityMetadata<?> metadata;
    private final Dialect dialect;
    private final String table;
    private final List<ColumnMapping> insertColumns;
    private final String insert;
    private final String delete;
    private final String upsert;
    private final Map<BitSet, String> updates = new ConcurrentHashMap<>();

    public EntitySql(EntityMetadata<?> metadata, Dialect dialect) {
        this.metadata = metadata;
        this.dialect = dialect;
        this.table = dialect.quote(metadata.tableName());
        // An identity key is produced by the database, so it is not part of the INSERT.
        this.insertColumns = metadata.columns().stream()
                .filter(c -> !(c.isId() && metadata.hasIdentityId())).toList();
        this.insert = "insert into " + table + " (" + names(insertColumns) + ") values ("
                + insertColumns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
        this.delete = "delete from " + table + " where " + keyPredicate();
        this.upsert = dialect.upsert(metadata.tableName(),
                metadata.columns().stream().map(ColumnMapping::name).toList(),
                List.of(metadata.idColumn().name()));
    }

    private String names(List<ColumnMapping> columns) {
        return columns.stream().map(c -> dialect.quote(c.name())).collect(Collectors.joining(", "));
    }

    /** {@code "id" = ?} plus {@code and "version" = ?} for versioned entities. */
    private String keyPredicate() {
        String predicate = dialect.quote(metadata.idColumn().name()) + " = ?";
        if (metadata.versionColumn() != null) {
            predicate += " and " + dialect.quote(metadata.versionColumn().name()) + " = ?";
        }
        return predicate;
    }

    public List<ColumnMapping> insertColumns() {
        return insertColumns;
    }

    public String insert() {
        return insert;
    }

    public String delete() {
        return delete;
    }

    public String upsert() {
        return upsert;
    }

    /** All columns of the entity qualified with {@code alias}, in row order. */
    public String selectList(String alias) {
        return metadata.columns().stream().map(c -> alias + "." + dialect.quote(c.name()))
                .collect(Collectors.joining(", "));
    }

    /** {@code select <columns> from <table> t0}. */
    public String selectFrom() {
        return "select " + selectList(ROOT) + " from " + table + " " + ROOT;
    }

    public String selectById(LockMode lockMode) {
        return selectFrom() + " where " + qualified(metadata.idColumn()) + " = ?" + dialect.lockClause(lockMode);
    }

    /** Rows whose {@code column} is one of {@code count} values, in primary key order. */
    public String selectWhereIn(ColumnMapping column, int count) {
        return selectFrom() + " where " + qualified(column) + " in ("
                + "?, ".repeat(count - 1) + "?) order by " + qualified(metadata.idColumn());
    }

    public String qualified(ColumnMapping column) {
        return ROOT + "." + dialect.quote(column.name());
    }

    /**
     * UPDATE that sets exactly the given columns. Versioned entities also set the version and
     * require the old one. Parameter order: changed columns, [new version], id, [old version].
     */
    public String update(BitSet changedColumns) {
        return updates.computeIfAbsent((BitSet) changedColumns.clone(), changed -> {
            StringBuilder sql = new StringBuilder("update ").append(table).append(" set ");
            sql.append(changed.stream().mapToObj(i -> dialect.quote(metadata.columns().get(i).name()) + " = ?")
                    .collect(Collectors.joining(", ")));
            if (metadata.versionColumn() != null) {
                sql.append(", ").append(dialect.quote(metadata.versionColumn().name())).append(" = ?");
            }
            return sql.append(" where ").append(keyPredicate()).toString();
        });
    }
}
