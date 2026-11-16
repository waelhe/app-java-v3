package com.marketplace.messaging;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ListingLeadCreatedEvent;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.TooManyRequestsException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * L34 (realestate systems plan §5 — lead capture): the lead flow —
 * public submission against a live listing, the provider's inbox, and the
 * G-R6 conservative daily cap.
 *
 * <p><b>Liveness gate (the plan's acceptance criterion 2):</b> a lead
 * rides live inventory only. The hot path is the single
 * {@link CatalogSpi#getActiveById} call; only on its miss do we
 * disambiguate — {@link ListingPriceProvider#getListingInfo} (the
 * unfiltered shared-api projection) still resolving means the listing
 * exists but is not ACTIVE, which is a 409 (the lead's own business
 * rule), while both missing is the honest 404. The catalog dependency is
 * the realestate precedent (PR #299: {@code catalog :: catalog-spi} in
 * {@code allowedDependencies}).
 *
 * <p><b>The G-R6 daily cap:</b> the sender fingerprint is the
 * <b>keyed</b> HmacSHA256 hex of the client IP — never the raw address,
 * and never a bare digest either (the CodeRabbit round-1 adoption,
 * CWE-759: the IPv4 space is enumerable, so an unkeyed SHA-256 is
 * re-identifiable by anyone who reads the table; the keyed digest is
 * pseudonymization and the key never leaves the server — prod fails
 * startup without it, the {@code JwkSourceProdHardening} pattern). The
 * advisory transaction lock on the fingerprint serializes the
 * check-then-insert sequence against concurrent submissions from the
 * same sender (the {@code MediaAssetRepository} position precedent) —
 * the count is exact, not approximately bounded. A cap rejection raises
 * the house {@link TooManyRequestsException} — the same RL-001 taxonomy
 * entry and 429 problem shape the Resilience4j channel produces, without
 * impersonating its exception (whose factory requires a limiter
 * instance this counting policy does not have).
 *
 * <p><b>Notification:</b> {@link ListingLeadCreatedEvent} is published
 * inside the create transaction (the Modulith registry writes its entries
 * atomically with the business write); the notifications module's
 * {@code @ApplicationModuleListener} alerts the provider after commit
 * with the framework's retry — a delivery failure never loses the lead.
 */
@Service
public class LeadsService {

    private static final Logger log = LoggerFactory.getLogger(LeadsService.class);

    /** The provider inbox's deterministic order (the L32 stable-order lesson). */
    private static final Sort INBOX_SORT = Sort.by(
            Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ListingLeadRepository leadRepository;
    private final CatalogSpi catalogSpi;
    private final ListingPriceProvider listingPriceProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserProvider currentUserProvider;
    private final MessagingProperties properties;

    public LeadsService(ListingLeadRepository leadRepository,
                        CatalogSpi catalogSpi,
                        ListingPriceProvider listingPriceProvider,
                        ApplicationEventPublisher eventPublisher,
                        CurrentUserProvider currentUserProvider,
                        MessagingProperties properties) {
        this.leadRepository = leadRepository;
        this.catalogSpi = catalogSpi;
        this.listingPriceProvider = listingPriceProvider;
        this.eventPublisher = eventPublisher;
        this.currentUserProvider = currentUserProvider;
        this.properties = properties;
    }

    /**
     * Public submission (guest or member). The type gate already passed in
     * the request record and the entity factory; what remains is the
     * liveness gate, the daily cap, and the after-commit alert. A valid
     * JWT attributes the lead to its user; an anonymous call stays
     * unattributed (the optional-identity seam — never a forced login).
     */
    @Transactional
    public LeadResponse createLead(UUID listingId, LeadRequest request,
                                   Authentication authentication, String clientIp) {
        UUID providerId = resolveLiveListingProvider(listingId);
        UUID senderUserId = currentUserProvider.tryGetCurrentUserId(authentication).orElse(null);
        String senderIpHash = hashIp(properties.leads().ipHashKey(), clientIp);

        if (senderIpHash != null) {
            // Serialize the per-fingerprint window: the second concurrent
            // submission waits for the first to commit, then counts the
            // committed row (the advisory-lock adoption above).
            leadRepository.acquireSenderWindowLock(senderIpHash);
            Instant windowStart = Instant.now().minus(24, ChronoUnit.HOURS);
            long submitted = leadRepository.countBySenderIpHashAndCreatedAtAfter(senderIpHash, windowStart);
            if (submitted >= properties.leads().dailyCapPerSender()) {
                throw new TooManyRequestsException(
                        "Daily lead limit reached for this sender — try again tomorrow");
            }
        }

        ListingLead lead = ListingLead.create(listingId, providerId, senderUserId, senderIpHash,
                request.contactName(), request.contactPhone(), request.message());
        ListingLead saved = leadRepository.save(lead);
        eventPublisher.publishEvent(new ListingLeadCreatedEvent(
                saved.getId(), saved.getListingId(), saved.getProviderId()));
        log.info("Listing lead created: leadId={}, listingId={}, authenticatedSender={}",
                saved.getId(), listingId, senderUserId != null);
        return LeadResponse.from(saved);
    }

    /** The provider's inbox — deterministic order, optional status filter. */
    @Transactional(readOnly = true)
    public Page<LeadResponse> listLeads(UUID providerId, LeadStatus status, Pageable pageable) {
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), INBOX_SORT);
        Page<ListingLead> page = (status == null)
                ? leadRepository.findByProviderId(providerId, ordered)
                : leadRepository.findByProviderIdAndStatus(providerId, status, ordered);
        return page.map(LeadResponse::from);
    }

    /**
     * The inbox move — one-way through the status machine. The lead's
     * {@code provider_id} lives in the users.id space (the A1/V2 fact),
     * so the caller's own user id IS the ownership key: the repository
     * read is scoped to it (a foreign lead is a 404 — it is not in your
     * inbox), the same identity-scoping the messaging conversations use
     * for their participants. No profile indirection, no
     * {@code ownsProvider} detour — the measured id space makes the
     * caller's identity the direct key.
     */
    @Transactional
    public LeadResponse transitionLead(UUID leadId, UUID ownerUserId, LeadStatus target) {
        ListingLead lead = leadRepository.findByIdAndProviderId(leadId, ownerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("ListingLead", leadId));
        lead.transitionTo(target);
        return LeadResponse.from(lead);
    }

    /**
     * The liveness gate: one call on the hot path; on its miss, the
     * unfiltered projection disambiguates 404 (missing) from 409 (exists,
     * not live — the plan's "الـlead على المعروض الحي حصرًا").
     */
    private UUID resolveLiveListingProvider(UUID listingId) {
        try {
            return catalogSpi.getActiveById(listingId).providerId();
        } catch (ResourceNotFoundException notActiveOrMissing) {
            // Resolves (unfiltered) => the listing exists but is not live.
            listingPriceProvider.getListingInfo(listingId);
            throw new ConflictException(
                    "Listing is not active — leads are accepted for live listings only");
        }
    }

    /**
     * The keyed fingerprint: HmacSHA256 hex of the client IP under the
     * configured key (CWE-759 adoption — see the class javadoc), or null
     * when no address is present (e.g. a test seam). Deterministic per
     * key, so the 24h window count and the V52 partial index keep
     * working unchanged across restarts.
     */
    static String hashIp(String key, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        if (key == null || key.isBlank()) {
            // Prod fails startup on this (MessagingConfig); any other
            // profile running without the key still fails LOUDLY here
            // with the property's name — never the cryptic
            // SecretKeySpec "Empty key".
            throw new IllegalStateException(
                    "marketplace.messaging.leads.ip-hash-key must be configured —"
                            + " the lead sender fingerprint is a keyed HMAC (CWE-759)");
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(clientIp.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is mandated by the Java platform specification — unreachable.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
