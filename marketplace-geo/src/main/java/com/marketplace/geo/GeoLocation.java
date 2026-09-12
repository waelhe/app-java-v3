package com.marketplace.geo;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * One node of the hierarchical administrative geography (realestate systems
 * plan L30 — {@code country → governorate → city → neighborhood}).
 *
 * <p>Lifecycle: reference data. The tree is seeded ({@code R__seed_geo_qudsaya})
 * and amended through the admin surface; nothing is ever hard-deleted while
 * children exist (the service answers 409 — no silent cascades). The entity
 * is {@code @Audited} per the AGENTS.md rule, so every admin amendment leaves
 * an Envers trail (the R__ seed itself bypasses Envers by nature — the
 * documented debt D-E6 of the plan).
 *
 * <p>{@code parentId} is a plain UUID column — a self-reference inside this
 * module, modeled exactly like the house's cross-module id columns (no JPA
 * relation, so no lazy-loading graph and no accidental cross-module joins);
 * the parent's existence and level are validated by the factory callers via
 * the repository (a CHECK constraint cannot assert another row's level).
 */
@Entity
@Table(name = "geo_locations")
@Audited
public class GeoLocation extends BaseEntity {

    @Id
    private UUID id;

    /** The parent node — null for the single root (level 0) only. */
    @Column(name = "parent_id")
    private UUID parentId;

    /** Depth in the hierarchy (see {@link GeoLevel}) — stored SMALLINT. */
    @Column(name = "level", nullable = false)
    private Integer level;

    /** Arabic administrative name — the primary market name. */
    @Column(name = "name_ar", nullable = false, length = 100)
    private String nameAr;

    /** Latin name — optional (not every node has an established one). */
    @Column(name = "name_en", length = 100)
    private String nameEn;

    /**
     * Stable URL-safe key, unique across the tree. Validated here
     * (lowercase latin/digits/dashes, 2-120 chars) so no casing or shape
     * drift can ever reach the column.
     */
    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    protected GeoLocation() {
    }

    private GeoLocation(UUID id, UUID parentId, int level,
                        String nameAr, String nameEn, String slug) {
        this.id = id;
        this.parentId = parentId;
        this.level = level;
        this.nameAr = requireText(nameAr, "nameAr", 100);
        this.nameEn = nameEn == null || nameEn.isBlank() ? null : nameEn.trim();
        this.slug = requireSlug(slug);
    }

    /**
     * Creates a child node under the given parent. The parent's level is
     * the caller's resolved {@code GeoLevel} (the service has loaded and
     * validated the parent row); the child level must be exactly parent+1.
     */
    public static GeoLocation createChild(GeoLocation parent, String nameAr,
                                          String nameEn, String slug) {
        if (parent == null) {
            throw new BadRequestException("A child geo location requires a parent");
        }
        GeoLevel parentLevel = GeoLevel.of(parent.level);
        if (parentLevel == GeoLevel.NEIGHBORHOOD) {
            throw new BadRequestException("Neighborhoods are the deepest level (no children)");
        }
        return new GeoLocation(UUID.randomUUID(), parent.getId(),
                parentLevel.childLevel().level(), nameAr, nameEn, slug);
    }

    /** Creates the single root (country) node — the only parentless form. */
    public static GeoLocation createRoot(String nameAr, String nameEn, String slug) {
        return new GeoLocation(UUID.randomUUID(), null, GeoLevel.COUNTRY.level(),
                nameAr, nameEn, slug);
    }

    /**
     * Admin amendment: renames (and optionally re-slugs) the node. The
     * level and parent are immutable after creation — moving a subtree is
     * a structural operation that does not exist yet (the honest surface).
     */
    public void update(String nameAr, String nameEn, String slug) {
        this.nameAr = requireText(nameAr, "nameAr", 100);
        this.nameEn = nameEn == null || nameEn.isBlank() ? null : nameEn.trim();
        this.slug = requireSlug(slug);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getParentId() { return parentId; }
    public Integer getLevel() { return level; }
    public String getNameAr() { return nameAr; }
    public String getNameEn() { return nameEn; }
    public String getSlug() { return slug; }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new BadRequestException(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private static String requireSlug(String slug) {
        if (slug == null || !slug.matches("[a-z0-9-]{2,120}")) {
            throw new BadRequestException(
                    "slug must be 2-120 lowercase latin letters, digits or dashes");
        }
        return slug;
    }
}
