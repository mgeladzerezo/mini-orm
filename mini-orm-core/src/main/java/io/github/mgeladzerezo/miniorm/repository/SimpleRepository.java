package io.github.mgeladzerezo.miniorm.repository;

import io.github.mgeladzerezo.miniorm.Session;
import io.github.mgeladzerezo.miniorm.query.Page;
import io.github.mgeladzerezo.miniorm.query.Pageable;
import io.github.mgeladzerezo.miniorm.query.Sort;
import java.util.List;
import java.util.Optional;

/** The built-in methods of {@link Repository}, implemented on top of a session. */
final class SimpleRepository<T, ID> implements Repository<T, ID> {

    private final Session session;
    private final Class<T> type;
    private final io.github.mgeladzerezo.miniorm.mapping.EntityMetadata<T> metadata;

    SimpleRepository(Session session, Class<T> type, io.github.mgeladzerezo.miniorm.mapping.EntityMetadata<T> metadata) {
        this.session = session;
        this.type = type;
        this.metadata = metadata;
    }

    @Override
    public Optional<T> findById(ID id) {
        return Optional.ofNullable(session.find(type, id));
    }

    @Override
    public List<T> findAll() {
        return session.from(type).orderBy(Sort.by(metadata.idColumn().property(), Sort.Direction.ASC)).list();
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        return session.from(type).page(pageable);
    }

    @Override
    public T save(T entity) {
        if (session.contains(entity)) {
            return entity;
        }
        if (metadata.id(entity) == null) {
            session.persist(entity);
            return entity;
        }
        return session.merge(entity);
    }

    @Override
    public void delete(T entity) {
        session.remove(session.contains(entity) ? entity : session.merge(entity));
    }

    @Override
    public void deleteById(ID id) {
        T entity = session.find(type, id);
        if (entity != null) {
            session.remove(entity);
        }
    }

    @Override
    public long count() {
        return session.from(type).count();
    }

    @Override
    public boolean existsById(ID id) {
        return session.find(type, id) != null;
    }
}
