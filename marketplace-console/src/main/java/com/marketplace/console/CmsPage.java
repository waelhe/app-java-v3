package com.marketplace.console;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-CMS-001 ownership decision): the CMS page row — the dashboard-managed
 * content page (title/slug/serialized rich-text content/SEO fields, the
 * publication lifecycle) attributed by its {@link CmsPageType}.
 *
 * <p>{@code content} is the serialized rich-text JSON document
 * (EditorJS-shaped) stored as-is (JSONB) and rendered on the wire as a
 * String (the no-new-scalar house decision). The base row IS the primary
 * language; every other language rides {@link CmsPageTranslation}.</p>
 */
@Entity
@Table(name = "cms_pages")
@Audited
public class CmsPage extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "page_type_id", nullable = false)
    private UUID pageTypeId;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "title", nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb")
    private String content;

    @Column(name = "is_published", nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "seo_title", length = 255)
    private String seoTitle;

    @Column(name = "seo_description", length = 512)
    private String seoDescription;

    protected CmsPage() {
    }

    private CmsPage(UUID id, UUID pageTypeId, String slug, String title) {
        this.id = id;
        this.pageTypeId = pageTypeId;
        this.slug = slug;
        this.title = title;
    }

    public static CmsPage create(UUID pageTypeId, String slug, String title) {
        return new CmsPage(UUID.randomUUID(), pageTypeId, slug, title);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getPageTypeId() {
        return pageTypeId;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public boolean isPublished() {
        return published;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getSeoTitle() {
        return seoTitle;
    }

    public String getSeoDescription() {
        return seoDescription;
    }

    /** The update's own field set — null means "no information" (kept). */
    void apply(String slug, String title, String content, Boolean published,
               String seoTitle, String seoDescription) {
        if (slug != null && !slug.isBlank()) {
            this.slug = slug.trim();
        }
        if (title != null && !title.isBlank()) {
            this.title = title.trim();
        }
        if (content != null) {
            this.content = content;
        }
        if (published != null) {
            setPublished(published);
        }
        if (seoTitle != null) {
            this.seoTitle = seoTitle.isBlank() ? null : seoTitle.trim();
        }
        if (seoDescription != null) {
            this.seoDescription = seoDescription.isBlank() ? null : seoDescription.trim();
        }
    }

    /** The publication lifecycle: publishing stamps the publication time. */
    void setPublished(boolean value) {
        this.published = value;
        if (value && this.publishedAt == null) {
            this.publishedAt = Instant.now();
        }
        if (!value) {
            this.publishedAt = null;
        }
    }
}
