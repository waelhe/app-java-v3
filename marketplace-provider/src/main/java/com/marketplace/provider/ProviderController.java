package com.marketplace.provider;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProviderController {

    private final ProviderService providerService;
    private final ProviderMapper providerMapper;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderPublicPageService providerPublicPageService;
    private final ProviderBusinessPageService businessPageService;

    public ProviderController(ProviderService providerService, ProviderMapper providerMapper,
                              CurrentUserProvider currentUserProvider,
                              ProviderPublicPageService providerPublicPageService,
                              ProviderBusinessPageService businessPageService) {
        this.providerService = providerService;
        this.providerMapper = providerMapper;
        this.currentUserProvider = currentUserProvider;
        this.providerPublicPageService = providerPublicPageService;
        this.businessPageService = businessPageService;
    }

    @PostMapping("/providers")
    @Operation(summary = "Become a provider", description = "Creates the caller's provider "
            + "profile (host onboarding). L36: the persona fields (actor type, agency "
            + "name, license number) ride the creation; an omitted actor type is the "
            + "individual default.")
    public ResponseEntity<ProviderResponse> create(@Valid @RequestBody ProviderRequest request,
                                                   Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(providerMapper.toResponse(
                providerService.create(request.displayName(), request.bio(), userId,
                        request.actorType(), request.agencyName(), request.licenseNumber())));
    }

    @GetMapping("/providers/{id}")
    @Operation(summary = "Get a provider profile", description = "Public provider profile with "
            + "the stored rating average.")
    public ResponseEntity<ProviderResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(providerMapper.toResponse(providerService.getById(id)));
    }

    @PutMapping("/providers/{id}")
    @Operation(summary = "Update my provider profile", description = "Owner-scoped update of "
            + "display name, bio and the L36 persona fields. PUT replacement semantics per "
            + "field class: omitted agency name / license number clear them (the bio "
            + "contract); an omitted actor type keeps the stored classification (a "
            + "required classification is never silently reset).")
    public ResponseEntity<ProviderResponse> update(@PathVariable UUID id, @Valid @RequestBody ProviderRequest request,
                                                   Authentication authentication) {
        return ResponseEntity.ok(providerMapper.toResponse(
                providerService.update(id, request.displayName(), request.bio(),
                        request.actorType(), request.agencyName(), request.licenseNumber(),
                        authentication)));
    }

    /**
     * W1 (§4.4/§4.5): the reviews block rides the same page response, paged
     * by its own request parameters — one endpoint, two independently paged
     * blocks (the listings ride the standard pageable, the reviews ride
     * {@code reviewsPage}/{@code reviewsSize}). The manual floor (0/1) keeps
     * a hostile parameter from reaching {@code PageRequest.of} with a
     * negative value — the resolver's own clamp equivalent for the manual
     * path — and the manual ceiling mirrors the resolver's configured cap
     * ({@code spring.data.web.pageable.max-page-size: 100} in
     * application.yml): a manual {@code PageRequest.of} bypasses that cap
     * by construction (greptile W1 r2-frontend, adopted), and one anonymous
     * request must never buy itself an unbounded page plus its three batch
     * lookups. The ceiling is the resolver's own documented bound, kept as
     * the single constant both paths answer to.
     */
    @GetMapping("/providers/{id}/public")
    @Operation(summary = "Get a provider's public page",
            description = "L36: the agent/office public page — profile with the L36 persona "
                    + "fields, the VERIFIED badge status, the rating block (fresh aggregate, "
                    + "W1 dual badges per the active reviews mode) and the ACTIVE listings "
                    + "page, plus the W1 reviews block: the provider's PUBLISHED forward "
                    + "reviews paged by reviewsPage/reviewsSize (default 0/10, capped at "
                    + "100 like every resolved page). Non-VERIFIED "
                    + "profiles get an empty listings block (a suspended broker's inventory "
                    + "is hidden on his page); the response carries no private contact data "
                    + "and no user id.")
    public ResponseEntity<ProviderPublicPageResponse> getPublicPage(@PathVariable UUID id,
                                                                    Pageable pageable,
                                                                    @RequestParam(name = "reviewsPage", defaultValue = "0") int reviewsPage,
                                                                    @RequestParam(name = "reviewsSize", defaultValue = "10") int reviewsSize) {
        Pageable reviewsPageable = PageRequest.of(
                Math.max(reviewsPage, 0),
                Math.min(Math.max(reviewsSize, 1), MAX_REVIEWS_PAGE_SIZE));
        return ResponseEntity.ok(providerPublicPageService.getPublicPage(id, pageable, reviewsPageable));
    }

    /** The manual path's ceiling — the resolver's configured cap (application.yml: max-page-size: 100). */
    static final int MAX_REVIEWS_PAGE_SIZE = 100;

    @PostMapping("/admin/providers/{id}/verify")
    @Operation(summary = "Verify a provider (administrative)",
            description = "Marks the provider verified — the administrative trust decision "
                    + "that unlocks the provider's standing on the platform.")
    public ResponseEntity<ProviderResponse> verify(@PathVariable UUID id) {
        return ResponseEntity.ok(providerMapper.toResponse(providerService.verify(id)));
    }

    @PostMapping("/admin/providers/{id}/suspend")
    @Operation(summary = "Suspend a provider (administrative)",
            description = "Suspends the provider — the administrative protective exit; a "
                    + "suspended provider's inventory is hidden from the public page, while "
                    + "the profile itself stays visible with its status.")
    public ResponseEntity<ProviderResponse> suspend(@PathVariable UUID id) {
        return ResponseEntity.ok(providerMapper.toResponse(providerService.suspend(id)));
    }

    // -- W2 (yelp-level plan §5 — the business page) ------------------------

    /**
     * The declared week's request shape: each entry is the ISO weekday and
     * the day's window — the service's own PUT-replacement contract.
     */
    public record BusinessHoursRequest(java.util.List<ProviderBusinessPageService.HoursEntry> hours) {
    }

    @PutMapping("/providers/{id}/business-hours")
    @Operation(summary = "Declare my working hours",
            description = "W2 (G11): PUT replacement semantics — the request's list IS the "
                    + "declared week: a day already declared moves its window, a new day "
                    + "inserts, a day absent from the request is withdrawn. At most one "
                    + "window per weekday (the V88 unique key).")
    public ResponseEntity<java.util.List<ProviderPublicPageResponse.BusinessHourView>> replaceHours(
            @PathVariable UUID id, @Valid @RequestBody BusinessHoursRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(businessPageService
                .replaceHours(id, request.hours(), authentication)
                .stream().map(ProviderPublicPageResponse.BusinessHourView::of).toList());
    }

    @PostMapping("/providers/{id}/services")
    @Operation(summary = "Add a declared service",
            description = "W2 (G12): appends one row to the declared services list — the "
                    + "Yelp menu analog. The position is auto-allocated (max+1, the W1 "
                    + "max-allocation lesson); the money pair is integer cents + ISO 4217 "
                    + "currency, declared together or not at all.")
    public ResponseEntity<ProviderPublicPageResponse.OfferedServiceView> addService(
            @PathVariable UUID id, @Valid @RequestBody ProviderBusinessPageService.ServiceEntry entry,
            Authentication authentication) {
        return ResponseEntity.ok(ProviderPublicPageResponse.OfferedServiceView.of(
                businessPageService.addService(id, entry, authentication)));
    }

    @PutMapping("/providers/{id}/services/{serviceId}")
    @Operation(summary = "Update a declared service",
            description = "W2 (G12): PUT replacement of the row's display fields (title, "
                    + "description, duration, price) — the entity's own documented contract; "
                    + "the position key moves only through the move endpoint.")
    public ResponseEntity<ProviderPublicPageResponse.OfferedServiceView> updateService(
            @PathVariable UUID id, @PathVariable UUID serviceId,
            @Valid @RequestBody ProviderBusinessPageService.ServiceEntry entry,
            Authentication authentication) {
        return ResponseEntity.ok(ProviderPublicPageResponse.OfferedServiceView.of(
                businessPageService.updateService(id, serviceId, entry, authentication)));
    }

    /** The move request shape: the target position within the menu. */
    public record MoveServiceRequest(int position) {
    }

    @PutMapping("/providers/{id}/services/{serviceId}/position")
    @Operation(summary = "Reorder a declared service",
            description = "W2 (G12): moves the row to the target position — swap semantics "
                    + "(the target's occupant takes the mover's old position), one "
                    + "transaction, the per-provider position key never violated. Returns "
                    + "the whole menu in its new order.")
    public ResponseEntity<java.util.List<ProviderPublicPageResponse.OfferedServiceView>> moveService(
            @PathVariable UUID id, @PathVariable UUID serviceId,
            @Valid @RequestBody MoveServiceRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(businessPageService
                .moveService(id, serviceId, request.position(), authentication)
                .stream().map(ProviderPublicPageResponse.OfferedServiceView::of).toList());
    }

    @DeleteMapping("/providers/{id}/services/{serviceId}")
    @Operation(summary = "Withdraw a declared service",
            description = "W2 (G12): soft-deletes the row (the BaseEntity convention — the "
                    + "Envers trail keeps the history).")
    public ResponseEntity<Void> removeService(@PathVariable UUID id, @PathVariable UUID serviceId,
                                              Authentication authentication) {
        businessPageService.removeService(id, serviceId, authentication);
        return ResponseEntity.noContent().build();
    }

    /** The area declaration's request shape: the geo-tree node id. */
    public record ServiceAreaRequest(UUID locationId) {
    }

    @PostMapping("/providers/{id}/service-areas")
    @Operation(summary = "Declare a served area",
            description = "W2 (G13): adds one geo-tree node to the declared service areas — "
                    + "«أخدم هذه المناطق». The node must be real (the FK's own law) and "
                    + "not already declared (the unique key).")
    public ResponseEntity<Void> addArea(@PathVariable UUID id,
                                        @Valid @RequestBody ServiceAreaRequest request,
                                        Authentication authentication) {
        businessPageService.addArea(id, request.locationId(), authentication);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/providers/{id}/service-areas/{areaId}")
    @Operation(summary = "Withdraw a served area",
            description = "W2 (G13): soft-deletes the declared area — the set IS the claim.")
    public ResponseEntity<Void> removeArea(@PathVariable UUID id, @PathVariable UUID areaId,
                                           Authentication authentication) {
        businessPageService.removeArea(id, areaId, authentication);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/providers/{id}/verification")
    @Operation(summary = "Submit my ownership-verification claim",
            description = "W2 (G14): queues the claim for administrative resolution "
                    + "(UNVERIFIED/REJECTED/VERIFIED → PENDING). Display-only trust "
                    + "signal — no privilege attaches to the badge.")
    public ResponseEntity<ProviderResponse> submitVerification(@PathVariable UUID id,
                                                               Authentication authentication) {
        return ResponseEntity.ok(providerMapper.toResponse(
                providerService.submitVerification(id, authentication)));
    }

    @PostMapping("/admin/providers/{id}/verification/confirm")
    @Operation(summary = "Confirm ownership verification (administrative)",
            description = "W2 (G14): resolves a PENDING claim to VERIFIED — the «مالك "
                    + "موثّق» badge lights on the public page.")
    public ResponseEntity<ProviderResponse> confirmVerification(@PathVariable UUID id) {
        return ResponseEntity.ok(providerMapper.toResponse(
                providerService.confirmVerification(id)));
    }

    @PostMapping("/admin/providers/{id}/verification/reject")
    @Operation(summary = "Decline ownership verification (administrative)",
            description = "W2 (G14): resolves a PENDING claim to REJECTED — the owner may "
                    + "submit again; the Envers trail is the record.")
    public ResponseEntity<ProviderResponse> rejectVerification(@PathVariable UUID id) {
        return ResponseEntity.ok(providerMapper.toResponse(
                providerService.rejectVerification(id)));
    }
}
