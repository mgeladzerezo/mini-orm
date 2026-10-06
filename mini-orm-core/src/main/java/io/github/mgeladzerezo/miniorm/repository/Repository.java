package io.github.mgeladzerezo.miniorm.repository;

import io.github.mgeladzerezo.miniorm.query.Page;
import io.github.mgeladzerezo.miniorm.query.Pageable;
import java.util.List;
import java.util.Optional;

/**
 * Base interface for repositories created with {@code session.repository(...)}. Extend it with
 * your entity and id types and add <em>derived finders</em>, whose implementation is parsed from
 * the method name:
 *
 * <pre>{@code
 * interface UserRepository extends Repository<User, Long> {
 *     Optional<User> findByEmail(String email);
 *     List<User> findByStatusAndAgeGreaterThanOrderByCreatedAtDesc(Status status, int age);
 *     Page<User> findByLastNameContaining(String text, Pageable pageable);
 *     long countByStatus(Status status);
 *     boolean existsByEmail(String email);
 * }
 * }</pre>
 *
 * <p>A repository is bound to the session that created it and shares its transaction and
 * persistence context. See {@link RepositoryFactory} for the full grammar of derived finders.
 *
 * @param <T>  the entity type
 * @param <ID> the primary key type
 */
public interface Repository<T, ID> {

    /**
     * @param id primary key
     * @return the entity, or empty if there is no such row
     */
    Optional<T> findById(ID id);

    /**
     * @return every row; use the paged variant for large tables
     */
    List<T> findAll();

    /**
     * @param pageable page index, size and sort
     * @return one page with the total row count
     */
    Page<T> findAll(Pageable pageable);

    /**
     * Persists a new entity or merges the state of a detached one: an instance the session
     * already manages is returned unchanged, one without an id is persisted, and one with an id
     * is merged (which persists a copy if the row does not exist).
     *
     * @param entity the entity to save
     * @return the managed instance, which may differ from the argument
     */
    T save(T entity);

    /**
     * Removes an entity; a detached instance is attached first.
     *
     * @param entity the entity to remove
     */
    void delete(T entity);

    /**
     * @param id primary key; nothing happens if there is no such row
     */
    void deleteById(ID id);

    /**
     * @return number of rows
     */
    long count();

    /**
     * @param id primary key
     * @return whether the row exists
     */
    boolean existsById(ID id);
}
