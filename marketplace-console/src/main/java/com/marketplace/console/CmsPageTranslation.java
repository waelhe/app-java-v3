package com.marketplace.console;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
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
 * live rows (V281); the apply semantics are the house "null keeps" rule,
 * and the content guard keeps the stored text a valid JSON document on
 * every path (the base row's own discipline — a plain unquoted string
 * is the caller's VAL-001, never the store's INT-001).
 */
@Entity
@Table(name = "cms_page_translations")
@Audited
public class CmsPageTranslation extends BaseEntity {

    /** The stateless document parser of the content guard (the
     * SystemSetting precedent: the write side keeps the stored text
     * valid JSON on every path). */
    private static final JsonMapper CONTENT_JSON = JsonMapper.builder().build();

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
            this.content = requireSerializedJsonDocument(content);
        }
        if (seoTitle != null) {
            this.seoTitle = seoTitle.isBlank() ? null : seoTitle.trim();
        }
        if (seoDescription != null) {
            this.seoDescription = seoDescription.isBlank() ? null : seoDescription.trim();
        }
    }

    /**
     * The jsonb column accepts only a serialized JSON document — a plain
     * (unquoted) string is rejected by PostgreSQL (22P02: invalid input
     * syntax for type json) and leaks on the wire as the internal INT-001
     * (the wave-13 shard-2/8 evidence). The guard answers the caller's
     * honest classified VAL-001 instead, on every write path.
     */
    private static String requireSerializedJsonDocument(String content) {
        try {
            // The 2026-11-05 #810 round review: readTree answers null for
            // the empty/blank document without throwing — the same
            // classified VAL-001 the page's own guard now carries.
            if (CONTENT_JSON.readTree(content) == null) {
                throw new IllegalArgumentException(
                        "The page translation content must be a serialized JSON document"
                                + " (rich text): the document is empty");
            }
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException(
                    "The page translation content must be a serialized JSON document"
                            + " (rich text): " + malformed.getOriginalMessage());
        }
        return content;
    }
}
