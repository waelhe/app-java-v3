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
     * Prefix autocomplete over the three searchable surfaces, matched
     * case-insensitively with PostgreSQL's official {@code ILIKE} operator:
     * "The key word ILIKE can be used instead of LIKE to make the match
     * case-insensitive according to the active locale" (PostgreSQL Official
     * Documentation, Functions & Operators, Pattern Matching — fetched live
     * 2026-09-26, saved at {@code scripts/doc-verify/postgres-pattern-matching.html}).
     * The comprehensive plan item 2.8 closed the measured gap here: the seed's
     * Latin names are Title Case ("Rif Dimashq"), so a case-sensitive LIKE
     * could never answer a lowercase user prefix ("rif") — the exact input
     * shape an autocomplete box receives. {@code nameAr} has no case, and
     * {@code slug} is lowercase by validation, so ILIKE changes their
     * matching only by letting capitalized user input reach them. The
     * service gates the prefix to >= 2 characters and ESCAPES the LIKE
     * wildcards ({@code %}/{@code _}) in the prefix before any query runs
     * (CodeRabbit round 1 adoption — {@code q=%%} must not match everything);
     * ILIKE supports the ESCAPE clause exactly as LIKE does, so the ESCAPE
     * clause below is unchanged. The functional-index escalation path
     * ({@code lower(name_en) text_pattern_ops}, per the same official
     * Pattern Matching section) stays deliberately unbuilt at the measured
     * six-row administrative-geography scale — a seq scan on it is already
     * the cheapest plan; the index becomes honest engineering when the
     * table's size says so.
     */
    @Query(value = """
            SELECT * FROM geo_locations
            WHERE is_deleted = false
              AND (name_ar ILIKE :prefixPattern ESCAPE '\\'
                   OR name_en ILIKE :prefixPattern ESCAPE '\\'
                   OR slug ILIKE :prefixPattern ESCAPE '\\')
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
     * D-R1/D-R3 decision (no PostGIS, no coordinate math).
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
