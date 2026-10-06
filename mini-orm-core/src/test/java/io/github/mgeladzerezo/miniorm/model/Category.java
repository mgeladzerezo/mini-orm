package io.github.mgeladzerezo.miniorm.model;

import io.github.mgeladzerezo.miniorm.annotation.Entity;
import io.github.mgeladzerezo.miniorm.annotation.GeneratedValue;
import io.github.mgeladzerezo.miniorm.annotation.Id;
import io.github.mgeladzerezo.miniorm.annotation.ManyToOne;

/** Self-referencing identity-keyed entity: parents must be inserted before children. */
@Entity
public class Category {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    @ManyToOne
    private Category parent;

    protected Category() {
    }

    public Category(String name, Category parent) {
        this.name = name;
        this.parent = parent;
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

    public Category getParent() {
        return parent;
    }

    public void setParent(Category parent) {
        this.parent = parent;
    }
}
