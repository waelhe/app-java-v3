package com.marketplace.shared.api;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * One hit of the unified search (§5.1) — the record's fields are exactly
 * the «بطاقة تعرض المصدر والتاريخ والحالة» discipline: WHICH source, WHEN,
 * WHAT state, and the stable id that opens the original record (the
 * discovery chain's last step — «فتح الأصل», never a copied record).
 *
 * <p>Serializable: unified search responses ride the same cached/serialized
 * read paths as the rest of the shared-api records (the
 * {@code PagedResponse} serialization contract — explicit
 * {@code serialVersionUID}, the Object Serialization Specification's
 * canonical-constructor form).
 *
 * @param id          the ORIGINAL record's id (open-the-original contract)
 * @param domain      the connected domain the hit came from
 * @param source      the stored source name (the honest provenance label —
 *                    {@code "listing"} / {@code "community_post"})
 * @param title       the record's display title (nullable where the source
 *                    has none)
 * @param snippet     a bounded excerpt for text-bearing sources (nullable;
 *                    the LISTINGS hit carries none — its facets live on the
 *                    domain surface)
 * @param status      the record's lifecycle state name (nullable where the
 *                    source carries no read-model status)
 * @param publishedAt the record's own publication time (nullable where the
 *                    source has no single publication fact)
 * @param locationId  the record's geo node (nullable — the caller's
 *                    location criterion, passed through, never silently
 *                    widened; the ambiguous-location rule stays a
 *                    clarification question, not a silent national query)
 */
public record UnifiedSearchHit(
        UUID id,
        UnifiedSearchDomain domain,
        String source,
        String title,
        String snippet,
        String status,
        Instant publishedAt,
        UUID locationId
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
