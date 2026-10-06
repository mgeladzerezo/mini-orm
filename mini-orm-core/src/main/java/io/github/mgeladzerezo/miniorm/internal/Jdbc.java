package io.github.mgeladzerezo.miniorm.internal;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * The only place where values cross the JDBC boundary. Every value written by the ORM goes
 * through {@link #bind}, which always uses a statement parameter, so no code path exists that
 * could splice a value into SQL text.
 */
public final class Jdbc {

    private Jdbc() {
    }

    public static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            bind(statement, i + 1, parameters.get(i));
        }
    }

    public static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        switch (value) {
            // Types.NULL lets the server infer the type; a concrete type would have to match
            // the column exactly on PostgreSQL (a VARCHAR null is rejected for a uuid column).
            case null -> statement.setNull(index, Types.NULL);
            case String s -> statement.setString(index, s);
            case byte[] bytes -> statement.setBytes(index, bytes);
            case BigDecimal decimal -> statement.setBigDecimal(index, decimal);
            default -> statement.setObject(index, value);
        }
    }

    /**
     * Reads a column as one of the supported database-side types. Numeric types use the
     * lenient typed getters (so a {@code bigint} aggregate can be read as {@code Integer} and
     * vice versa); temporal types and UUID use JDBC 4.2 {@code getObject(int, Class)}.
     */
    public static Object read(ResultSet rs, int index, Class<?> databaseType) throws SQLException {
        if (databaseType == String.class) {
            return rs.getString(index);
        } else if (databaseType == Long.class) {
            long value = rs.getLong(index);
            return rs.wasNull() ? null : value;
        } else if (databaseType == Integer.class) {
            int value = rs.getInt(index);
            return rs.wasNull() ? null : value;
        } else if (databaseType == Boolean.class) {
            boolean value = rs.getBoolean(index);
            return rs.wasNull() ? null : value;
        } else if (databaseType == BigDecimal.class) {
            return rs.getBigDecimal(index);
        } else if (databaseType == Double.class) {
            double value = rs.getDouble(index);
            return rs.wasNull() ? null : value;
        } else if (databaseType == Float.class) {
            float value = rs.getFloat(index);
            return rs.wasNull() ? null : value;
        } else if (databaseType == Short.class) {
            short value = rs.getShort(index);
            return rs.wasNull() ? null : value;
        } else if (databaseType == byte[].class) {
            return rs.getBytes(index);
        }
        return rs.getObject(index, databaseType);
    }
}
