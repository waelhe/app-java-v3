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
    public ResponseEntity<PagedResponse<UserSummary>> listUsers(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(identitySpi.findAllSummaries(pageable)));
    }

    public record ChangeRoleRequest(@NotBlank String role) {}

    @PutMapping("/users/{id}/role")
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
    public ResponseEntity<Void> pseudonymizeUser(@PathVariable UUID id,
                                                 @Valid @RequestBody PseudonymizeRequest request,
                                                 Authentication authentication) {
        identitySpi.pseudonymizeAccount(id, request.reason(),
                authentication != null ? authentication.getName() : null);
        return ResponseEntity.ok().build();
    }

    // -- Listings -------------------------------------------------------

    @GetMapping("/listings")
    public ResponseEntity<PagedResponse<ProviderListingSummary>> listAllListings(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogSpi.findAllSummaries(pageable)));
    }

    @PostMapping("/listings/{id}/archive")
    public ResponseEntity<ProviderListingSummary> archiveListing(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(catalogSpi.archiveListing(id, authentication));
    }

    // -- Bookings -------------------------------------------------------

    @GetMapping("/bookings")
    public ResponseEntity<PagedResponse<BookingSummary>> listBookings(
            @RequestParam(required = false) String status, Pageable pageable) {
        Page<BookingSummary> bookings = status != null && !status.isBlank()
                ? bookingSpi.listByStatusSummary(status, pageable)
                : bookingSpi.listAllSummaries(pageable);
        return ResponseEntity.ok(PagedResponse.of(bookings));
    }

    // -- Payments -------------------------------------------------------

    @GetMapping("/payments")
    public ResponseEntity<PagedResponse<PaymentSummary>> listPaymentIntents(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(paymentsSpi.listIntentsSummaries(pageable)));
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<PaymentSummary> getPaymentIntent(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentsSpi.getIntentSummary(id));
    }

    // -- Revisions / Audit history ---------------------------------------

    @GetMapping("/revisions/entities")
    public ResponseEntity<List<String>> listAuditedEntities() {
        return ResponseEntity.ok(revisionService.getEntityNames().stream().sorted().toList());
    }

    @GetMapping("/revisions/{entityName}/{id}")
    public ResponseEntity<List<RevisionService.RevisionEntry>> getRevisions(
            @PathVariable String entityName, @PathVariable UUID id) {
        return ResponseEntity.ok(revisionService.getRevisions(entityName, id));
    }
}
