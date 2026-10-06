package io.github.mgeladzerezo.miniorm.internal;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@code List} placed in a {@code @OneToMany} field. It runs its loader on first access
 * and then behaves like the {@code ArrayList} it wraps. If the session has been closed in the
 * meantime the loader throws {@code LazyInitializationException}.
 */
public final class LazyList<E> extends AbstractList<E> implements LazyCollection {

    private final Supplier<List<?>> loader;
    private List<E> elements;

    public LazyList(Supplier<List<?>> loader) {
        this.loader = loader;
    }

    @SuppressWarnings("unchecked")
    private List<E> elements() {
        if (elements == null) {
            elements = new ArrayList<>((List<E>) loader.get());
        }
        return elements;
    }

    @Override
    public boolean isInitialized() {
        return elements != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initialize(List<?> loaded) {
        if (elements == null) {
            elements = new ArrayList<>((List<E>) loaded);
        }
    }

    @Override
    public E get(int index) {
        return elements().get(index);
    }

    @Override
    public int size() {
        return elements().size();
    }

    @Override
    public E set(int index, E element) {
        return elements().set(index, element);
    }

    @Override
    public void add(int index, E element) {
        elements().add(index, element);
    }

    @Override
    public E remove(int index) {
        return elements().remove(index);
    }

    /** Does not trigger loading, so logging or debugging an entity never runs a query. */
    @Override
    public String toString() {
        return elements == null ? "[lazy collection, not loaded]" : elements.toString();
    }
}
