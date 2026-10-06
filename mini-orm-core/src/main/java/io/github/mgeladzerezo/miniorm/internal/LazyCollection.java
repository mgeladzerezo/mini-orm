package io.github.mgeladzerezo.miniorm.internal;

import java.util.List;

/** Common view of {@link LazyList} and {@link LazySet} for the session. */
public interface LazyCollection {

    boolean isInitialized();

    /** Supplies the elements without a query, used by fetch plans. No effect if already loaded. */
    void initialize(List<?> elements);
}
