package io.github.mgeladzerezo.miniorm.query;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a query result together with the total row count.
 *
 * @param content       the rows of this page
 * @param number        zero-based page index
 * @param size          requested page size
 * @param totalElements number of rows matching the query across all pages
 * @param <T>           element type
 */
public record Page<T>(List<T> content, int number, int size, long totalElements) {

    /** Copies the content. */
    public Page {
        content = List.copyOf(content);
    }

    /**
     * @return number of pages needed for all rows
     */
    public int totalPages() {
        return (int) ((totalElements + size - 1) / size);
    }

    /**
     * @return whether a later page exists
     */
    public boolean hasNext() {
        return number + 1 < totalPages();
    }

    /**
     * @return whether an earlier page exists
     */
    public boolean hasPrevious() {
        return number > 0;
    }

    /**
     * Converts the content, keeping the paging information.
     *
     * @param mapper element conversion
     * @param <R>    new element type
     * @return a page of converted elements
     */
    public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
        return new Page<>(content.stream().<R>map(mapper).toList(), number, size, totalElements);
    }
}
