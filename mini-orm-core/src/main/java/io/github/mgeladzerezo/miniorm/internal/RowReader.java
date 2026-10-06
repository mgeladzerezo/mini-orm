package io.github.mgeladzerezo.miniorm.internal;

import io.github.mgeladzerezo.miniorm.mapping.ColumnMapping;
import io.github.mgeladzerezo.miniorm.mapping.EntityMetadata;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads the columns of one entity from the current row of a result set. */
public final class RowReader {

    private RowReader() {
    }

    /**
     * Reads database-side values for every column of {@code metadata}.
     *
     * @param rs          result set positioned on a row
     * @param metadata    the entity whose columns were selected, in {@code columns()} order
     * @param firstColumn the 1-based JDBC index of the entity's first column
     * @return one value per column, indexed by {@link ColumnMapping#index()}
     * @throws SQLException if a column cannot be read
     */
    public static Object[] read(ResultSet rs, EntityMetadata<?> metadata, int firstColumn) throws SQLException {
        Object[] row = new Object[metadata.columns().size()];
        for (ColumnMapping column : metadata.columns()) {
            row[column.index()] = Jdbc.read(rs, firstColumn + column.index(), column.databaseType());
        }
        return row;
    }
}
