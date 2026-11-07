package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor {@code collection}
 * and {@code collections} read roots, packet agent-2): one row of the
 * storefront collection registry — the curated product grouping the
 * pinned Products-domain contract exposes.
 *
 * <p>Lifecycle: reference data (the {@code V70} categories precedent). The
 * registry evolves by ADMIN writes through the owning use case
 * ({@code CollectionWriteService} — the collectionCreate/Update/Delete
 * roots), and the entity is {@code @Audited} per the AGENTS.md rule, so
 * every amendment leaves an Envers trail through the
 * {@code collections_aud} mirror (the {@code V253} migration's own
 * convention).</p>
 *
 * <p>{@code slug} is the stable API-facing key (what create/update carry
 * and what the public {@code collection} lookup addresses — the DB CHECK
 * pins its shape, and the {@code uq_collections_slug_live} partial unique
 * index keeps LIVE rows unique while a soft-deleted row releases its slug
 * for reuse, the V47 slug precedent: a slug is presentation identity,
 * never referenced by a foreign key). The bilingual {@code name_en}/
 * {@code name_ar} columns follow the platform's own two-language row law
 * (the registry's at-least-one-name law governs the pair).
 * {@code isPublished} is the row's own publication fact — the pinned
 * Saleor input's own field; the include-unpublished read surface is the
 * ADMIN capability (the registry's own law, never silently approximated).</p>
 */
@Entity
@Table(name = "collections")
@Audited
public class Collection extends BaseEntity {

    @Id
    private UUID id;

    /** The stable API-facing key (lowercase latin/digits/dashes — the V253 CHECK shape). */
    @Column(name = "slug", nullable = false, length = 255)
    private String slug;

    /** English display name — the storefront's default label. */
    @Column(name = "name_en", length = 100)
    private String nameEn;

    /** Arabic display name — the primary market's label. */
    @Column(name = "name_ar", length = 100)
    private String nameAr;

    /** The row's own publication fact (false until the ADMIN publishes). */
    @Column(name = "is_published", nullable = false)
    private boolean published;

    protected Collection() {
    }

    private Collection(UUID id, String slug, String nameEn, String nameAr, boolean published) {
        this.id = id;
        this.slug = slug;
        this.nameEn = nameEn;
        this.nameAr = nameAr;
        this.published = published;
    }

    /** Factory for the owning write use case — the registry's write shape. */
    public static Collection create(String slug, String nameEn, String nameAr, boolean published) {
        return new Collection(UUID.randomUUID(), slug, nameEn, nameAr, published);
    }

    @Override
    public UUID getId() {
        return id;
    }

    /**
     * The registry row's amendment shape — the ADMIN write use case's own
     * seam (the Saleor {@code collectionUpdate} root mapped onto the
     * registry's columns). Each argument is the ALREADY-VALIDATED
     * replacement value (the use case owns validation and the slug
     * uniqueness precheck — the {@code uq_collections_slug_live} partial
     * unique index's own no-TOCTOU window); {@code null} preserves the
     * column (partial update, the Saleor input's omit-to-preserve
     * semantics).
     */
    void revise(String slug, String nameEn, boolean published) {
        if (slug != null) {
            this.slug = slug;
        }
        if (nameEn != null) {
            this.nameEn = nameEn;
        }
        this.published = published;
    }

    /**
     * The registry row's bilingual translation seam — the ADMIN translate
     * use case's own seam (the Saleor {@code collectionTranslate} root
     * mapped onto the registry's bilingual columns; the {@code Category.translate}
     * precedent). The arguments are the ALREADY-VALIDATED replacement
     * values for the whole bilingual pair (the use case owns validation
     * and computes the merged pair — the at-least-one-name law over the
     * result, the 100-character column ceilings).
     */
    void translate(String newNameEn, String newNameAr) {
        this.nameEn = newNameEn;
        this.nameAr = newNameAr;
    }

    public String getSlug() {
        return slug;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getNameAr() {
        return nameAr;
    }

    public boolean isPublished() {
        return published;
    }
}
