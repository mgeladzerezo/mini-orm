package io.github.mgeladzerezo.miniorm.dialect;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/** PostgreSQL 12 and later. */
public final class PostgresDialect implements Dialect {

    /** Creates the dialect. It is stateless and safe to share. */
    public PostgresDialect() {
    }

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public String textType() {
        return "text";
    }

    @Override
    public String binaryType() {
        return "bytea";
    }

    @Override
    public String paginate(String sql, Integer limit, Integer offset, List<Object> parameters) {
        StringBuilder out = new StringBuilder(sql);
        if (limit != null) {
            out.append(" limit ?");
            parameters.add(limit);
        }
        if (offset != null) {
            out.append(" offset ?");
            parameters.add(offset);
        }
        return out.toString();
    }

    /** {@code 55P03} is lock_not_available, raised by NOWAIT and by {@code lock_timeout}. */
    @Override
    public boolean isLockFailure(SQLException e) {
        return "55P03".equals(e.getSQLState());
    }

    @Override
    public String sequenceNextValue(String sequence) {
        // The sequence name travels inside a string literal, so quote for both levels.
        return "select nextval('" + quote(sequence).replace("'", "''") + "')";
    }

    @Override
    public String upsert(String table, List<String> columns, List<String> keyColumns) {
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String updates = columns.stream().filter(c -> !keyColumns.contains(c))
                .map(c -> quote(c) + " = excluded." + quote(c)).collect(Collectors.joining(", "));
        return "insert into " + quote(table) + " (" + quoteAll(columns) + ") values (" + placeholders
                + ") on conflict (" + quoteAll(keyColumns) + ") "
                + (updates.isEmpty() ? "do nothing" : "do update set " + updates);
    }
}
