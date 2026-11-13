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
 * CMS page type — the page's attributed classification. The assigned
 * attribute set lives in {@link CmsPageTypeAttribute} (catalog-registered
 * UUIDs; existence validated at the composition boundary).
 */
@Entity
@Table(name = "cms_page_types")
@Audited
public class CmsPageType extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false)
    private String slug;

    protected CmsPageType() {
    }

    private CmsPageType(UUID id, String name, String slug) {
        this.id = id;
        this.name = name;
        this.slug = slug;
    }

    public static CmsPageType create(String name, String slug) {
        return new CmsPageType(UUID.randomUUID(), name, slug);
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
