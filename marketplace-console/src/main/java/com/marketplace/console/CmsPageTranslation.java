package com.marketplace.console;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * per-language page translation — every language except the page's own
 * primary row (the base table). The (page, language) pair is unique over
 * live rows (V281); the apply semantics are the house "null keeps" rule.
 */
@Entity
@Table(name = "cms_page_translations")
@Audited
public class CmsPageTranslation extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "page_id", nullable = false)
    private UUID pageId;

    @Column(name = "language_code", nullable = false, length = 8)
    private String languageCode;

    @Column(name = "slug")
    private String slug;

    @Column(name = "title")
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb")
    private String content;

    @Column(name = "seo_title", length = 255)
    private String seoTitle;

    @Column(name = "seo_description", length = 512)
    private String seoDescription;

    protected CmsPageTranslation() {
    }

    private CmsPageTranslation(UUID id, UUID pageId, String languageCode) {
        this.id = id;
        this.pageId = pageId;
        this.languageCode = languageCode;
    }

    public static CmsPageTranslation create(UUID pageId, String languageCode) {
        return new CmsPageTranslation(UUID.randomUUID(), pageId, languageCode);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getPageId() {
        return pageId;
    }

    public String getLanguageCode() {
        return languageCode;
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

    public String getSeoTitle() {
        return seoTitle;
    }

    public String getSeoDescription() {
        return seoDescription;
    }

    void apply(String slug, String title, String content, String seoTitle, String seoDescription) {
        if (slug != null && !slug.isBlank()) {
            this.slug = slug.trim();
        }
        if (title != null && !title.isBlank()) {
            this.title = title.trim();
        }
        if (content != null) {
            this.content = content;
        }
        if (seoTitle != null) {
            this.seoTitle = seoTitle.isBlank() ? null : seoTitle.trim();
        }
        if (seoDescription != null) {
            this.seoDescription = seoDescription.isBlank() ? null : seoDescription.trim();
        }
    }
}
