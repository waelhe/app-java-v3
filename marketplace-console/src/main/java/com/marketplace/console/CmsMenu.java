package com.marketplace.console;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * navigation menu — a named tree of {@link CmsMenuItem}s, assignable to a
 * navigation slot (V284).
 */
@Entity
@Table(name = "cms_menus")
@Audited
public class CmsMenu extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false)
    private String slug;

    protected CmsMenu() {
    }

    private CmsMenu(UUID id, String name, String slug) {
        this.id = id;
        this.name = name;
        this.slug = slug;
    }

    public static CmsMenu create(String name, String slug) {
        return new CmsMenu(UUID.randomUUID(), name, slug);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    void apply(String name, String slug) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        if (slug != null && !slug.isBlank()) {
            this.slug = slug.trim();
        }
    }
}
