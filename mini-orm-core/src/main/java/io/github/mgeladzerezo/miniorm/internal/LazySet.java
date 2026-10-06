package io.github.mgeladzerezo.miniorm.internal;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** The {@code Set} counterpart of {@link LazyList}; keeps the load order. */
public final class LazySet<E> extends AbstractSet<E> implements LazyCollection {

    private final Supplier<List<?>> loader;
    private Set<E> elements;

    public LazySet(Supplier<List<?>> loader) {
        this.loader = loader;
    }

    @SuppressWarnings("unchecked")
    private Set<E> elements() {
        if (elements == null) {
            elements = new LinkedHashSet<>((List<E>) loader.get());
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
            elements = new LinkedHashSet<>((List<E>) loaded);
        }
    }

    @Override
    public Iterator<E> iterator() {
        return elements().iterator();
    }

    @Override
    public int size() {
        return elements().size();
    }

    @Override
    public boolean contains(Object o) {
        return elements().contains(o);
    }

    @Override
    public boolean add(E element) {
        return elements().add(element);
    }

    @Override
    public boolean remove(Object o) {
        return elements().remove(o);
    }

    /** Does not trigger loading. */
    @Override
    public String toString() {
        return elements == null ? "[lazy collection, not loaded]" : elements.toString();
    }
}
