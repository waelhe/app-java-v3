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
 * String (the no-new-scalar house decision) — the write paths keep the
 * stored text a valid JSON document on every path (the SystemSetting
 * discipline): a plain (unquoted) string is the caller's classified
 * VAL-001, never the store's INT-001. The base row IS the primary
 * language; every other language rides {@link CmsPageTranslation}.</p>
 */
@Entity
@Table(name = "cms_pages")
@Audited
public class CmsPage extends BaseEntity {

    /** The stateless document parser of the content guard (the
     * SystemSetting precedent: the write side keeps the stored text
     * valid JSON on every path). */
    private static final JsonMapper CONTENT_JSON = JsonMapper.builder().build();

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
            this.content = requireSerializedJsonDocument(content, "page");
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

    /**
     * The jsonb column accepts only a serialized JSON document — a plain
     * (unquoted) string is rejected by PostgreSQL (22P02: invalid input
     * syntax for type json) and leaks on the wire as the internal INT-001.
     * The guard answers the caller's honest classified VAL-001 instead,
     * on every write path.
     */
    private static String requireSerializedJsonDocument(String content, String owner) {
        try {
            // The 2026-11-05 #810 round review (Minor · Functional
            // Correctness): readTree answers null for the empty/blank
            // document WITHOUT throwing — the guard used to swallow that
            // and let the value reach the jsonb column's own rejection at
            // flush. A null/missing node is the same classified VAL-001.
            // The 2026-11-06 #810 round review: Jackson 3's readTree
            // answers a MISSING NODE (not null) for the blank document —
            // the same empty-document refusal, both shapes.
            tools.jackson.databind.JsonNode parsed = CONTENT_JSON.readTree(content);
            if (parsed == null || parsed.isMissingNode()) {
                throw new IllegalArgumentException(
                        "The " + owner + " content must be a serialized JSON document (rich text):"
                                + " the document is empty");
            }
            rejectNulCharacters(parsed);
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException(
                    "The " + owner + " content must be a serialized JSON document (rich text): "
                            + malformed.getOriginalMessage());
        }
        return content;
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
    /**
     * The 2026-11-08 #810 round-5 review (Minor · Functional Correctness):
     * the NUL character is valid JSON but NOT valid jsonb — PostgreSQL
     * rejects a U+0000 inside a text value at flush (the raw storage
     * error), so the guard walks the parsed tree (object KEYS included)
     * and answers the same classified validation refusal for the
     * offending document, on every write path (the page's own content
     * AND the translation's — the twin validators carry the same law).
     */
    private static void rejectNulCharacters(tools.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            for (java.util.Map.Entry<String, tools.jackson.databind.JsonNode> entry
                    : node.properties()) {
                if (entry.getKey().indexOf('\u0000') >= 0) {
                    throw new IllegalArgumentException(
                            "The document contains a NUL character the jsonb column cannot store");
                }
                rejectNulCharacters(entry.getValue());
            }
        } else if (node.isArray()) {
            for (tools.jackson.databind.JsonNode child : node) {
                rejectNulCharacters(child);
            }
        } else if (node.isTextual() && node.asString().indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(
                    "The document contains a NUL character the jsonb column cannot store");
        }
    }
}
