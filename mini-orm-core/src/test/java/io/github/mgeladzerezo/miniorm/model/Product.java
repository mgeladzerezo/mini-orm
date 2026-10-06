package io.github.mgeladzerezo.miniorm.model;

import io.github.mgeladzerezo.miniorm.annotation.Column;
import io.github.mgeladzerezo.miniorm.annotation.Entity;
import io.github.mgeladzerezo.miniorm.annotation.Id;
import io.github.mgeladzerezo.miniorm.annotation.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Assigned string key, many basic types, binary column, JSON column. */
@Entity
public class Product {

    @Id
    @Column(length = 20)
    private String sku;

    private String name;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    private double weightKg;

    private boolean active = true;

    private LocalDate releasedOn;

    private byte[] image;

    @Json
    private List<String> tags;

    protected Product() {
    }

    public Product(String sku, String name, BigDecimal price) {
        this.sku = sku;
        this.name = name;
        this.price = price;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(double weightKg) {
        this.weightKg = weightKg;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDate getReleasedOn() {
        return releasedOn;
    }

    public void setReleasedOn(LocalDate releasedOn) {
        this.releasedOn = releasedOn;
    }

    public byte[] getImage() {
        return image;
    }

    public void setImage(byte[] image) {
        this.image = image;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }
}
