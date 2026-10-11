package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * L49 (the Nextdoor-2026 completeness wave — gap #4, the events layer):
 * the neighborhood events board surface. Every endpoint sits behind the
 * resource-server chain's {@code anyRequest().authenticated()} and the
 * service's active-membership gate (403 — G-N3's default) — no
 * security-config change, the same zero-config line every layer since
 * L20 has ridden.
 *
 * <p><b>The two URL families</b> (the posts controller's own contract
 * shape): the board pair under {@code /neighborhood/events} (the
 * member's own neighborhood — the membership IS the scope, there is no
 * location parameter to read anyone else's board) and the event-scoped
 * pair under {@code /events/{id}} (the RSVP toggle, and the organizer's
 * own delete).
 *
 * <p><b>The two write limiters</b> (the plan's own «نمطلتان مسماتان
 * مستقلتان» discipline): {@code eventCreate} and {@code eventRsvp},
 * both the L29 model — named Resilience4j instances, fail fast with
 * 429 RL-001. One named instance for BOTH the RSVP and the un-RSVP:
 * they are one toggle's two directions (the postReact model verbatim).
 *
 * <p><b>The type gates</b> (criterion 3, the posts' own discipline):
 * the category and the registration model arrive as Strings and parse
 * through {@link #parseCategory(String)} / {@link #parseRegistration(String)}
 * BEFORE any service call — an invalid value answers the house 400 with
 * the valid vocabulary listed, never an enum-binding 500.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class NeighborhoodEventController {

    /**
     * The label bounds — the house {@code provider_listings.title} limit
     * (V2's own documented bound): the meeting-spot and organizer labels
     * are one-line display names, the post title's own shape.
     */
    static final int MAX_LABEL_LENGTH = 200;

    /**
     * The description's documented bound — the lead message's own limit
     * (the post body's {@code MAX_BODY_LENGTH} shape): the community
     * domain's authored-text bound lives at the type gate, exactly where
     * the posts layer pins it.
     */
    static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final NeighborhoodEventService eventService;
    private final CurrentUserProvider currentUserProvider;

    public NeighborhoodEventController(NeighborhoodEventService eventService,
                                       CurrentUserProvider currentUserProvider) {
        this.eventService = eventService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/neighborhood/events")
    @Operation(summary = "Read my neighborhood's events board",
            description = "The caller's OWN neighborhood's upcoming events, soonest first — "
                    + "the membership is the scope (there is no location parameter: one "
                    + "membership, one board — G-N1/G-N3). No active membership answers 403. "
                    + "The board is forward-looking and status-honest: only events whose start "
                    + "is still to come ride the read, and a CANCELLED or POSTPONED event never "
                    + "masquerades as upcoming (past gatherings and withdrawn ones stay in the "
                    + "audit trail, not on the board). The optional filters are the category "
                    + "(SPORTS_FAMILY/VOLUNTEER/SOCIAL/MARKET/WORKSHOP — the product's own "
                    + "chips) and, since JT-20, the status (ACTIVE/CANCELLED/POSTPONED — on "
                    + "today's board only an ACTIVE event can match); an invalid value answers "
                    + "400 before any read. Every row carries the two attendance facts "
                    + "(attending, rsvpedByMe) and its honest status. Deterministic pagination "
                    + "on the complete sort key (startsAt ASC, id ASC) — no shaky page "
                    + "boundaries.")
    public ResponseEntity<PagedResponse<NeighborhoodEventView>> board(
            @Parameter(description = "Optional category filter — SPORTS_FAMILY, VOLUNTEER, SOCIAL, MARKET or WORKSHOP")
            @RequestParam(required = false) String category,
            @Parameter(description = "Optional status filter — ACTIVE, CANCELLED or POSTPONED "
                    + "(the upcoming floor already excludes the withdrawn states)")
            @RequestParam(required = false) String status,
            Pageable pageable,
            Authentication authentication) {
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                eventService.getBoard(callerId, parseCategory(category),
                        parseStatus(status), pageable)));
    }

    @PostMapping("/neighborhood/events")
    @RateLimiter(name = "eventCreate")
    @Operation(summary = "Organize an event in my neighborhood",
            description = "Writes an event into the caller's active neighborhood. The gate "
                    + "order (before any write): the location resolves through the geo port "
                    + "(404 unknown), must be a level-3 neighborhood node (400 otherwise), "
                    + "and the caller must hold an active membership in exactly that location "
                    + "(403 otherwise). The event's own type gates follow: startsAt strictly "
                    + "in the future (400 — the board is forward-looking), endsAt absent or "
                    + "after startsAt (400), and the ONE registration/capacity rule (400: OPEN "
                    + "carries no capacity, LIMITED_SEATS/TABLE_RESERVATION carry a strictly "
                    + "positive one). Title, locationLabel and organizerLabel are bounded at "
                    + "200 characters, description at 2000.")
    public ResponseEntity<NeighborhoodEventView> create(
            @Valid @RequestBody CreateEventRequest request,
            Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        NeighborhoodEventView view = eventService.createEvent(
                authorId,
                request.locationId(),
                parseCategory(request.category()),
                request.title(),
                request.description(),
                request.startsAt(),
                request.endsAt(),
                request.locationLabel(),
                request.organizerLabel(),
                request.capacity(),
                parseRegistration(request.registration()));
        return ResponseEntity.status(201).body(view);
    }

    @DeleteMapping("/neighborhood/events/{eventId}")
    @Operation(summary = "Delete my event",
            description = "The organizer's own soft delete: the row stays (the audit trail keeps "
                    + "every revision — b-5's retention), the reads stop returning it, and the "
                    + "seats follow in the read path. Only the organizer — anyone else answers "
                    + "403; an unknown event answers 404.")
    public ResponseEntity<Void> delete(
            @PathVariable UUID eventId,
            Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        eventService.deleteByOrganizer(authorId, eventId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/events/{eventId}/rsvp")
    @RateLimiter(name = "eventRsvp")
    @Operation(summary = "Take a seat on an event (one seat per member)",
            description = "L49 — the board's attendance write. The gate order is the L47 "
                    + "reaction order verbatim: the event gate first (an unknown or deleted "
                    + "event answers the honest 404 — a deleted event's seats are absent "
                    + "exactly as the event itself is), then the active-membership gate in "
                    + "the event's OWN neighborhood (403 — a seat is a community commitment "
                    + "like a comment), then the one-seat check (409 — «مقعد واحد لكل عضو»), "
                    + "and only then the capacity count (409 when the seated states' capacity "
                    + "is full — OPEN events never capacity-gate). The seat read serializes "
                    + "on the event row (PESSIMISTIC_WRITE), so concurrent seats queue in "
                    + "order and the count cannot race past capacity. The board read carries "
                    + "the live count and the caller's own seat (attending / rsvpedByMe).")
    public ResponseEntity<EventRsvpView> rsvp(
            @PathVariable UUID eventId,
            Authentication authentication) {
        UUID memberId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(201).body(eventService.rsvp(memberId, eventId));
    }

    @DeleteMapping("/events/{eventId}/rsvp")
    @RateLimiter(name = "eventRsvp")
    @Operation(summary = "Free my seat on an event",
            description = "L49 — the un-RSVP. The gate order matches the RSVP (the event "
                    + "gate's honest 404, then the membership gate's 403); a member with no "
                    + "LIVE seat on the event answers the honest 404 (there is nothing to "
                    + "free). The free is the house soft delete: the row stays (b-5's "
                    + "retention, the Envers trail keeps the revision) and the seat is free "
                    + "for a fresh one. 204 on success.")
    public ResponseEntity<Void> unrsvp(
            @PathVariable UUID eventId,
            Authentication authentication) {
        UUID memberId = currentUserProvider.getCurrentUserId(authentication);
        eventService.unrsvp(memberId, eventId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The category type gate (criterion 3): a String in, the enum out —
     * an invalid value answers the house 400 listing the valid
     * vocabulary, BEFORE any service call (and therefore before any
     * read or write).
     */
    private static EventCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EventCategory.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid category '" + raw + "' — valid values: SPORTS_FAMILY, VOLUNTEER, SOCIAL, MARKET, WORKSHOP");
        }
    }

    /**
     * The registration type gate (the same discipline): a String in, the
     * enum out — an invalid value answers the house 400 listing the
     * design's own three states, BEFORE any service call.
     */
    private static EventRegistration parseRegistration(String raw) {
        try {
            return EventRegistration.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid registration '" + raw + "' — valid values: OPEN, LIMITED_SEATS, TABLE_RESERVATION");
        }
    }

    /**
     * JT-20: the status type gate (the same discipline): a String in,
     * the enum out — an invalid value answers the house 400 listing the
     * gathering state's own three values, BEFORE any service call. Null
     * or blank is the absent filter (the optional axis).
     */
    private static NeighborhoodEventStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return NeighborhoodEventStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid status '" + raw + "' — valid values: ACTIVE, CANCELLED, POSTPONED");
        }
    }

    /**
     * The organize body: the target neighborhood (the organizer's own —
     * the service gates the match), the product's own five fields, and
     * the ONE registration/capacity pair.
     */
    public record CreateEventRequest(
            @NotNull
            @Schema(description = "The geo tree node id of the organizer's neighborhood — must be "
                    + "the caller's active membership location (a level-3 node).",
                    example = "11111111-1111-4111-8111-111111111104")
            UUID locationId,

            @NotBlank
            @Schema(description = "The event's category — the product's own filter chips.",
                    allowableValues = {"SPORTS_FAMILY", "VOLUNTEER", "SOCIAL", "MARKET", "WORKSHOP"},
                    example = "VOLUNTEER")
            String category,

            @NotBlank
            @Size(max = 200)
            @Schema(description = "The event's headline (max 200 characters).", maxLength = 200,
                    example = "Neighborhood park cleanup morning")
            String title,

            @NotBlank
            @Size(max = MAX_DESCRIPTION_LENGTH)
            @Schema(description = "What happens, what to bring (max " + MAX_DESCRIPTION_LENGTH
                    + " characters).",
                    maxLength = MAX_DESCRIPTION_LENGTH)
            String description,

            @NotNull
            @Schema(description = "The start timestamp (ISO-8601) — strictly in the future; the "
                    + "board is forward-looking.",
                    example = "2026-10-02T08:00:00Z")
            Instant startsAt,

            @Schema(description = "The end timestamp (ISO-8601) — optional (a gathering may be "
                    + "open-ended); strictly after startsAt when present.",
                    example = "2026-10-02T11:00:00Z")
            Instant endsAt,

            @NotBlank
            @Size(max = MAX_LABEL_LENGTH)
            @Schema(description = "The in-neighborhood meeting spot's display label "
                    + "(max 200 characters).", maxLength = 200,
                    example = "Community garden — main gate")
            String locationLabel,

            @NotBlank
            @Size(max = MAX_LABEL_LENGTH)
            @Schema(description = "The organizing body's display label (max 200 characters).",
                    maxLength = 200,
                    example = "Neighborhood development committee")
            String organizerLabel,

            @Schema(description = "Capacity in seats (or tables, for TABLE_RESERVATION) — "
                    + "required and strictly positive for the two seated states, must be "
                    + "absent for OPEN.",
                    example = "20")
            Integer capacity,

            @NotBlank
            @Schema(description = "The registration model — the design's own three states.",
                    allowableValues = {"OPEN", "LIMITED_SEATS", "TABLE_RESERVATION"},
                    example = "LIMITED_SEATS")
            String registration
    ) {
    }
}
