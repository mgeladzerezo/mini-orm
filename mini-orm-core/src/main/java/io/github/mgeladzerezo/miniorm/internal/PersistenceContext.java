package io.github.mgeladzerezo.miniorm.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The session's first-level cache: every instance the session manages, reachable by identity
 * (to find the entry of an object) and by {@link EntityKey} (to guarantee one instance per row).
 *
 * <p>Instances that were persisted with a database-generated key have no key yet; they are only
 * in the identity index until their INSERT has run.
 */
public final class PersistenceContext {

    private final Map<Object, EntityEntry> byInstance = new IdentityHashMap<>();
    private final Map<EntityKey, EntityEntry> byKey = new HashMap<>();
    private final Map<EntityKey, Object> proxies = new HashMap<>();
    private int sequence;

    /** Instances loaded but not yet snapshotted, because their eager references are still being resolved. */
    private final List<EntityEntry> unsnapshotted = new ArrayList<>();

    public EntityEntry entryOf(Object instance) {
        return byInstance.get(instance);
    }

    public EntityEntry entryFor(EntityKey key) {
        return byKey.get(key);
    }

    public int nextSequence() {
        return sequence++;
    }

    /** Registers an entry under its identity and, when it has an id, its key. */
    public void add(EntityEntry entry) {
        byInstance.put(entry.entity, entry);
        if (entry.id != null) {
            byKey.put(entry.key(), entry);
        }
    }

    /** Indexes an entry whose id has just been assigned by the database. */
    public void indexKey(EntityEntry entry) {
        byKey.put(entry.key(), entry);
    }

    public void remove(EntityEntry entry) {
        byInstance.remove(entry.entity);
        if (entry.id != null) {
            byKey.remove(entry.key(), entry);
        }
    }

    /** All entries in registration order of their {@code sequence}; a copy, safe to iterate while flushing. */
    public List<EntityEntry> entries() {
        List<EntityEntry> all = new ArrayList<>(byInstance.values());
        all.sort((a, b) -> Integer.compare(a.sequence, b.sequence));
        return all;
    }

    public boolean isEmpty() {
        return byInstance.isEmpty();
    }

    public Object proxyFor(EntityKey key) {
        return proxies.get(key);
    }

    public void addProxy(EntityKey key, Object proxy) {
        proxies.put(key, proxy);
    }

    public Object removeProxy(EntityKey key) {
        return proxies.remove(key);
    }

    public void deferSnapshot(EntityEntry entry) {
        unsnapshotted.add(entry);
    }

    /** Hands over, and forgets, the entries awaiting their first snapshot. */
    public List<EntityEntry> takeUnsnapshotted() {
        List<EntityEntry> taken = new ArrayList<>(unsnapshotted);
        unsnapshotted.clear();
        return taken;
    }

    public void clear() {
        byInstance.clear();
        byKey.clear();
        proxies.clear();
        unsnapshotted.clear();
    }

}
