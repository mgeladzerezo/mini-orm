package io.github.mgeladzerezo.miniorm.query;

/** How a fetch plan loads an association. */
public enum FetchMode {
    /**
     * In the same SELECT, through a LEFT JOIN. One round trip. For a collection the join
     * multiplies root rows, so it cannot be combined with {@code limit}/{@code offset}.
     */
    JOIN,
    /**
     * In one additional SELECT with an {@code IN (...)} list of the keys collected from the
     * first result. Two round trips regardless of the number of rows; safe with pagination.
     */
    BATCH
}
