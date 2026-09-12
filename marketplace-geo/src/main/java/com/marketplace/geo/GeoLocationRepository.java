package com.marketplace.geo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface GeoLocationRepository
        extends JpaRepository<GeoLocation, UUID>, RevisionRepository<GeoLocation, UUID, Integer> {

    Optional<GeoLocation> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsByParentId(UUID parentId);

    /** Direct children in stable slug order (the public children endpoint). */
    List<GeoLocation> findByParentIdOrderBySlugAsc(UUID parentId);

    /** The roots (level 0) — one by policy; a list keeps the read honest. */
    List<GeoLocation> findByParentIdIsNullOrderBySlugAsc();

    /**
     * Prefix autocomplete over the three searchable surfaces. Plain LIKE
     * on a prefix: {@code nameAr} has no case; {@code nameEn} is stored
     * trimmed as authored and matched case-sensitively (Latin names are
     * conventionally capitalized consistently in the seed); {@code slug}
     * is lowercase by validation. The service gates the prefix to >= 2
     * characters and ESCAPES the LIKE wildcards ({@code %}/{@code _}) in the
     * prefix before any query runs (CodeRabbit round 1 adoption — {@code
     * q=%%} must not match everything); the ESCAPE clause below matches the
     * service's escaping.
     */
    @Query(value = """
            SELECT * FROM geo_locations
            WHERE is_deleted = false
              AND (name_ar LIKE :prefixPattern ESCAPE '\\'
                   OR name_en LIKE :prefixPattern ESCAPE '\\'
                   OR slug LIKE :prefixPattern ESCAPE '\\')
            ORDER BY level, slug
            LIMIT :limit
            """,
            nativeQuery = true)
    List<GeoLocation> suggestByPrefix(String prefixPattern, int limit);

    /**
     * The qualified location set — the node itself plus every transitive
     * descendant — via the official PostgreSQL recursive CTE
     * (PostgreSQL Reference, Queries › WITH Queries › Recursive Queries):
     * the non-recursive term anchors on the requested node, the recursive
     * term joins children level by level. B-tree joins only — the plan's
     * D-R1/D-E3 decision (no PostGIS, no coordinate math).
     */
    @Query(value = """
            WITH RECURSIVE descendants AS (
                SELECT id, parent_id FROM geo_locations
                WHERE id = :id AND is_deleted = false
                UNION ALL
                SELECT g.id, g.parent_id
                FROM geo_locations g
                JOIN descendants d ON g.parent_id = d.id
                WHERE g.is_deleted = false
            )
            SELECT id FROM descendants
            """,
            nativeQuery = true)
    Set<UUID> findSelfAndDescendantIds(UUID id);
}
