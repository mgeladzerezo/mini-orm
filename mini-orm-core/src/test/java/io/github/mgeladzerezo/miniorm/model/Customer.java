package io.github.mgeladzerezo.miniorm.model;

import io.github.mgeladzerezo.miniorm.annotation.Column;
import io.github.mgeladzerezo.miniorm.annotation.CreatedAt;
import io.github.mgeladzerezo.miniorm.annotation.Embedded;
import io.github.mgeladzerezo.miniorm.annotation.Entity;
import io.github.mgeladzerezo.miniorm.annotation.Enumerated;
import io.github.mgeladzerezo.miniorm.annotation.GeneratedValue;
import io.github.mgeladzerezo.miniorm.annotation.Id;
import io.github.mgeladzerezo.miniorm.annotation.Index;
import io.github.mgeladzerezo.miniorm.annotation.OneToMany;
import io.github.mgeladzerezo.miniorm.annotation.PrePersist;
import io.github.mgeladzerezo.miniorm.annotation.PreUpdate;
import io.github.mgeladzerezo.miniorm.annotation.Table;
import io.github.mgeladzerezo.miniorm.annotation.Transient;
import io.github.mgeladzerezo.miniorm.annotation.UpdatedAt;
import io.github.mgeladzerezo.miniorm.annotation.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Identity key, optimistic lock, embedded record, enum, timestamps, callbacks, optional column, inverse collection. */
@Entity
@Table(indexes = @Index(properties = {"tier", "name"}))
public class Customer {

    @Id
    @GeneratedValue
    private Long id;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String email;

    @Embedded
    private Address address;

    @Enumerated
    private Tier tier = Tier.BASIC;

    private Optional<String> nickname = Optional.empty();

    private int points;

    @Version
    private long version;

    @CreatedAt
    private Instant createdAt;

    @UpdatedAt
    private Instant updatedAt;

    @OneToMany(mappedBy = "customer")
    private List<PurchaseOrder> orders = new ArrayList<>();

    @Transient
    private int prePersistCalls;

    @Transient
    private int preUpdateCalls;

    protected Customer() {
    }

    public Customer(String name, String email) {
        this.name = name;
        this.email = email;
    }

    @PrePersist
    void normalise() {
        email = email.toLowerCase();
        prePersistCalls++;
    }

    @PreUpdate
    void touched() {
        preUpdateCalls++;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public Tier getTier() {
        return tier;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    public Optional<String> getNickname() {
        return nickname;
    }

    public void setNickname(Optional<String> nickname) {
        this.nickname = nickname;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<PurchaseOrder> getOrders() {
        return orders;
    }

    public int getPrePersistCalls() {
        return prePersistCalls;
    }

    public int getPreUpdateCalls() {
        return preUpdateCalls;
    }
}
