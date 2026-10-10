package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): one hit from one source. Identifiers and stored
 * text only — the client routes itself ({@code route}) to the owning
 * surface; the orchestrator never re-renders a domain record.
 *
 * <p>Visibility is the SOURCE's contract: an adapter only ever returns
 * rows its own public read surfaces may show (the plan's «لا تسرب» gate —
 * hidden/withdrawn/expired rows never reach a hit).
 */
public record UnifiedSearchHit(
        UnifiedSearchSource source,
        UUID id,
        String title,
        String snippet,
        UUID locationId,
        String route
) {
}
