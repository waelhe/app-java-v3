package com.marketplace.admin;

import com.marketplace.booking.spi.BookingSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BookingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PaymentSummary;
import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.UserSummary;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
@NamedInterface("admin-api")
public class AdminController {

    private final IdentitySpi identitySpi;
    private final CatalogSpi catalogSpi;
    private final BookingSpi bookingSpi;
    private final PaymentsSpi paymentsSpi;
    private final RevisionService revisionService;

    public AdminController(IdentitySpi identitySpi,
                           CatalogSpi catalogSpi,
                           BookingSpi bookingSpi,
                           PaymentsSpi paymentsSpi,
                           RevisionService revisionService) {
        this.identitySpi = identitySpi;
        this.catalogSpi = catalogSpi;
        this.bookingSpi = bookingSpi;
        this.paymentsSpi = paymentsSpi;
        this.revisionService = revisionService;
    }

    // -- Users ----------------------------------------------------------

    @GetMapping("/users")
    @Operation(summary = "List all accounts",
            description = "Paginated user summaries — the admin roster surface. Every account "
                    + "across roles (CONSUMER/PROVIDER/ADMIN), newest-first by persistence order.")
    public ResponseEntity<PagedResponse<UserSummary>> listUsers(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(identitySpi.findAllSummaries(pageable)));
    }

    public record ChangeRoleRequest(@NotBlank String role) {}

    @PutMapping("/users/{id}/role")
    @Operation(summary = "Change an account's role",
            description = "Sets the account's role (CONSUMER/PROVIDER/ADMIN) on the users store. "
                    + "The authorization authorities that ride the role are owned by the identity "
                    + "module's role-surface contract.")
    public ResponseEntity<Void> updateUserRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        identitySpi.updateUserRole(id, request.role());
        return ResponseEntity.ok().build();
    }

    /**
     * L23 (feature-expansion roadmap §5) — administrative account
     * disable/enable. The reason is part of the contract: it is recorded with
     * the action in the audit log by the identity module. The status values
     * are pinned at the request boundary (bean validation) — the service
     * keeps its own guard as defense-in-depth for SPI callers.
     */
    public record ChangeStatusRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "DISABLED|ENABLED",
                    message = "status must be DISABLED or ENABLED") String status,
            @NotBlank String reason) {}

    @PutMapping("/users/{id}/status")
    @Operation(summary = "Disable or enable an account",
            description = "L23 administrative account state change. The reason is part of the "
                    + "contract: it is recorded with the action in the identity module's audit "
                    + "log. Status values are pinned at the request boundary (DISABLED|ENABLED).")
    public ResponseEntity<Void> updateUserStatus(@PathVariable UUID id,
                                                 @Valid @RequestBody ChangeStatusRequest request,
                                                 Authentication authentication) {
        identitySpi.updateUserStatus(id, request.status(), request.reason(),
                authentication != null ? authentication.getName() : null);
        return ResponseEntity.ok().build();
    }

    /**
     * I7 Phase 1 (account-pseudonymization-plan §7 — the administrative
     * surface, gate b-5's adopted recommendation): one-way account
     * pseudonymization. The reason is part of the contract exactly like the
     * L23 status surface — it is recorded with the action in the identity
     * module's structured audit line. POST (an action, not a resource-state
     * PUT — the plan's own verb); idempotence is the service's documented
     * no-op on an already-pseudonymized row.
     *
     * <p>Answers 503 SU-001 while the HMAC secret channel is unbound
     * ({@code PSEUDONYMIZATION_HMAC_KEY}) — the capability is OFF, not
     * broken (the PSP/MAIL gate semantics).
     */
    public record PseudonymizeRequest(@NotBlank String reason) {}

    @PostMapping("/users/{id}/pseudonymize")
    @Operation(summary = "Pseudonymize an account (one-way)",
            description = "I7 Phase 1 — one-way account pseudonymization. The reason is recorded "
                    + "with the action in the identity module's structured audit line. Idempotent "
                    + "no-op on an already-pseudonymized row. Answers 503 SU-001 while the HMAC "
                    + "secret channel (PSEUDONYMIZATION_HMAC_KEY) is unbound.")
    public ResponseEntity<Void> pseudonymizeUser(@PathVariable UUID id,
                                                 @Valid @RequestBody PseudonymizeRequest request,
                                                 Authentication authentication) {
        identitySpi.pseudonymizeAccount(id, request.reason(),
                authentication != null ? authentication.getName() : null);
        return ResponseEntity.ok().build();
    }

    /**
     * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the extended
     * purges, the plan's §7 Phase 3 row): the free-text purge maintenance
     * surface. UPDATE-only across the owning modules (base tables + Envers
     * mirrors); shared records keep their structure and non-text columns
     * (Art. 17(3)(b) / 20(4) — the plan's §4). POST (an action, not a
     * resource-state change — the plan's own verb convention); idempotent
     * by the port contract (a re-run purges zero rows and reports zero).
     *
     * <p>The guard is the service's own: the target must already be
     * pseudonymized (409 otherwise — the purge completes an erasure flow,
     * it never operates on a live account's active content).
     */
    public record ContentPurgeRequest(@NotBlank String reason) {}

    public record ContentPurgeResponse(int purgedRows) {}

    @PostMapping("/users/{id}/purge-content")
    @Operation(summary = "Purge an account's free-text content",
            description = "I7 Phase 3 — UPDATE-only free-text purge across the owning modules "
                    + "(base tables + Envers mirrors); shared records keep structure and non-text "
                    + "columns. Idempotent (a re-run reports zero). The target must already be "
                    + "pseudonymized (409 otherwise — the purge completes an erasure flow).")
    public ResponseEntity<ContentPurgeResponse> purgeUserContent(@PathVariable UUID id,
                                                                 @Valid @RequestBody ContentPurgeRequest request,
                                                                 Authentication authentication) {
        int purgedRows = identitySpi.purgeAuthoredContent(id, request.reason(),
                authentication != null ? authentication.getName() : null);
        return ResponseEntity.ok(new ContentPurgeResponse(purgedRows));
    }

    /**
     * I7 Phase 3 (account-pseudonymization-plan §2 gate b-4 — the audit
     * history purge): the audit-identity purge maintenance surface. The
     * plan's purge option, verbatim: users_aud rows deleted for the user
     * + the created_by/updated_by columns nulled across every table
     * carrying them — a heavy operation run OUTSIDE any transaction, one
     * autocommitted statement per (table, column), idempotent (a re-run
     * answers zero on both counts). POST (an action, not a
     * resource-state change — the plan's own verb convention).
     *
     * <p>The guard is the service's own: the target must already be
     * pseudonymized (409 otherwise — the purge completes an erasure flow;
     * a live account's audit trail is active history).
     */
    public record AuditPurgeRequest(@NotBlank String reason) {}

    public record AuditPurgeResponse(int scrubbedRows, int usersAudRowsDeleted) {}

    @PostMapping("/users/{id}/purge-audit-history")
    @Operation(summary = "Purge an account's audit identity",
            description = "I7 Phase 3 — users_aud rows deleted for the user plus the "
                    + "created_by/updated_by columns nulled across every carrying table; heavy "
                    + "operation outside any transaction, idempotent (a re-run answers zero on "
                    + "both counts). The target must already be pseudonymized (409 otherwise).")
    public ResponseEntity<AuditPurgeResponse> purgeUserAuditHistory(@PathVariable UUID id,
                                                                    @Valid @RequestBody AuditPurgeRequest request,
                                                                    Authentication authentication) {
        var result = identitySpi.purgeAuditHistory(id, request.reason(),
                authentication != null ? authentication.getName() : null);
        return ResponseEntity.ok(new AuditPurgeResponse(result.scrubbedRows(), result.usersAudRowsDeleted()));
    }

    // -- Listings -------------------------------------------------------

    @GetMapping("/listings")
    @Operation(summary = "List all listings (every state)",
            description = "Paginated provider-listing summaries across all lifecycle states — "
                    + "the administrative roster of the catalog, unconstrained by the public "
                    + "ACTIVE-only browse surfaces.")
    public ResponseEntity<PagedResponse<ProviderListingSummary>> listAllListings(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogSpi.findAllSummaries(pageable)));
    }

    @PostMapping("/listings/{id}/archive")
    @Operation(summary = "Archive a listing",
            description = "Administrative archive of a listing in any live state — the admin-side "
                    + "exit the provider's own archive action mirrors; answers the archived "
                    + "summary. An archived listing disappears from every public surface.")
    public ResponseEntity<ProviderListingSummary> archiveListing(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(catalogSpi.archiveListing(id, authentication));
    }

    /**
     * L37 (realestate systems plan §5 — the featured boost): the
     * administrative shading point. PUT on the promotion sub-resource —
     * a settable state, the {@code /users/{id}/role} and
     * {@code /users/{id}/status} family, not a one-shot action: the body
     * sets the window's end, and an ABSENT/null {@code until} CLEARS the
     * boost (the L36 bio-fields' own PUT contract — an admin correcting a
     * shading is the documented exit).
     *
     * <p>The window's future-boundary is validated at the SERVICE against
     * the injected Clock (one seam, one clock — the reason there is no
     * {@code @Future} here is documented there). Every shading is an
     * @Audited entity update, so the Envers revision trail with the
     * {@code updated_by} attribution is the plan's criterion-4 record;
     * this endpoint answers the resulting state.
     */
    public record SetListingPromotionRequest(java.time.Instant until) {}

    @PutMapping("/listings/{id}/promotion")
    @Operation(summary = "Set or clear a listing's featured boost",
            description = "L37 administrative shading point. The body sets the boost window's "
                    + "end; an absent/null until CLEARS the boost. The window's future-boundary "
                    + "is validated at the service against the injected Clock; every shading is "
                    + "an @Audited entity update (Envers revision trail with attribution).")
    public ResponseEntity<com.marketplace.shared.api.ListingPromotion> setListingPromotion(
            @PathVariable UUID id, @Valid @RequestBody SetListingPromotionRequest request) {
        return ResponseEntity.ok(catalogSpi.setListingPromotion(id, request.until()));
    }

    // -- Bookings -------------------------------------------------------

    @GetMapping("/bookings")
    @Operation(summary = "List bookings (optional status filter)",
            description = "Paginated booking summaries across the platform — the administrative "
                    + "oversight surface; an optional status filter narrows to one lifecycle "
                    + "state (PENDING/CONFIRMED/CANCELLED/COMPLETED).")
    public ResponseEntity<PagedResponse<BookingSummary>> listBookings(
            @RequestParam(required = false) String status, Pageable pageable) {
        Page<BookingSummary> bookings = status != null && !status.isBlank()
                ? bookingSpi.listByStatusSummary(status, pageable)
                : bookingSpi.listAllSummaries(pageable);
        return ResponseEntity.ok(PagedResponse.of(bookings));
    }

    // -- Payments -------------------------------------------------------

    @GetMapping("/payments")
    @Operation(summary = "List payment intents",
            description = "Paginated payment-intent summaries — the administrative money "
                    + "oversight surface (amount, currency, state machine position per intent).")
    public ResponseEntity<PagedResponse<PaymentSummary>> listPaymentIntents(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(paymentsSpi.listIntentsSummaries(pageable)));
    }

    @GetMapping("/payments/{id}")
    @Operation(summary = "Read one payment intent",
            description = "Single payment-intent summary by id — the administrative drill-down "
                    + "of the money oversight surface.")
    public ResponseEntity<PaymentSummary> getPaymentIntent(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentsSpi.getIntentSummary(id));
    }

    // -- Revisions / Audit history ---------------------------------------

    @GetMapping("/revisions/entities")
    @Operation(summary = "List audited entity names",
            description = "The sorted names of every @Audited entity carrying an Envers revision "
                    + "trail — the index of the audit-history surfaces below.")
    public ResponseEntity<List<String>> listAuditedEntities() {
        return ResponseEntity.ok(revisionService.getEntityNames().stream().sorted().toList());
    }

    @GetMapping("/revisions/{entityName}/{id}")
    @Operation(summary = "Read an entity's revision trail",
            description = "The Envers revision entries of one audited entity row — the raw "
                    + "revision-number/timestamp trail per id, entity-name-keyed.")
    public ResponseEntity<List<RevisionService.RevisionEntry>> getRevisions(
            @PathVariable String entityName, @PathVariable UUID id) {
        return ResponseEntity.ok(revisionService.getRevisions(entityName, id));
    }
}
