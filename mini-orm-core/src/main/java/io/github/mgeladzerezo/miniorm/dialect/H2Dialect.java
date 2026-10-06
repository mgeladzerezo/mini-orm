package io.github.mgeladzerezo.miniorm.dialect;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/** H2 2.x in its regular (non-compatibility) mode. */
public final class H2Dialect implements Dialect {

    /** H2 error code LOCK_TIMEOUT_1. */
    private static final int LOCK_TIMEOUT = 50200;

    /** Creates the dialect. It is stateless and safe to share. */
    public H2Dialect() {
    }

    @Override
    public String name() {
        return "h2";
    }

    @Override
    public String textType() {
        return "character large object";
    }

    @Override
    public String binaryType() {
        return "binary large object";
    }

    /** Standard SQL row limiting; note that the offset is bound before the limit. */
    @Override
    public String paginate(String sql, Integer limit, Integer offset, List<Object> parameters) {
        StringBuilder out = new StringBuilder(sql);
        if (offset != null) {
            out.append(" offset ? rows");
            parameters.add(offset);
        }
        if (limit != null) {
            out.append(" fetch first ? rows only");
            parameters.add(limit);
        }
        return out.toString();
    }

    @Override
    public boolean isLockFailure(SQLException e) {
        return e.getErrorCode() == LOCK_TIMEOUT;
    }

    @Override
    public String sequenceNextValue(String sequence) {
        return "select next value for " + quote(sequence);
    }

    @Override
    public String upsert(String table, List<String> columns, List<String> keyColumns) {
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "merge into " + quote(table) + " (" + quoteAll(columns) + ") key (" + quoteAll(keyColumns)
                + ") values (" + placeholders + ")";
    }
}
