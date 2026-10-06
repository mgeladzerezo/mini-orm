package io.github.mgeladzerezo.miniorm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.miniorm.error.MappingException;
import io.github.mgeladzerezo.miniorm.error.NonUniqueResultException;
import io.github.mgeladzerezo.miniorm.model.Address;
import io.github.mgeladzerezo.miniorm.model.Customer;
import io.github.mgeladzerezo.miniorm.model.Tier;
import io.github.mgeladzerezo.miniorm.query.Page;
import io.github.mgeladzerezo.miniorm.query.Pageable;
import io.github.mgeladzerezo.miniorm.query.Sort;
import io.github.mgeladzerezo.miniorm.repository.Repository;
import io.github.mgeladzerezo.miniorm.support.OrmTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code Repository} and derived finders parsed from method names. */
class RepositoryTest extends OrmTest {

    public interface CustomerRepository extends Repository<Customer, Long> {
        Optional<Customer> findByEmail(String email);

        Customer findByName(String name);

        List<Customer> findByTierOrderByPointsDesc(Tier tier);

        List<Customer> findByTierAndPointsGreaterThanOrderByNameAsc(Tier tier, int points);

        List<Customer> findByTierOrPointsLessThanEqualOrderByName(Tier tier, int points);

        List<Customer> findByAddressCity(String city);

        List<Customer> findByNameContainingIgnoreCase(String text);

        List<Customer> findByNameStartingWith(String prefix);

        List<Customer> findByPointsBetween(int low, int high);

        List<Customer> findByNameIn(List<String> names);

        List<Customer> findByNicknameIsNull();

        List<Customer> findByTierNot(Tier tier);

        List<Customer> findTop2ByOrderByPointsDesc();

        Customer findFirstByTierOrderByPointsAsc(Tier tier);

        Page<Customer> findByTier(Tier tier, Pageable pageable);

        List<Customer> findByTier(Tier tier, Sort sort);

        long countByTier(Tier tier);

        boolean existsByEmail(String email);

        long deleteByTier(Tier tier);

        default String describe(String email) {
            return findByEmail(email).map(Customer::getName).orElse("nobody");
        }
    }

    @BeforeEach
    void seed() {
        db.orm().runInTransaction(session -> {
            add(session, "Ada", "ada@x.io", Tier.GOLD, 30, "Tbilisi");
            add(session, "Alan", "alan@x.io", Tier.BASIC, 20, "Batumi");
            add(session, "Grace", "grace@x.io", Tier.GOLD, 50, "Tbilisi");
            add(session, "Linus", "linus@x.io", Tier.BASIC, 10, "Kutaisi");
        });
    }

    private static void add(Session session, String name, String email, Tier tier, int points, String city) {
        Customer customer = new Customer(name, email);
        customer.setTier(tier);
        customer.setPoints(points);
        customer.setAddress(new Address("s", city));
        session.persist(customer);
    }

    private static List<String> names(List<Customer> customers) {
        return customers.stream().map(Customer::getName).toList();
    }

    @Test
    void baseMethodsFindSaveCountAndDelete() {
        db.orm().runInTransaction(session -> {
            CustomerRepository repository = session.repository(CustomerRepository.class);
            assertThat(repository.count()).isEqualTo(4);
            assertThat(names(repository.findAll())).hasSize(4);

            Customer fresh = new Customer("New", "new@x.io");
            Customer saved = repository.save(fresh);
            assertThat(saved).isSameAs(fresh);
            session.flush();
            assertThat(saved.getId()).isNotNull();
            assertThat(repository.existsById(saved.getId())).isTrue();
            assertThat(repository.findById(saved.getId())).containsSame(saved);
            assertThat(repository.findById(-5L)).isEmpty();

            repository.deleteById(saved.getId());
            session.flush();
            assertThat(repository.existsById(saved.getId())).isFalse();
            repository.deleteById(-5L);
        });
    }

    @Test
    void savingADetachedInstanceMergesIt() {
        Customer detached = db.orm().inSession(session ->
                session.repository(CustomerRepository.class).findByEmail("ada@x.io").orElseThrow());
        detached.setName("Ada L.");
        db.orm().runInTransaction(session -> {
            Customer managed = session.repository(CustomerRepository.class).save(detached);
            assertThat(managed).isNotSameAs(detached);
        });
        db.orm().runInSession(session -> assertThat(
                session.repository(CustomerRepository.class).findByEmail("ada@x.io").orElseThrow().getName())
                .isEqualTo("Ada L."));
    }

    @Test
    void deletingADetachedInstanceAttachesItFirst() {
        Customer detached = db.orm().inSession(session ->
                session.repository(CustomerRepository.class).findByEmail("alan@x.io").orElseThrow());
        db.orm().runInTransaction(session -> session.repository(CustomerRepository.class).delete(detached));
        db.orm().runInSession(session ->
                assertThat(session.repository(CustomerRepository.class).existsByEmail("alan@x.io")).isFalse());
    }

    @Test
    void findAllWithAPageableReturnsASliceOfTheTable() {
        db.orm().runInSession(session -> {
            Page<Customer> page = session.repository(CustomerRepository.class)
                    .findAll(Pageable.of(1, 3, Sort.asc(Customer::getName)));
            assertThat(names(page.content())).containsExactly("Linus");
            assertThat(page.totalElements()).isEqualTo(4);
        });
    }

    @Test
    void derivedFindersTranslateOperatorsPropertiesAndOrdering() {
        db.orm().runInSession(session -> {
            CustomerRepository r = session.repository(CustomerRepository.class);
            assertThat(r.findByEmail("grace@x.io")).get().extracting(Customer::getName).isEqualTo("Grace");
            assertThat(r.findByEmail("none@x.io")).isEmpty();
            assertThat(r.findByName("Ada").getEmail()).isEqualTo("ada@x.io");
            assertThat(r.findByName("nobody")).isNull();
            assertThat(names(r.findByTierOrderByPointsDesc(Tier.GOLD))).containsExactly("Grace", "Ada");
            assertThat(names(r.findByTierAndPointsGreaterThanOrderByNameAsc(Tier.GOLD, 40))).containsExactly("Grace");
            assertThat(names(r.findByTierOrPointsLessThanEqualOrderByName(Tier.GOLD, 10)))
                    .containsExactly("Ada", "Grace", "Linus");
            assertThat(names(r.findByAddressCity("Tbilisi"))).containsExactlyInAnyOrder("Ada", "Grace");
            assertThat(names(r.findByNameContainingIgnoreCase("AN"))).containsExactlyInAnyOrder("Alan");
            assertThat(names(r.findByNameStartingWith("Gr"))).containsExactly("Grace");
            assertThat(names(r.findByPointsBetween(15, 35))).containsExactlyInAnyOrder("Ada", "Alan");
            assertThat(names(r.findByNameIn(List.of("Ada", "Linus", "Zed")))).containsExactlyInAnyOrder("Ada", "Linus");
            assertThat(r.findByNicknameIsNull()).hasSize(4);
            assertThat(names(r.findByTierNot(Tier.GOLD))).containsExactlyInAnyOrder("Alan", "Linus");
            assertThat(names(r.findTop2ByOrderByPointsDesc())).containsExactly("Grace", "Ada");
            assertThat(r.findFirstByTierOrderByPointsAsc(Tier.BASIC).getName()).isEqualTo("Linus");
        });
    }

    @Test
    void derivedFindersAcceptPageableAndSortArguments() {
        db.orm().runInSession(session -> {
            CustomerRepository r = session.repository(CustomerRepository.class);
            Page<Customer> page = r.findByTier(Tier.GOLD, Pageable.of(0, 1, Sort.desc(Customer::getPoints)));
            assertThat(names(page.content())).containsExactly("Grace");
            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(names(r.findByTier(Tier.GOLD, Sort.asc(Customer::getName)))).containsExactly("Ada", "Grace");
        });
    }

    @Test
    void countExistsAndDeleteDerivedMethods() {
        db.orm().runInTransaction(session -> {
            CustomerRepository r = session.repository(CustomerRepository.class);
            assertThat(r.countByTier(Tier.GOLD)).isEqualTo(2);
            assertThat(r.existsByEmail("ada@x.io")).isTrue();
            assertThat(r.existsByEmail("zed@x.io")).isFalse();
            assertThat(r.deleteByTier(Tier.BASIC)).isEqualTo(2);
            assertThat(r.count()).isEqualTo(2);
        });
        db.orm().runInSession(session ->
                assertThat(session.repository(CustomerRepository.class).count()).isEqualTo(2));
    }

    @Test
    void aSingleResultFinderThatMatchesSeveralRowsFails() {
        db.orm().runInSession(session -> assertThatThrownBy(() ->
                session.repository(OneResult.class).findByTier(Tier.GOLD))
                .isInstanceOf(NonUniqueResultException.class));
    }

    interface OneResult extends Repository<Customer, Long> {
        Customer findByTier(Tier tier);
    }

    @Test
    void defaultMethodsOfTheInterfaceRunAsWritten() {
        db.orm().runInSession(session -> {
            CustomerRepository r = session.repository(CustomerRepository.class);
            assertThat(r.describe("ada@x.io")).isEqualTo("Ada");
            assertThat(r.describe("zzz@x.io")).isEqualTo("nobody");
            assertThat(r.toString()).contains("CustomerRepository");
        });
    }

    // ------------------------------------------------------------------ fail at creation, not at first call

    interface MisspelledProperty extends Repository<Customer, Long> {
        List<Customer> findByEmial(String email);
    }

    interface UnparsableName extends Repository<Customer, Long> {
        void frobnicate();
    }

    interface WrongReturnType extends Repository<Customer, Long> {
        String countByTier(Tier tier);
    }

    interface PageWithoutPageable extends Repository<Customer, Long> {
        Page<Customer> findByTier(Tier tier);
    }

    interface UnrelatedParameter extends Repository<Customer, Long> {
        List<Customer> findByTier(Tier tier, String surprise);
    }

    @Test
    void aMalformedRepositoryInterfaceFailsWhenItIsCreatedWithAnExplanation() {
        db.orm().runInSession(session -> {
            assertThatThrownBy(() -> session.repository(MisspelledProperty.class)).isInstanceOf(MappingException.class)
                    .hasMessageContaining("Emial").hasMessageContaining("Known:");
            assertThatThrownBy(() -> session.repository(UnparsableName.class)).isInstanceOf(MappingException.class)
                    .hasMessageContaining("frobnicate");
            assertThatThrownBy(() -> session.repository(WrongReturnType.class)).isInstanceOf(MappingException.class)
                    .hasMessageContaining("String");
            assertThatThrownBy(() -> session.repository(PageWithoutPageable.class)).isInstanceOf(MappingException.class)
                    .hasMessageContaining("Pageable");
            assertThatThrownBy(() -> session.repository(UnrelatedParameter.class)).isInstanceOf(MappingException.class)
                    .hasMessageContaining("Pageable or a Sort");
        });
    }
}
