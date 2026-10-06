package io.github.mgeladzerezo.miniorm.query;

import java.util.Objects;

/**
 * A request for one page of a result.
 *
 * @param page zero-based page index
 * @param size rows per page
 * @param sort ordering; when unsorted, pages are ordered by primary key so that they are stable
 */
public record Pageable(int page, int size, Sort sort) {

    /** Validates the request. */
    public Pageable {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, got " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1, got " + size);
        }
        Objects.requireNonNull(sort, "sort");
    }

    /**
     * @param page zero-based page index
     * @param size rows per page
     * @return an unsorted page request
     */
    public static Pageable of(int page, int size) {
        return new Pageable(page, size, Sort.unsorted());
    }

    /**
     * @param page zero-based page index
     * @param size rows per page
     * @param sort ordering
     * @return a sorted page request
     */
    public static Pageable of(int page, int size, Sort sort) {
        return new Pageable(page, size, sort);
    }

    /**
     * @return number of rows before this page
     */
    public long offset() {
        return (long) page * size;
    }
}
