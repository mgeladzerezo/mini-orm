package io.github.mgeladzerezo.miniorm.model;

import io.github.mgeladzerezo.miniorm.annotation.Entity;
import io.github.mgeladzerezo.miniorm.annotation.GeneratedValue;
import io.github.mgeladzerezo.miniorm.annotation.GenerationType;
import io.github.mgeladzerezo.miniorm.annotation.Id;
import io.github.mgeladzerezo.miniorm.annotation.ManyToOne;
import io.github.mgeladzerezo.miniorm.annotation.OneToMany;
import io.github.mgeladzerezo.miniorm.annotation.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Sequence key, lazy many-to-one, BigDecimal, LocalDateTime, inverse collection. */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, sequence = "purchase_order_seq", allocationSize = 10)
    private Long id;

    @ManyToOne
    private Customer customer;

    private BigDecimal total;

    private LocalDateTime placedAt;

    @OneToMany(mappedBy = "order")
    private List<OrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(Customer customer, BigDecimal total) {
        this.customer = customer;
        this.total = total;
        this.placedAt = LocalDateTime.of(2026, 1, 15, 10, 30);
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public LocalDateTime getPlacedAt() {
        return placedAt;
    }

    public List<OrderLine> getLines() {
        return lines;
    }
}
