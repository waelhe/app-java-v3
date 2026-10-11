package com.marketplace.discovery;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.DiscoveryCardView;
import com.marketplace.shared.api.DiscoveryRowType;
import com.marketplace.shared.api.DiscoveryRowView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Wave D-1 (plan #536 §1.4 / JT-20): the home discovery surface — the
 * aggregated rails read, the row topic page ("عرض الكل", UJ-261/262) and
 * the impression ledger's write, on the {@code NeighborhoodPostController}
 * house shapes: every endpoint sits behind the resource-server chain's
 * {@code anyRequest().authenticated()} (the membership is the scope, the
 * caller's identity resolved through the {@code CurrentUserProvider} /me
 * seam — no security-config change, the same zero-config line every layer
 * since L20 has ridden) and the closed vocabularies arrive as Strings and
 * parse through the type gates BEFORE any service call (criterion 3 — an
 * invalid value answers the house 400 with the valid vocabulary listed,
 * never an enum-binding 500).
 *
 * <p><b>The impression endpoint's 204-always contract:</b> the type gates
 * answer 400 for invalid values; everything after them — including a
 * ledger failure — never fails the browsing experience: the service
 * records best-effort and the endpoint answers 204 regardless.</p>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class DiscoveryController {

    /**
     * The topic page's documented size bound — "عرض الكل" is a bounded
     * honest page, not an unbounded dump; above it the house 400 (the
     * {@code MAX_SEARCH_QUERY_LENGTH} gate's own discipline).
     */
    static final int MAX_ROW_PAGE_SIZE = 50;

    private final DiscoveryService discoveryService;
    private final CurrentUserProvider currentUserProvider;

    public DiscoveryController(DiscoveryService discoveryService,
                               CurrentUserProvider currentUserProvider) {
        this.discoveryService = discoveryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/discovery")
    @Operation(summary = "Read my home discovery rails",
            description = "The aggregated home surface for the CALLER's scope: every rail assembled "
                    + "deterministically in §1.4's order (URGENT_ALERTS, FOLLOWED_SOURCES, LOST_FOUND, "
                    + "NEIGHBORHOOD_RECOMMENDATIONS, EVENTS_AND_OPPORTUNITIES, FOR_YOU), each card carrying "
                    + "its original source identity and an honest reason. Rails whose eligibility is empty "
                    + "are OMITTED from the response entirely (AC-20-07 — no empty rail is ever rendered). "
                    + "Each rail is bounded at 10 cards; the full filtered list lives on the row topic page. "
                    + "No active membership answers an honest empty list — never a widened scope.")
    public ResponseEntity<List<DiscoveryRowView>> home(Authentication authentication) {
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(discoveryService.homeRows(callerId));
    }

    @GetMapping("/discovery/rows/{row}")
    @Operation(summary = "Read one discovery rail's full page (the topic page)",
            description = "\"عرض الكل\" (UJ-261/262): the row's FULL page straight from the owning "
                    + "port — no 10-card bound, paged with page/size (size capped at 50, the house 400 "
                    + "above it). Merged rails (EVENTS_AND_OPPORTUNITIES, FOLLOWED_SOURCES, FOR_YOU) "
                    + "answer a deterministic recency window over their sources' pages with the sources' "
                    + "honest total; the delegated-alert rail renders its bounded official list as one "
                    + "page, never re-ranked. No active membership answers an honest empty page.")
    public ResponseEntity<PagedResponse<DiscoveryCardView>> row(
            @Parameter(description = "The rail: URGENT_ALERTS, FOLLOWED_SOURCES, LOST_FOUND, "
                    + "NEIGHBORHOOD_RECOMMENDATIONS, EVENTS_AND_OPPORTUNITIES or FOR_YOU.")
            @PathVariable DiscoveryRowType row,
            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1..50).")
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        PagedRequest request = parsePage(page, size);
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(discoveryService.rowPage(callerId, row, request));
    }

    @PostMapping("/discovery/impressions")
    @Operation(summary = "Record one card impression",
            description = "Writes one row into the discovery_impressions ledger (V176) — the display "
                    + "day is today. The repeated (user, row, source, day) quadruple is the ledger's own "
                    + "skip (the V93 ON CONFLICT bridge — a quiet no-op, never an error), and ANY "
                    + "recording failure is absorbed: the endpoint answers 204 as long as the values "
                    + "are valid — browsing never fails on bookkeeping. Invalid vocabulary values answer "
                    + "the house 400 before any write.")
    public ResponseEntity<Void> impression(
            @Valid @RequestBody ImpressionRequest request, Authentication authentication) {
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        discoveryService.recordImpression(callerId, parseRow(request.row()),
                parseSourceType(request.sourceType()), request.sourceId());
        return ResponseEntity.noContent().build();
    }

    /**
     * The topic page's paging gate: the documented bounds (page ≥ 0,
     * 1..50) BEFORE any service call — the house 400, never a binding
     * surprise deeper in the stack.
     */
    private static PagedRequest parsePage(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must not be negative");
        }
        if (size < 1 || size > MAX_ROW_PAGE_SIZE) {
            throw new BadRequestException(
                    "size must be between 1 and " + MAX_ROW_PAGE_SIZE + ", got " + size);
        }
        return PagedRequest.of(page, size);
    }

    /**
     * The row type gate (criterion 3): a String in, the enum out — an
     * invalid value answers the house 400 listing the valid vocabulary,
     * BEFORE any service call.
     */
    private static DiscoveryRowType parseRow(String raw) {
        if (raw != null) {
            try {
                return DiscoveryRowType.valueOf(raw.trim());
            } catch (IllegalArgumentException invalid) {
                // fall through to the vocabulary 400 below
            }
        }
        throw new BadRequestException(
                "Invalid row '" + raw + "' — valid values: URGENT_ALERTS, FOLLOWED_SOURCES, LOST_FOUND, "
                        + "NEIGHBORHOOD_RECOMMENDATIONS, EVENTS_AND_OPPORTUNITIES, FOR_YOU");
    }

    /**
     * The sourceType gate: the card vocabulary's closed set (the
     * {@code DiscoveryCardView} contract) — anything else answers the
     * house 400 listing the valid values, BEFORE any service call.
     */
    private static String parseSourceType(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        switch (trimmed) {
            case DiscoveryService.SOURCE_NEIGHBORHOOD_POST,
                 DiscoveryService.SOURCE_NEIGHBORHOOD_EVENT,
                 DiscoveryService.SOURCE_JOB,
                 DiscoveryService.SOURCE_URGENT_ALERT,
                 DiscoveryService.SOURCE_PROVIDER_LISTING -> {
                return trimmed;
            }
            default -> throw new BadRequestException(
                    "Invalid sourceType '" + raw + "' — valid values: NEIGHBORHOOD_POST, "
                            + "NEIGHBORHOOD_EVENT, JOB, URGENT_ALERT, PROVIDER_LISTING");
        }
    }

    /**
     * The impression body: the rail, the card's source type and the
     * source record's id — the projection's identity pair plus the rail
     * it was rendered on.
     */
    public record ImpressionRequest(
            @NotBlank
            @Schema(description = "The rail the card was rendered on: URGENT_ALERTS, FOLLOWED_SOURCES, "
                    + "LOST_FOUND, NEIGHBORHOOD_RECOMMENDATIONS, EVENTS_AND_OPPORTUNITIES or FOR_YOU.",
                    allowableValues = {"URGENT_ALERTS", "FOLLOWED_SOURCES", "LOST_FOUND",
                            "NEIGHBORHOOD_RECOMMENDATIONS", "EVENTS_AND_OPPORTUNITIES", "FOR_YOU"},
                    example = "FOR_YOU")
            String row,

            @NotBlank
            @Schema(description = "The card's source record type: NEIGHBORHOOD_POST, NEIGHBORHOOD_EVENT, "
                    + "JOB, URGENT_ALERT or PROVIDER_LISTING.",
                    allowableValues = {"NEIGHBORHOOD_POST", "NEIGHBORHOOD_EVENT", "JOB",
                            "URGENT_ALERT", "PROVIDER_LISTING"},
                    example = "NEIGHBORHOOD_POST")
            String sourceType,

            @NotNull
            @Schema(description = "The source record's id in its owner's id space.")
            UUID sourceId
    ) {
    }
}
