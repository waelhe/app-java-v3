package com.marketplace.shared.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read port for the hierarchical administrative geography (realestate systems
 * plan §5, Layer 30 — implemented by the {@code marketplace-geo} module).
 *
 * <p>The model is <em>administrative, not coordinate-based</em> (plan D-R1):
 * a self-referencing tree {@code country → governorate → city → neighborhood}
 * with B-tree joins. Radius search is excluded by a measured platform gate
 * (PostGIS is unavailable on the standard Railway postgres template — V34
 * measurement + SYSTEM.md §15), and the local business model (gradual
 * expansion over named neighborhoods) is hierarchical by nature.
 *
 * <p>Cross-module contract: consumers (search, realestate) resolve locations
 * through this port only — never through a database-level dependency on the
 * geo module's tables (the same decoupling as {@code ListingPriceProvider}).
 */
public interface GeoLookupPort {

    /**
     * The full tree from the root (level 0) down, nested. One response —
     * the hierarchy is small by design (hundreds of rows at city scale).
     */
    GeoNode getTree();

    /** The direct children of the given location, in stable (slug) order. */
    List<GeoNode> getChildren(UUID parentId);

    /**
     * Prefix autocomplete over {@code nameAr}/{@code nameEn}/{@code slug}.
     * The prefix must be at least 2 characters (trimmed) — a shorter one is
     * a {@link BadRequestException} (the type-gate philosophy: reject at the
     * boundary, never run a uselessly-broad query).
     */
    List<GeoNode> suggest(String prefix);

    /**
     * The given location plus every transitive descendant — the qualified
     * location set for hierarchical filtering (plan L32: "the search does
     * not fiddle with tree depth"; it receives the resolved set through
     * this port). An unknown id is a {@link ResourceNotFoundException}.
     */
    Set<UUID> findSelfAndDescendants(UUID locationId);

    /**
     * One location by id — the existence gate used by writers that attach a
     * location to their own rows (realestate L31). Unknown id is a
     * {@link ResourceNotFoundException} (never a silently-accepted value).
     */
    GeoNode getLocation(UUID id);

    /**
     * Public read model of one node of the administrative hierarchy. The
     * {@code level} is the plan's SMALLINT (0=country, 1=governorate,
     * 2=city, 3=neighborhood) — an int, not an enum, because cross-module
     * consumers only compare and display it.
     *
     * @param id       stable identifier (seeded rows keep fixed UUIDs)
     * @param parentId the parent location, {@code null} for the root only
     * @param level    depth in the hierarchy (0-3)
     * @param nameAr   Arabic administrative name (the primary market name)
     * @param nameEn   Latin name (nullable — not every node has one)
     * @param slug     stable, URL-safe, unique key
     */
    record GeoNode(
            UUID id,
            UUID parentId,
            int level,
            String nameAr,
            String nameEn,
            String slug,
            List<GeoNode> children
    ) {
        /** Flat form (leaf views): no children carried. */
        public GeoNode(UUID id, UUID parentId, int level,
                       String nameAr, String nameEn, String slug) {
            this(id, parentId, level, nameAr, nameEn, slug, List.of());
        }
    }
}
