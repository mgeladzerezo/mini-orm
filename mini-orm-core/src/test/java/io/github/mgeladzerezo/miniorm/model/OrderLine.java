package io.github.mgeladzerezo.miniorm.model;

import io.github.mgeladzerezo.miniorm.annotation.Entity;
import io.github.mgeladzerezo.miniorm.annotation.FetchType;
import io.github.mgeladzerezo.miniorm.annotation.GeneratedValue;
import io.github.mgeladzerezo.miniorm.annotation.GenerationType;
import io.github.mgeladzerezo.miniorm.annotation.Id;
import io.github.mgeladzerezo.miniorm.annotation.ManyToOne;
import java.util.UUID;

/** UUID key, eager many-to-one to the order, lazy one to the product. */
@Entity
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    private PurchaseOrder order;

    @ManyToOne
    private Product product;

    private int quantity;

    protected OrderLine() {
    }

    public OrderLine(PurchaseOrder order, Product product, int quantity) {
        this.order = order;
        this.product = product;
        this.quantity = quantity;
    }

    public UUID getId() {
        return id;
    }

    public PurchaseOrder getOrder() {
        return order;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
