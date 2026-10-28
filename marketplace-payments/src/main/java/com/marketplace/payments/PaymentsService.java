package com.marketplace.payments;

import com.marketplace.payments.spi.PaymentIntentSearch;
import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.payments.spi.SettlementEligibilityReport;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.Currencies;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.PaymentSummary;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

import io.micrometer.observation.annotation.Observed;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
@Validated
public class PaymentsService implements PaymentsSpi {

    private static final Logger log = LoggerFactory.getLogger(PaymentsService.class);

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserProvider currentUserProvider;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final PaymentWebhookSecurity paymentWebhookSecurity;
    private final WebhookEventRecorder webhookEventRecorder;
    private final PaymentIntentSettlementService paymentIntentSettlementService;
    private final SettlementEligibilityService settlementEligibilityService;
    private final ObjectProvider<PspChannel> pspChannel;
    private final CommissionProperties commissionProperties;

    public PaymentsService(PaymentIntentRepository paymentIntentRepository,
                           PaymentRepository paymentRepository,
                           PaymentWebhookEventRepository webhookEventRepository,
                           ApplicationEventPublisher eventPublisher,
                           CurrentUserProvider currentUserProvider,
                           BookingParticipantProvider bookingParticipantProvider,
                           PaymentWebhookSecurity paymentWebhookSecurity,
                           WebhookEventRecorder webhookEventRecorder,
                           PaymentIntentSettlementService paymentIntentSettlementService,
                           SettlementEligibilityService settlementEligibilityService,
                           ObjectProvider<PspChannel> pspChannel,
                           CommissionProperties commissionProperties) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.eventPublisher = eventPublisher;
        this.currentUserProvider = currentUserProvider;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentWebhookSecurity = paymentWebhookSecurity;
        this.webhookEventRecorder = webhookEventRecorder;
        this.paymentIntentSettlementService = paymentIntentSettlementService;
        this.settlementEligibilityService = settlementEligibilityService;
        this.pspChannel = pspChannel;
        this.commissionProperties = commissionProperties;
    }

    public boolean processWebhookEvent(String provider, String eventId, String eventType, String signature) {
        return processWebhookEvent(provider, eventId, eventType, signature, null, null);
    }

    public boolean processWebhookEvent(String provider, String eventId, String eventType, String signature,
                                       UUID paymentIntentId, String externalId) {
        // D-009: every dispatch-relevant field rides inside the MAC — a
        // captured signature can no longer be re-pointed at another
        // provider (the dedup key's left half) or another payment intent
        // (the confirmIntent target on this internal path).
        paymentWebhookSecurity.validateSignature(provider, eventId, eventType, paymentIntentId, externalId, signature);
        return handleVerifiedWebhook(provider, eventId, eventType, paymentIntentId, externalId);
    }

    /**
     * Provider-verified webhook dispatch: the signature was verified by the
     * channel itself (Stripe: SDK constructEvent with DEFAULT_TOLERANCE), so
     * only deduplication and dispatch remain. The event row is inserted and
     * committed FIRST (in its own transaction, {@link WebhookEventRecorder})
     * — the unique (provider, event_id) index is the serialization point, so a
     * concurrent delivery of the same event loses cleanly here and is answered
     * as already-processed instead of failing later with a 5xx (CodeRabbit
     * #241, B5 provider-scoped). A dispatch that fails rolls this transaction
     * back AND removes the committed row, so the provider's retry re-processes
     * the event instead of hitting the dedup gate forever.
     */
    boolean handleVerifiedWebhook(String provider, String eventId, String eventType,
                                  UUID paymentIntentId, String externalId) {
        return handleVerifiedWebhook(provider, eventId, eventType, paymentIntentId, externalId, null, null);
    }

    /**
     * Full verified dispatch (L19): the Stripe route forwards the refund
     * snapshot extracted from {@code charge.refunded} payloads; the legacy
     * HMAC route has no payload and passes none.
     */
    boolean handleVerifiedWebhook(String provider, String eventId, String eventType,
                                  UUID paymentIntentId, String externalId,
                                  PspChannel.RefundSnapshot refund) {
        return handleVerifiedWebhook(provider, eventId, eventType, paymentIntentId, externalId, refund, null);
    }

    /**
     * R10 (Wave 2) — the verified dispatch against the durable inbox: the
     * recorder commits the row (RECEIVED, full re-delivery contract, raw
     * payload) in its own transaction FIRST, the dispatch runs, and the row's
     * terminal state follows the dispatch's own transaction discipline — the
     * settlement events are marked SETTLED inside the settlement transaction
     * (atomic with the money), the rest are marked by the idempotent backstop
     * below. The measured defect this closes: a worker stop between the
     * recorder's commit and the dispatch's completion used to strand the dedup
     * row forever (the provider's retries answered already-processed against
     * a tombstone) — the money transition was lost permanently. Now the
     * stranded row stays RECEIVED and the recovery sweep re-delivers it.
     *
     * <p>Failure families keep their documented semantics: a dispatch that
     * throws while the worker is alive still triggers the compensating delete
     * (the provider retry re-processes — byte-compatible with the pre-R10
     * contract); the backstop mark sits OUTSIDE that catch so a mark failure
     * after a clean settlement can never delete the row of an event whose
     * settlement already committed (the row would simply stay RECEIVED and be
     * recovered — re-delivery completes as the settlement's own idempotent
     * no-op).</p>
     */
    boolean handleVerifiedWebhook(String provider, String eventId, String eventType,
                                  UUID paymentIntentId, String externalId,
                                  PspChannel.RefundSnapshot refund, String rawPayload) {
        if (webhookEventRepository.findByProviderAndEventId(provider, eventId).isPresent()) {
            return false;
        }
        try {
            webhookEventRecorder.record(provider, eventId, eventType, rawPayload,
                    paymentIntentId, externalId,
                    refund != null ? refund.refundedAmountCents() : null);
        } catch (DataIntegrityViolationException ex) {
            // Distinguish a lost concurrent-duplicate race from every OTHER
            // integrity failure (CodeRabbit #242 round 2: e.g. an oversized
            // provider/eventId/eventType on the legacy route makes the insert
            // fail on column limits — that is NOT "already processed", and
            // acknowledging it with 200 would swallow the event). Only a row
            // that actually exists under this provider+eventId is the
            // concurrent duplicate; anything else must surface.
            if (webhookEventRepository.findByProviderAndEventId(provider, eventId).isEmpty()) {
                throw ex;
            }
            log.info("Webhook event {} concurrently recorded by another delivery — answering already-processed: {}",
                    eventId, ex.getMostSpecificCause().getMessage());
            return false;
        }
        try {
            dispatchWebhookEvent(eventType, paymentIntentId, externalId, refund,
                    new WebhookEventRef(provider, eventId));
        } catch (RuntimeException ex) {
            // The row is committed but the event was NOT processed: remove it
            // so the provider retry re-processes instead of being deduplicated
            // against a tombstone (recorded-and-lost).
            try {
                webhookEventRecorder.delete(provider, eventId);
            } catch (RuntimeException cleanupEx) {
                // Never mask the ORIGINAL dispatch failure — but the surviving
                // RECEIVED row is no longer an operator action: the recovery
                // sweep re-delivers it automatically (R10).
                log.error("Webhook event {} dispatch failed AND the compensating delete failed — the inbox row"
                        + " survives as RECEIVED and the recovery sweep will re-deliver it. Cleanup failure:",
                        eventId, cleanupEx);
            }
            throw ex;
        }
        // R10 backstop — idempotent: the settlement types were already marked
        // inside the settlement's own transaction; the non-settlement types
        // (charge.refunded sync, log-only) are marked here in the carrier's
        // transaction, atomic with their dispatch effects.
        webhookEventRecorder.markSettled(provider, eventId);
        return true;
    }

    /**
     * Stripe webhook entry point: verifies the notification with the
     * provider's own signature scheme, resolves the local intent via the
     * psp_intent_id link (V33) — metadata as the documented cross-check —
     * and runs the verified dispatch. The 503 SU-001 inert answer mirrors
     * the MAIL / MEDIA_S3 provider gates when the channel is unbound.
     */
    @Observed(name = "payment.psp.webhook")
    public boolean handleStripeWebhook(String rawPayload, String signatureHeader) {
        PspChannel channel = requireChannel();
        PspChannel.VerifiedWebhook verified = channel.verifyWebhook(rawPayload, signatureHeader);
        UUID intentId = verified.marketplaceIntentId();
        if (intentId == null && verified.pspIntentId() != null) {
            intentId = paymentIntentRepository.findByPspIntentId(verified.pspIntentId())
                    .map(PaymentIntent::getId)
                    .orElse(null);
        }
        if (intentId == null && "payment_intent.succeeded".equals(verified.eventType())) {
            // Reject BEFORE recording: a recorded-but-unresolved event is
            // deduplicated forever while never having confirmed the intent —
            // the provider would keep answering 202 for a lost transition
            // (CodeRabbit #241). A non-2xx answer makes Stripe retry, and by
            // the next delivery the intent exists and resolves through the
            // metadata path or the V33 psp_intent_id link.
            throw new ConflictException(
                    "payment_intent.succeeded event " + verified.eventId()
                            + " could not be resolved to a marketplace payment intent —"
                            + " rejecting so the provider retries it");
        }
        return handleVerifiedWebhook("stripe", verified.eventId(), verified.eventType(),
                intentId, verified.pspIntentId(), verified.refund(), rawPayload);
    }

    /**
     * Verified dispatch with the optional refund snapshot extracted from
     * {@code charge.refunded} notifications (L19).
     */
    void dispatchWebhookEvent(String eventType, UUID paymentIntentId, String externalId,
                              PspChannel.RefundSnapshot refund) {
        dispatchWebhookEvent(eventType, paymentIntentId, externalId, refund, null);
    }

    /**
     * R10 (Wave 2) — the recovery sweep's re-delivery entry point: dispatches
     * a recorded inbox row through the SAME verified-dispatch contract the
     * original delivery used (signature verification already happened at
     * record time — the row exists because the notification was verified,
     * so the re-delivery must not re-verify anything).
     *
     * <p><b>Why this is public when {@link #dispatchWebhookEvent} stays
     * package-private:</b> the recovery component calls it through the Spring
     * proxy, and the documented proxying rule (Reference › AOP › Proxying
     * Mechanisms — the N2 lesson this module already carries) is that
     * non-public methods are not reliably advised; the webhook routes reach
     * the package-private form through self-invocation inside this class,
     * the recovery sweep reaches this public form from its own bean. The
     * caller is always inside the payments module — the module boundary is
     * Modulith's, not this method's.</p>
     */
    public void redeliverWebhookEvent(String provider, String eventId, String eventType,
                                      UUID paymentIntentId, String externalId, Long refundAmountCents) {
        PspChannel.RefundSnapshot refund =
                refundAmountCents != null ? new PspChannel.RefundSnapshot(refundAmountCents) : null;
        dispatchWebhookEvent(eventType, paymentIntentId, externalId, refund,
                new WebhookEventRef(provider, eventId));
    }

    /**
     * R10 (Wave 2) — the dispatch carrying its inbox row: the settlement
     * events hand the row to {@link PaymentIntentSettlementService} so the
     * SETTLED mark commits atomically with the money; the other event types
     * leave the marking to the caller's idempotent backstop.
     */
    void dispatchWebhookEvent(String eventType, UUID paymentIntentId, String externalId,
                              PspChannel.RefundSnapshot refund, WebhookEventRef inboxRow) {
        switch (eventType) {
            case "payment_intent.succeeded" -> {
                if (paymentIntentId != null) {
                    log.info("Webhook dispatch: payment_intent.succeeded for intent {}", paymentIntentId);
                    paymentIntentSettlementService.confirm(paymentIntentId, externalId, inboxRow);
                } else {
                    log.warn("Webhook payment_intent.succeeded missing paymentIntentId: eventType={}", eventType);
                }
            }
            case "payment_intent.processing" ->
                log.info("Webhook: payment intent processing confirmed by gateway: eventType={}", eventType);
            case "payment_intent.payment_failed" -> {
                // L19 — the closed loop: a failed payment now flips the local
                // intent/payment to FAILED (event + cache invalidation) instead
                // of dying as a log line while the booking stays "paid".
                if (paymentIntentId != null) {
                    log.warn("Webhook dispatch: payment_intent.payment_failed for intent {}", paymentIntentId);
                    paymentIntentSettlementService.fail(paymentIntentId, inboxRow);
                } else {
                    log.warn("Webhook payment_intent.payment_failed missing paymentIntentId: eventType={}", eventType);
                }
            }
            // Row 15 (agent findings 2026-10-26T11:43 export): the provider's
            // own terminal resolution for an intent IT cancelled or let
            // expire — no money moved, so a CANCELLATION_PENDING abandonment
            // resolves to FAILED through the settlement's own edge. Every
            // other state is the settlement's idempotent no-op read (the
            // pre-fix dispatch ignored these types entirely — a logged
            // default; the resolution only ever touches the abandonment).
            case "payment_intent.canceled", "payment_intent.expired" -> {
                if (paymentIntentId != null) {
                    log.info("Webhook dispatch: {} for intent {}", eventType, paymentIntentId);
                    paymentIntentSettlementService.resolveAbandonment(paymentIntentId, inboxRow);
                } else {
                    log.warn("Webhook {} missing paymentIntentId — nothing to resolve", eventType);
                }
            }
            case "charge.refunded" -> {
                // L19 — the async safety net: refunds completed at the provider
                // (including dashboard-created ones) sync the local books to
                // the remote cumulative actual. Best-effort by design (roadmap
                // debt D4): an unresolvable intent is warned and acknowledged,
                // never rejected into a retry loop.
                if (refund != null && paymentIntentId != null) {
                    log.info("Webhook dispatch: charge.refunded for intent {} cumulative {} cents",
                            paymentIntentId, refund.refundedAmountCents());
                    syncRemoteRefund(paymentIntentId, refund.refundedAmountCents());
                } else {
                    log.warn("Webhook charge.refunded without refund snapshot or resolvable intent: eventType={}",
                            eventType);
                }
            }
            default ->
                log.debug("Unhandled webhook event type: {}", eventType);
        }
    }

    @Transactional(readOnly = true)
    @Cacheable("paymentIntents")
    public PaymentIntent getIntent(UUID id) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<PaymentIntent> listIntents(Pageable pageable) {
        return paymentIntentRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<PaymentSummary> listIntentsSummaries(Pageable pageable) {
        return paymentIntentRepository.findAllSummariesBy(pageable).map(this::toPaymentSummaryFromView);
    }

    /**
     * The unified finance-operator read model's search (payments plan
     * Phase 1 — "investigate any origin by a single search"): the AND of
     * every present field, over the specification mechanism the repository
     * already exposes. The neutral search is the plain list — the caller
     * decides which surface it answers, this method only composes
     * predicates.
     */
    @Transactional(readOnly = true)
    @Override
    public Page<PaymentSummary> searchIntentsSummaries(PaymentIntentSearch search, Pageable pageable) {
        if (search == null || search.isNeutral()) {
            return listIntentsSummaries(pageable);
        }
        // The status word is validated by the vocabulary's owner — a typo
        // answers the house 400, never a silent zero-match page (the
        // seam carries words; the enum stays inside the module).
        if (search.status() != null) {
            try {
                PaymentIntentStatus.valueOf(search.status());
            } catch (IllegalArgumentException unknownWord) {
                throw new BadRequestException("Unknown payment status: " + search.status()
                        + " — the state machine's vocabulary is: " + java.util.Arrays.toString(
                                PaymentIntentStatus.values()));
            }
        }
        Specification<PaymentIntent> spec = PaymentIntentSpecifications.fromSearch(search);
        return paymentIntentRepository.findAll(spec, pageable)
                .map(this::toPaymentSummary);
    }

    /**
     * W11 (the origin-state matrix's G5 closure — ADR-0013 D3): the
     * settlement-eligibility operational read — a pure delegation to the
     * law's owner ({@link SettlementEligibilityService}), exactly the way
     * the settlement command delegates to its own extracted bean. The
     * admin authorization gate lives on the consuming controller surface
     * (the financial-facts read's own rule); this method is the read
     * itself — no mutation, no events, no money movement (ADR-0013 D4).
     */
    @Override
    @Transactional(readOnly = true)
    public SettlementEligibilityReport settlementEligibility(UUID providerId, String origin, Pageable pageable) {
        return settlementEligibilityService.eligibility(providerId, origin, pageable);
    }

    private PaymentSummary toPaymentSummaryFromView(PaymentIntentSummaryView view) {
        return new PaymentSummary(
                view.getId(),
                view.getBookingId(),
                view.getAdCampaignId(),
                view.getOrderId(),
                view.getLoanId(),
                view.getConsumerId(),
                view.getAmountCents(),
                view.getCurrency(),
                view.getOrigin(),
                view.getStatus().name(),
                view.getRefundedAmountCents(),
                view.getCommissionRate(),
                view.getPspIntentId(),
                view.getCreatedAt(),
                view.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public PaymentSummary getIntentSummary(UUID id) {
        return toPaymentSummary(getIntent(id));
    }

    @Transactional(readOnly = true)
    public PaymentIntent getIntentForUser(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntent(id);
        verifyConsumerOwnership(intent, authentication);
        return intent;
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntent createIntent(UUID bookingId, UUID consumerId, String idempotencyKey) {
        // The caller-selected key namespace is CLOSED to the server's own
        // deterministic payable keys (CodeRabbit agent findings, rows
        // 17/18): booking callers choose their keys, while the order and
        // loan checkout paths derive theirs as "order-{orderId}" /
        // "loan-{loanId}" — a booking key that pre-occupies the derived
        // namespace could make a cheaper booking intent satisfy an order's
        // or loan's payment gate. The prefix refusal is the structural
        // isolation; the origin+payable verification on the reuse paths is
        // the defense in depth (the same two-layer model the V81 unique
        // index rides behind the friendly gate).
        if (idempotencyKey != null && isServerPayableKey(idempotencyKey)) {
            throw new ConflictException("Idempotency key '" + idempotencyKey
                    + "' is reserved for a server-generated payable — booking callers must choose their own key");
        }
        // Idempotency: return existing intent if same key
        if (idempotencyKey != null) {
            var existing = paymentIntentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().getConsumerId().equals(consumerId)) {
                    throw new AccessDeniedException("Idempotency key belongs to another consumer");
                }
                return existing.get();
            }
        }

        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(bookingId);
        bookingInfo.requireParticipant(consumerId);
        bookingInfo.requireStatus("CONFIRMED", "create payment intent");

        // R4 (comprehensive-review-ar-fix plan §4/R4 — one collectible
        // attempt per booking): a fresh intent requires the booking's
        // previous attempt to have FAILED or been CANCELLED — any other
        // live row blocks the creation. The pre-fix code's only dedup
        // surface was the CALLER-SUPPLIED (optional) idempotency key, so
        // multiple intents per booking were reachable by construction and
        // processing each charged the booking again. The guard is the
        // friendly failure; the partial unique index
        // uq_payment_intents_one_active_attempt (V81) is the concurrency
        // backstop for the race past it (the V67 house shape).
        paymentIntentRepository
                .findFirstByBookingIdAndStatusNotInOrderByCreatedAtDescIdDesc(
                        bookingId, PaymentIntentStatus.RETRYABLE)
                .ifPresent(blocking -> {
                    throw new ConflictException(
                            "Booking " + bookingId + " already has a payment intent in state "
                                    + blocking.getStatus() + " (" + blocking.getId()
                                    + ") — a new attempt requires the previous one to have failed"
                                    + " or been cancelled");
                });

        PaymentIntent intent = PaymentIntent.create(bookingId, consumerId, bookingInfo.priceCents(),
                bookingInfo.currency(), idempotencyKey, commissionProperties.rate());
        PaymentIntent saved = paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(saved.getId(), "INITIATED"));
        return saved;
    }

    /**
     * W5 (yelp-level plan §5 — the ads & billing wave, G24): the ad bill's
     * intent — the plan's «وظيفة خصم دورية تُصدر نية دفع». The payer is the
     * PROVIDER (the campaign's advertiser), the booking coupling is lifted
     * the W1 way (origin + cross-column CHECK, V103), and idempotency is
     * the DETERMINISTIC window key {@code ad-debit-{campaignId}-{windowStart}}
     * — the column's own UNIQUE index rejects the replay of a settled
     * window, the plan's structural answer to «إعادة المحاولة أو تداخل
     * الجدولة يستحيلان معًا».
     *
     * <p>Replay of an existing key returns the existing intent (the
     * BOOKING path's own contract); a mismatched payer rejects with
     * ACCESS_DENIED (the same ownership law). Settlement rides the
     * existing surfaces — the admin {@code confirmIntent} today, the PSP
     * webhook when E1 (Stripe) lands — the provider-facing pay flow is
     * that wave's concern, not this one's.</p>
     */
    public PaymentIntent createAdIntent(UUID providerId, UUID campaignId, LocalDate windowStart,
                                        long amountCents, String currency, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = paymentIntentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().getConsumerId().equals(providerId)) {
                    throw new AccessDeniedException("Idempotency key belongs to another payer");
                }
                // Agent findings 2026-10-26T13:11 export, row 39: the replay
                // decision must return THE window's own payable — a
                // same-payer intent from the caller-writable booking
                // namespace that pre-occupied the key must never satisfy
                // the ad bill's line (the billing event would be treated
                // as handled while its AD intent was never created). The
                // complete binding check (origin, campaign, amount,
                // currency) mirrors the loan settlement's own
                // authoritative-payable verification — the honest refusal
                // on any mismatch, never a silent adoption of unrelated
                // money. The booking surface's own prefix refusal
                // (isServerPayableKey) is the structural isolation layer;
                // this is the defense in depth on the reuse path.
                PaymentIntent found = existing.get();
                if (!PaymentIntent.ORIGIN_AD.equals(found.getOrigin())
                        || !campaignId.equals(found.getAdCampaignId())
                        || found.getAmountCents() == null || found.getAmountCents() != amountCents
                        || found.getCurrency() == null || !found.getCurrency().equals(currency)) {
                    throw new ConflictException("Idempotency key '" + idempotencyKey
                            + "' is held by a non-matching payment intent (" + found.getId()
                            + ", origin=" + found.getOrigin() + ") — the ad window's payable"
                            + " cannot be satisfied by it");
                }
                return found;
            }
        }
        if (amountCents <= 0) {
            // The billing run never emits zero-window events (no charge row,
            // no event); the guard keeps the surface honest on its own terms.
            throw new ConflictException("An ad bill intent requires a positive amount: " + amountCents + " cents");
        }
        PaymentIntent intent = PaymentIntent.createForAds(providerId, campaignId,
                amountCents, currency, idempotencyKey, commissionProperties.rate());
        PaymentIntent saved = paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(saved.getId(), "INITIATED"));
        return saved;
    }

    /**
     * Stage 6 (ADR-0002): the order-checkout intent — the
     * {@code createAdIntent} twin verbatim: the deterministic
     * {@code order-{orderId}} idempotency key is the first line of
     * defense (the replay answers the SAME intent), the V184
     * order_id UNIQUE constraint is the concurrency backstop, and the INITIATED
     * event keeps the notification vocabulary continuous.
     */
    public PaymentIntent createOrderIntent(UUID orderId, UUID consumerId,
                                           long amountCents, String currency) {
        String idempotencyKey = "order-" + orderId;
        // The part-1 review note: normalize ONCE, before the lookup — a
        // retry arriving as "sar" (or null) must meet the stored "SAR" as
        // the SAME payable, never a 409; the entity factory's own
        // normalization/default is the authority, applied here so the
        // replay comparison and the creation both see the one form.
        String normalizedCurrency = Currencies.normalizeOrDefault(currency, Currencies.DEFAULT_CODE);
        var existing = paymentIntentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PaymentIntent found = existing.get();
            if (!found.getConsumerId().equals(consumerId)) {
                throw new AccessDeniedException("Idempotency key belongs to another payer");
            }
            // CodeRabbit agent findings, row 18: the deterministic order key
            // must return THE order's own payable — a same-payer intent that
            // pre-occupied the key (any origin, any amount) must never
            // satisfy this order's payment gate. The complete binding check
            // mirrors the delivery settlement's own authoritative-payable
            // verification (DeliveryService.onPaymentStateChanged): origin,
            // subject, amount, and currency must ALL match, else the honest
            // refusal — money never moves on a mismatched identifier.
            if (!PaymentIntent.ORIGIN_ORDER.equals(found.getOrigin())
                    || !orderId.equals(found.getOrderId())
                    || found.getAmountCents() == null || found.getAmountCents() != amountCents
                    || found.getCurrency() == null || !found.getCurrency().equals(normalizedCurrency)) {
                throw new ConflictException("Idempotency key '" + idempotencyKey
                        + "' is held by a non-matching payment intent (" + found.getId()
                        + ", origin=" + found.getOrigin() + ") — the order's payable"
                        + " cannot be satisfied by it");
            }
            return found;
        }
        if (amountCents <= 0) {
            throw new ConflictException("An order intent requires a positive amount: " + amountCents + " cents");
        }
        PaymentIntent intent = PaymentIntent.createForOrder(orderId, consumerId,
                amountCents, normalizedCurrency, idempotencyKey, commissionProperties.rate());
        PaymentIntent saved = paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(saved.getId(), "INITIATED"));
        return saved;
    }

    /** Unique delivery payable resolved from the delivery domain's persisted quote. */
    @Observed(name = "payment.delivery-intent.create")
    public PaymentIntent createDeliveryIntent(UUID deliveryRequestId, UUID requesterId, long amountMinor, String currency) {
        if (deliveryRequestId == null || requesterId == null || amountMinor <= 0
                || currency == null || !currency.trim().matches("[A-Za-z]{3}")) {
            throw new ConflictException("Delivery request, payer, positive amount and ISO currency are required");
        }
        String normalizedCurrency = currency.trim().toUpperCase(java.util.Locale.ROOT);
        String key = "delivery-" + deliveryRequestId;
        var existing = paymentIntentRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            PaymentIntent intent = existing.get();
            if (!requesterId.equals(intent.getConsumerId()) || !deliveryRequestId.equals(intent.getDeliveryRequestId())
                    || !PaymentIntent.ORIGIN_DELIVERY.equals(intent.getOrigin())
                    || intent.getAmountCents() != amountMinor || !normalizedCurrency.equals(intent.getCurrency())) {
                throw new ConflictException("Delivery payment idempotency key conflicts with the authoritative payable");
            }
            return intent;
        }
        PaymentIntent saved = paymentIntentRepository.save(PaymentIntent.createForDelivery(
                deliveryRequestId, requesterId, amountMinor, normalizedCurrency, key));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), saved.getId()));
        return saved;
    }

    /**
     * Stage 8 (ADR-0004): the loan-fee intent — the order-checkout twin
     * verbatim (the deterministic {@code loan-{loanId}} key is the first
     * line of defense, the V200 loan_id UNIQUE constraint the backstop).
     */
    public PaymentIntent createLoanIntent(UUID loanId, UUID borrowerId,
                                          long amountCents, String currency) {
        String idempotencyKey = "loan-" + loanId;
        // The part-1 review note (the order path's twin): normalize ONCE,
        // before the lookup — a retry arriving as "sar" (or null) must meet
        // the stored "SAR" as the SAME payable, never a 409; the entity
        // factory's own normalization/default is the authority, applied
        // here so the replay comparison and the creation both see the one
        // form.
        String normalizedCurrency = Currencies.normalizeOrDefault(currency, Currencies.DEFAULT_CODE);
        var existing = paymentIntentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PaymentIntent found = existing.get();
            if (!found.getConsumerId().equals(borrowerId)) {
                throw new AccessDeniedException("Idempotency key belongs to another payer");
            }
            // CodeRabbit agent findings, row 17: the deterministic loan key
            // must return THE loan's own payable — a same-borrower booking
            // intent that pre-occupied the key must never satisfy the loan's
            // paid gate. The complete binding check (origin, subject,
            // amount, currency) mirrors the delivery settlement's own
            // authoritative-payable verification; the honest refusal on any
            // mismatch, never a silent link of unrelated money.
            if (!PaymentIntent.ORIGIN_LOAN.equals(found.getOrigin())
                    || !loanId.equals(found.getLoanId())
                    || found.getAmountCents() == null || found.getAmountCents() != amountCents
                    || found.getCurrency() == null || !found.getCurrency().equals(normalizedCurrency)) {
                throw new ConflictException("Idempotency key '" + idempotencyKey
                        + "' is held by a non-matching payment intent (" + found.getId()
                        + ", origin=" + found.getOrigin() + ") — the loan's payable"
                        + " cannot be satisfied by it");
            }
            return found;
        }
        if (amountCents <= 0) {
            throw new ConflictException("A loan intent requires a positive amount: " + amountCents + " cents");
        }
        PaymentIntent intent = PaymentIntent.createForLoan(loanId, borrowerId,
                amountCents, normalizedCurrency, idempotencyKey, commissionProperties.rate());
        PaymentIntent saved = paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(saved.getId(), "INITIATED"));
        return saved;
    }

    /**
     * The server-generated payable-key namespace (CodeRabbit agent findings,
     * rows 17/18): the order, loan, delivery, and ad-bill checkout paths
     * DERIVE their idempotency keys from the payable's identity
     * ({@code order-}/{@code loan-}/{@code delivery-}/{@code ad-debit-}
     * prefixes), while the booking path lets the CALLER choose the key. A
     * caller-selected key that pre-occupies a derived namespace could make
     * an unrelated cheaper intent satisfy that payable's payment gate, so
     * the booking surface refuses the reserved prefixes outright — the
     * structural isolation layer; the complete origin+payable verification
     * on each derived key's reuse path is the defense in depth.
     */
    private static boolean isServerPayableKey(String key) {
        return key.startsWith("order-") || key.startsWith("loan-")
                || key.startsWith("delivery-") || key.startsWith("ad-debit-");
    }

    /**
     * Result of processing a payment intent: the local intent plus the PSP
     * client secret when the real channel is bound (the calling client needs
     * it to complete the payment on the provider side). Null clientSecret =
     * channel inert, existing behavior.
     */
    public record ProcessIntentResult(PaymentIntent intent, String clientSecret) {}

    @Observed(name = "payment.process")
    @PreAuthorize("hasRole('CONSUMER')")
    @Retry(name = "paymentProcessing")
    @CircuitBreaker(name = "paymentProcessing")
    @ConcurrencyLimit(5)
    public ProcessIntentResult processIntent(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntentForUser(id, authentication);
        intent.markProcessing();
        // Real channel (roadmap B3): create the remote intent when the PSP is
        // bound. The idempotency key is derived from the local intent id, so
        // retries replay the SAME remote intent (official idempotency-key
        // contract) instead of double-charging the flow.
        PspChannel channel = pspChannel.getIfAvailable();
        String clientSecret = null;
        if (channel != null) {
            var remote = channel.createRemoteIntent(
                    intent.getId(), intent.getAmountCents(), intent.getCurrency(),
                    "marketplace-intent-" + intent.getId());
            intent.assignPspIntentId(remote.pspIntentId());
            clientSecret = remote.clientSecret();
        }
        Payment payment = Payment.create(intent.getId(), intent.getAmountCents());
        paymentRepository.save(payment);
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), id));
        return new ProcessIntentResult(intent, clientSecret);
    }

    /**
     * Admin command shell (POST /intents/{id}/confirm): the role check
     * lives HERE — on the command surface — while the domain transition
     * lives in {@link PaymentIntentSettlementService} (N2: the pre-fix
     * self-invocation from the webhook dispatch bypassed this proxy, so
     * the {@code @PreAuthorize} below read as a boundary that one path
     * silently went around). The webhook reaches the same domain
     * transition with provider-signature authorization instead.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public PaymentIntent confirmIntent(UUID id, String externalId) {
        return paymentIntentSettlementService.confirm(id, externalId);
    }

    @Observed(name = "payment.cancel")
    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntent cancelIntent(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntentForUser(id, authentication);
        intent.cancel();
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), id));
        return intent;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public RefundedPayment refundPayment(UUID paymentId) {
        return refundPayment(paymentId, null, null);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public RefundedPayment refundPayment(UUID paymentId, @Min(1) Long amountCents) {
        return refundPayment(paymentId, amountCents, null);
    }

    /**
     * S7 carrier (the {@link ProcessIntentResult} shape): the refunded
     * payment together with its intent — the response layer needs the
     * intent's ISO 4217 currency to answer a complete money shape, and the
     * refund flow already loads both in one transaction. Domain consumers
     * (the dispute refund adapter) read {@link #payment()}.
     */
    public record RefundedPayment(Payment payment, PaymentIntent intent) {}

    /**
     * A stable refund-operation scope — the idempotency key's operation
     * identity when a domain's refund MUST replay identically across a
     * rollback-then-retry window.
     *
     * <p><b>V230's return edge (amount-bound):</b> a completing RETURN
     * passes its own id; its key carries the return's own fixed amount —
     * one return, one remote refund, replayed safely on retry (the
     * state-derived key drifts when a sibling return advances the
     * cumulative total between a rollback and its retry).</p>
     *
     * <p><b>The dispute twin (agent findings 2026-10-26, row 35 —
     * FROZEN):</b> a booking dispute's refund passes the DISPUTE id, and
     * its key carries NO amount: one dispute is ONE refund operation, the
     * approved amount freezes at first execution, and every retry
     * reconciles against the operation itself — the PSP replays the
     * already-executed refund instead of submitting a new one under a new
     * key. The pre-fix path kept the state-derived key (payment +
     * refunded-so-far + amount), so after a rollback the webhook's
     * cumulative sync changed the refunded-so-far leg, the retry derived a
     * NEW key, and the same partial refund was paid at the provider
     * TWICE. The pre-channel over-refund guards stay exactly where they
     * are — the honest money-safety ceiling the V230 return edge accepts
     * verbatim.</p>
     */
    public record StableRefundScope(String kind, UUID operationId) {

        /** V230's return edge: the key carries the return's own amount. */
        public static StableRefundScope forReturn(UUID returnId) {
            return new StableRefundScope("return", returnId);
        }

        /** Row 35's dispute twin: the key freezes the operation, amount-less. */
        public static StableRefundScope forDispute(UUID disputeId) {
            return new StableRefundScope("dispute", disputeId);
        }

        /**
         * Row 15's abandonment twin (agent findings 2026-10-26T11:43
         * export): the operation is the ABANDONED INTENT itself, always a
         * full-capture refund, amount-less — one abandonment, one remote
         * refund, forever, across every registry resubmission.
         */
        public static StableRefundScope forAbandonment(UUID paymentIntentId) {
            return new StableRefundScope("abandonment", paymentIntentId);
        }

        String idempotencyKey(UUID paymentId, Long amountCents) {
            if ("return".equals(kind)) {
                return returnScopedRefundIdempotencyKey(paymentId, operationId, amountCents);
            }
            if ("abandonment".equals(kind)) {
                return abandonmentScopedRefundIdempotencyKey(paymentId, operationId);
            }
            return disputeScopedRefundIdempotencyKey(paymentId, operationId);
        }
    }

    /**
     * The V230 return-scoped refund edge (the review's Major finding): a
     * completing RETURN passes its own id as the idempotency key's STABLE
     * scope. The state-derived key (payment + refunded-so-far + amount)
     * drifts when a sibling return's refund advances the cumulative total
     * between a rollback and its retry — the retry then derives a NEW key
     * and the PSP refunds the same return twice. The return-scoped key is
     * stable across that window: one return, one remote refund, replayed
     * safely on retry. The full-refund path keeps the state-derived key
     * (null scope) — its own scope is single-shot.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public RefundedPayment refundPayment(UUID paymentId, @Min(1) Long amountCents,
                                         StableRefundScope idempotencyScope) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        PaymentIntent intent = paymentIntentRepository.findById(payment.getPaymentIntentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + payment.getPaymentIntentId()));

        long alreadyRefunded = payment.getRefundedAmountCents();
        if (amountCents != null) {
            if (alreadyRefunded + amountCents > payment.getAmountCents()) {
                throw new ConflictException("Refund amount exceeds payment amount");
            }
            if (intent.getRefundedAmountCents() + amountCents > intent.getAmountCents()) {
                throw new ConflictException("Refund amount exceeds intent amount");
            }
        }
        // L19/R1 — the closed money loop through the ONE refund contract: when
        // the real channel is bound AND the intent is linked to a remote
        // intent, the refund is created at the provider with a derived
        // idempotency key and the local books reflect the remote cumulative
        // actual. Not linked = the intent never left the house
        // (internal/test money) — nothing remote to refund; no channel = the
        // documented inert path. Either way the existing internal flow below
        // stays byte-compatible. The booking-cancellation listener's full
        // refund (refundFully) routes through the SAME contract — one
        // behavior for one financial operation, no dual paths.
        RemoteRefundOutcome outcome = refundThroughChannel(payment, intent, amountCents, idempotencyScope);
        switch (outcome) {
            case PENDING_AT_PROVIDER -> {
                // Remote refund created but not completed: local books await
                // the charge.refunded webhook (the async safety net).
                return new RefundedPayment(payment, intent);
            }
            case SYNCED -> {
                paymentIntentRepository.save(intent);
                eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), intent.getStatus().name()));
                eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), intent.getId()));
                return new RefundedPayment(payment, intent);
            }
            case NO_CHANNEL -> {
                // fall through to the documented inert local path below
            }
        }
        boolean isFullRefund = (amountCents == null || alreadyRefunded + amountCents == payment.getAmountCents());
        if (isFullRefund) {
            payment.markRefunded();
            intent.markRefunded();
        } else {
            payment.markPartiallyRefunded(amountCents);
            intent.markPartiallyRefunded(amountCents);
        }
        paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), intent.getStatus().name()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), intent.getId()));
        return new RefundedPayment(payment, intent);
    }

    /**
     * Applies the remote cumulative actual to the local rows: full when the
     * provider says everything is refunded, partial otherwise. The remote
     * number is authoritative (roadmap L19 acceptance 3) — the local books
     * follow the money at the PSP even when refunds happened outside this
     * service.
     */
    private void applyRemoteRefund(Payment payment, PaymentIntent intent, long remoteRefundedTotalCents) {
        if (remoteRefundedTotalCents >= payment.getAmountCents()) {
            payment.markRefunded();
            intent.markRefunded();
        } else {
            payment.markPartiallyRefundedTotal(remoteRefundedTotalCents);
            intent.markPartiallyRefundedTotal(remoteRefundedTotalCents);
        }
    }

    /**
     * Deterministic refund replay key, derived from the request as the local
     * books see it: the payment, the refunded-so-far state, and the requested
     * amount (or "full"). A retried request (after rollback) re-derives the
     * SAME key and replays the same remote refund instead of double-charging;
     * a NEW refund (state advanced) derives a different key and creates a new
     * remote refund — the official idempotency-key contract.
     */
    static String refundIdempotencyKey(UUID paymentId, long alreadyRefundedCents, Long amountCents) {
        String amount = amountCents == null ? "full" : amountCents.toString();
        return "marketplace-refund-" + paymentId + "-" + alreadyRefundedCents + "-" + amount;
    }

    /**
     * The V230 return-scoped key (the review's Major finding): derived from
     * the payment, the RETURN's id, and the frozen amount — deliberately
     * NOT from the refunded-so-far state. One completing return owns exactly
     * one remote refund: its retried completion (after a rollback whose
     * sibling refund advanced the cumulative total) re-derives the SAME key
     * and replays the same refund at the PSP instead of creating a second
     * one — the official idempotency-key contract, scoped to the RMA edge.
     */
    static String returnScopedRefundIdempotencyKey(UUID paymentId, UUID returnId, Long amountCents) {
        String amount = amountCents == null ? "full" : amountCents.toString();
        return "marketplace-refund-" + paymentId + "-return-" + returnId + "-" + amount;
    }

    /**
     * Row 35 (agent findings 2026-10-26): the dispute refund's FROZEN
     * operation key — one dispute, one remote refund, forever. The key
     * carries NO amount and NO refunded-so-far state: the approved amount
     * freezes at the first execution and every retry (the same decision
     * or a re-approved one) replays the SAME remote refund through the
     * PSP's idempotency contract — the executed refund is reused, never
     * re-submitted under a new key. The rollback window that broke the
     * state-derived key: refund X executes at the PSP inside a resolve
     * transaction that rolls back, the charge.refunded webhook syncs the
     * local books to X, and the retry's state-derived key would have
     * carried the advanced cumulative leg and paid X a second time.
     */
    static String disputeScopedRefundIdempotencyKey(UUID paymentId, UUID disputeId) {
        return "marketplace-refund-" + paymentId + "-dispute-" + disputeId;
    }

    /**
     * Row 15 (agent findings 2026-10-26T11:43 export): the abandonment
     * refund's FROZEN operation key — one abandoned intent, one remote
     * refund, forever, always the full captured amount. The amount-less
     * shape is the dispute twin's law: the operation freezes at first
     * execution and every registry resubmission (worker crash, event
     * redelivery, operator retry) replays the SAME remote refund through
     * the PSP's idempotency contract — the buyer is paid back exactly once
     * no matter how often the recovery re-runs.
     */
    static String abandonmentScopedRefundIdempotencyKey(UUID paymentId, UUID paymentIntentId) {
        return "marketplace-refund-" + paymentId + "-abandonment-" + paymentIntentId;
    }

    /**
     * L19 — webhook-side refund sync ({@code charge.refunded}): sets the local
     * books to the remote cumulative actual. Idempotent: a redelivery that
     * carries no new state is a debug no-op instead of a state-machine
     * violation, so the provider's retry loop never traps on a synced event
     * (the dedup table covers identical event ids; this covers the same
     * SNAPSHOT arriving under a different event id).
     */
    void syncRemoteRefund(UUID paymentIntentId, long remoteRefundedTotalCents) {
        PaymentIntent intent = getIntent(paymentIntentId);
        Payment payment = paymentRepository.findByPaymentIntentId(paymentIntentId).orElse(null);
        if (payment == null) {
            log.warn("charge.refunded for intent {} without a local payment row — nothing to sync",
                    paymentIntentId);
            return;
        }
        // CodeRabbit #249 round 1: Stripe does not guarantee webhook delivery
        // order (official docs), and amount_refunded only grows — a snapshot
        // BELOW the synced local totals is a stale, out-of-order event and is
        // ignored instead of regressing the books.
        if (remoteRefundedTotalCents < payment.getRefundedAmountCents()
                || remoteRefundedTotalCents < intent.getRefundedAmountCents()) {
            log.info("charge.refunded for intent {} carries an out-of-order snapshot ({} cents) below"
                            + " the synced totals (payment {} / intent {}) — ignored",
                    paymentIntentId, remoteRefundedTotalCents,
                    payment.getRefundedAmountCents(), intent.getRefundedAmountCents());
            return;
        }
        boolean fullRemote = remoteRefundedTotalCents >= payment.getAmountCents();
        boolean alreadySynced = payment.getRefundedAmountCents() == remoteRefundedTotalCents
                && intent.getRefundedAmountCents() == remoteRefundedTotalCents
                && payment.getStatus() == (fullRemote ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        if (alreadySynced) {
            log.debug("charge.refunded for intent {} carries the already-synced total {} cents",
                    paymentIntentId, remoteRefundedTotalCents);
            return;
        }
        applyRemoteRefund(payment, intent, remoteRefundedTotalCents);
        paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), intent.getStatus().name()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), intent.getId()));
        log.info("Synced remote refund state for intent {}: {} cents refunded (status {})",
                paymentIntentId, remoteRefundedTotalCents, payment.getStatus());
    }

    /**
     * S12 guard — booking cancellation's money half maps the intent's ACTUAL
     * state through {@link PaymentIntentStatus#TRANSITIONS} to its legal
     * outcome instead of assuming SUCCEEDED. Spring Modulith's Event
     * Publication Registry retries a listener that throws (official
     * reference: "In case the listener fails, the log entry stays untouched
     * so that retry mechanisms can be deployed"), so an illegal-transition
     * throw here (the pre-fix behavior: unconditional {@code markRefunded()})
     * was resubmitted daily forever with the intent stuck. Every state now
     * either transitions legally or completes as an idempotent no-op:
     * <ul>
     *   <li>SUCCEEDED / PARTIALLY_REFUNDED → REFUNDED — the only states where
     *       money moved, so the full refund (and its ledger debit event)
     *       applies;</li>
     *   <li>CREATED → CANCELLED — the booking died before any charge
     *       existed, so nothing is owed back;</li>
     *   <li>PROCESSING → FAILED — the in-flight charge is abandoned (the
     *       local books assume it never settles; a late PSP success hitting
     *       a terminal intent is the webhook side's declared gap, closed
     *       separately);</li>
     *   <li>FAILED / CANCELLED / REFUNDED → no-op — a redelivery must
     *       complete the publication instead of re-violating the machine.</li>
     * </ul>
     *
     * <p><b>R4 scoping (comprehensive-review-ar-fix plan §4/R4):</b> the
     * intent is resolved through the two STATUS-SCOPED deterministic
     * searches — the collectible attempt and the money-carrying attempt,
     * each latest by {@code (createdAt, id)} — never the pre-fix
     * unfiltered per-booking lookup that returned an arbitrary row once
     * several intents coexisted for one booking.</p>
 *
     * <p><b>R1 (Wave 2) — the money-remote half of the SUCCEEDED branch.</b>
     * The full refund of a remotely-charged intent now routes through the one
     * remote-refund contract ({@link #refundThroughChannel}): a PENDING remote
     * refund completes the listener cleanly (the {@code charge.refunded}
     * webhook finishes the books), while a CHANNEL FAILURE throws on purpose
     * — the local books must never say REFUNDED for money the provider has
     * not returned. That throw is not a state-machine violation (the S12
     * family above stays legal-or-noop); it is the honest signal that keeps
     * the publication incomplete, so the framework's own recovery owns the
     * retry: the {@code @Retry(name = "paymentProcessing")} wrapper first,
     * then the Event Publication Registry, then the runtime
     * {@code EventPublicationResubmission} sweep — the refund stays pending
     * and retryable until the channel heals, and the intent keeps its true
     * money state throughout.</p>     */
    @Retry(name = "paymentProcessing")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoRefundByBooking(UUID bookingId) {
        // R4 (comprehensive-review-ar-fix plan §4/R4): the booking's money
        // half is STATUS-SCOPED, never an unfiltered per-booking search —
        // the pre-fix findByBookingId returned an arbitrary row once the
        // defect let several intents coexist. Two scoped deterministic
        // searches (latest by (createdAt, id)) carry the S12 contract:
        // the COLLECTIBLE attempt (if any) is aborted where it stands,
        // and the COLLECTED attempt (if any) is refunded. Under the R4
        // invariant the two are mutually exclusive per booking; the
        // scoped searches stay individually idempotent for redeliveries.
        paymentIntentRepository
                .findFirstByBookingIdAndStatusInOrderByCreatedAtDescIdDesc(
                        bookingId, PaymentIntentStatus.COLLECTIBLE)
                .ifPresent(intent -> {
                    switch (intent.getStatus()) {
                        case CREATED -> cancelUnpaid(intent);
                        case PROCESSING -> failInFlight(intent);
                        default ->
                                log.info("Auto-refund for booking {} skipped — collectible intent {}"
                                                + " already left the collectible states in {}",
                                        bookingId, intent.getId(), intent.getStatus());
                    }
                });
        paymentIntentRepository
                .findFirstByBookingIdAndStatusInOrderByCreatedAtDescIdDesc(
                        bookingId, PaymentIntentStatus.COLLECTED)
                .ifPresent(intent -> {
                    switch (intent.getStatus()) {
                        case SUCCEEDED, PARTIALLY_REFUNDED -> refundFully(intent);
                        case REFUNDED ->
                                log.info("Auto-refund for booking {} skipped — intent {} already"
                                                + " terminal in REFUNDED",
                                        bookingId, intent.getId());
                        default ->
                                log.info("Auto-refund for booking {} skipped — intent {} already terminal in {}",
                                        bookingId, intent.getId(), intent.getStatus());
                    }
                });
    }

    /**
     * Stage 6 (ADR-0002): the ORDER cancellation's money half — the
     * {@code autoRefundByBooking} twin verbatim, keyed by the order's ONE
     * intent (the V184 order_id UNIQUE constraint makes the single lookup the
     * whole truth): the collectible attempt is aborted where it stands,
     * the collected attempt is refunded in full — through the ONE refund
     * contract, no second refund path.
     */
    @Retry(name = "paymentProcessing")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoRefundByLoan(UUID loanId) {
        paymentIntentRepository.findByLoanId(loanId).ifPresent(intent -> {
            switch (intent.getStatus()) {
                case CREATED -> cancelUnpaid(intent);
                case PROCESSING -> failInFlight(intent);
                case SUCCEEDED, PARTIALLY_REFUNDED -> refundFully(intent);
                case REFUNDED ->
                        log.info("Auto-refund for loan {} skipped — intent {} already terminal in REFUNDED",
                                loanId, intent.getId());
                default ->
                        log.info("Auto-refund for loan {} skipped — intent {} already terminal in {}",
                                loanId, intent.getId(), intent.getStatus());
            }
        });
    }

    @Retry(name = "paymentProcessing")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoRefundByOrder(UUID orderId) {
        paymentIntentRepository.findByOrderId(orderId).ifPresent(intent -> {
            switch (intent.getStatus()) {
                case CREATED -> cancelUnpaid(intent);
                case PROCESSING -> failInFlight(intent);
                case SUCCEEDED, PARTIALLY_REFUNDED -> refundFully(intent);
                case REFUNDED -> log.info("Auto-refund for order {} skipped — intent {} already REFUNDED", orderId, intent.getId());
                default -> log.info("Auto-refund for order {} skipped — intent {} terminal in {}", orderId, intent.getId(), intent.getStatus());
            }
        });
    }

    @Observed(name = "payment.delivery.refund")
    @Retry(name = "paymentProcessing")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoRefundByDelivery(UUID deliveryRequestId) {
        paymentIntentRepository.findByDeliveryRequestId(deliveryRequestId).ifPresent(intent -> {
            switch (intent.getStatus()) {
                case CREATED -> cancelUnpaid(intent);
                case PROCESSING -> failInFlight(intent);
                case SUCCEEDED, PARTIALLY_REFUNDED -> refundFully(intent);
                case REFUNDED -> log.info("Delivery {} intent {} already REFUNDED", deliveryRequestId, intent.getId());
                default -> log.info("Delivery {} intent {} terminal in {}", deliveryRequestId, intent.getId(), intent.getStatus());
            }
        });
    }

    /**
     * R1 (Wave 2) — the ONE remote-refund contract every refund caller routes
     * through (the admin command and the booking-cancellation listener's full
     * refund — the measured defect was two behaviors for one financial
     * operation: the admin path created the remote refund while the listener
     * marked the local books REFUNDED without the provider ever paying anyone
     * back). The contract:
     * <ul>
     *   <li>intent not linked to a remote intent, or no channel bound ⇒
     *       {@link RemoteRefundOutcome#NO_CHANNEL} — the documented inert
     *       local path applies (internal/test money);</li>
     *   <li>remote refund created and PENDING ⇒
     *       {@link RemoteRefundOutcome#PENDING_AT_PROVIDER} — the local books
     *       await the {@code charge.refunded} webhook (the async safety net);
     *       a channel exception propagates to the caller's existing
     *       {@code @Retry(name = "paymentProcessing")} wrapper and, on the
     *       listener path, to Spring Modulith's Event Publication Registry —
     *       the refund stays pending and retryable, never falsely REFUNDED
     *       (the framework's own retry mechanisms own the recovery, including
     *       {@code EventPublicationResubmission} at runtime);</li>
     *   <li>remote refund SUCCEEDED ⇒
     *       {@link RemoteRefundOutcome#SYNCED} — the remote cumulative actual
     *       is applied to both rows (the provider's number is authoritative,
     *       roadmap L19 acceptance 3) and the caller persists + publishes.</li>
     * </ul>
     * Anything else from the provider is a loud conflict with no local state
     * change (CodeRabbit #249 round 1).
     */
    private RemoteRefundOutcome refundThroughChannel(Payment payment, PaymentIntent intent, Long amountCents,
                                                    StableRefundScope idempotencyScope) {
        PspChannel channel = pspChannel.getIfAvailable();
        if (channel == null || intent.getPspIntentId() == null) {
            return RemoteRefundOutcome.NO_CHANNEL;
        }
        String idempotencyKey = idempotencyScope != null
                ? idempotencyScope.idempotencyKey(payment.getId(), amountCents)
                : refundIdempotencyKey(
                        payment.getId(), payment.getRefundedAmountCents(), amountCents);
        PspChannel.RemoteRefund remote = channel.createRemoteRefund(
                intent.getPspIntentId(), amountCents, idempotencyKey);
        if ("pending".equals(remote.status())) {
            log.info("Remote refund {} for payment {} is pending at the provider — local books"
                            + " await the charge.refunded webhook (nothing refunded yet: {} cents)",
                    remote.refundId(), payment.getId(), remote.refundedTotalCents());
            return RemoteRefundOutcome.PENDING_AT_PROVIDER;
        }
        if (!"succeeded".equals(remote.status())) {
            throw new ConflictException("Remote refund " + remote.refundId() + " for payment "
                    + payment.getId() + " returned status '" + remote.status()
                    + "' — no local refund state changed");
        }
        applyRemoteRefund(payment, intent, remote.refundedTotalCents());
        return RemoteRefundOutcome.SYNCED;
    }

    /** The one contract's outcomes — see {@link #refundThroughChannel}. */
    private enum RemoteRefundOutcome {
        NO_CHANNEL,
        PENDING_AT_PROVIDER,
        SYNCED
    }

    private void refundFully(PaymentIntent intent) {
        // the auto-refund listeners' full refund: single-shot per listener
        // delivery (the registry's resubmission carries the SAME event), the
        // state-derived key is the documented shape for these edges
        refundFully(intent, null);
    }

    /**
     * Row 15's carrier: the same ONE full-refund contract with a STABLE
     * operation scope when the refund is an OPERATION that must replay
     * identically across every recovery resubmission (the abandonment's
     * own refund — one abandoned intent, one remote refund, forever).
     */
    private void refundFully(PaymentIntent intent, StableRefundScope idempotencyScope) {
        Payment payment = paymentRepository.findByPaymentIntentId(intent.getId()).orElse(null);
        if (payment == null) {
            // Never charged through settlement — nothing remote to coordinate;
            // the intent-level terminal mark is the honest state.
            intent.markRefunded();
            paymentIntentRepository.save(intent);
            eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "REFUNDED"));
            eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), intent.getId()));
            log.info("Auto-refund completed for intent {} (full refund — no payment row to coordinate)",
                    intent.getId());
            return;
        }
        RemoteRefundOutcome outcome = refundThroughChannel(payment, intent, null, idempotencyScope);
        switch (outcome) {
            case SYNCED -> {
                paymentIntentRepository.save(intent);
                eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), intent.getStatus().name()));
                eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), intent.getId()));
                log.info("Auto-refund completed for intent {} — remote cumulative applied",
                        intent.getId());
            }
            case PENDING_AT_PROVIDER -> log.info(
                    "Auto-refund for intent {} created a PENDING remote refund — local books await the"
                            + " charge.refunded webhook (the async safety net); the listener completes cleanly",
                    intent.getId());
            case NO_CHANNEL -> {
                // The documented inert local path — byte-compatible with the
                // pre-R1 behavior for money that never left the house.
                intent.markRefunded();
                payment.markRefunded();
                paymentIntentRepository.save(intent);
                paymentRepository.save(payment);
                eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "REFUNDED"));
                eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), intent.getId()));
                log.info("Auto-refund completed for intent {} (full refund — inert local path)", intent.getId());
            }
        }
    }

    private void cancelUnpaid(PaymentIntent intent) {
        intent.cancel();
        paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "CANCELLED"));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), intent.getId()));
        log.info("Auto-refund resolved intent {} as CANCELLED — the booking died before any charge existed",
                intent.getId());
    }

    /**
     * Row 15 (agent findings 2026-10-26T11:43 export) — the abandonment's
     * own record, NOT a terminal failure: a domain cancellation hit an
     * in-flight payment and decided "this money returns to the buyer";
     * the remote capture may still win the race. The intent lands
     * CANCELLATION_PENDING (the payment row stays PENDING — the remote
     * outcome owns its terminal state), a NEW attempt stays blocked until
     * the race resolves, and the provider's own events close the loop:
     * payment_intent.succeeded settles the books and pays the abandonment's
     * ONE durable idempotent refund back ({@link #refundAbandonedCapture});
     * payment_intent.payment_failed / canceled / expired resolve it to
     * FAILED (no money moved — the settlement's fail edge and
     * {@link #resolveAbandonment} respectively).
     *
     * <p>The pre-fix shape terminally FAILED the local pair with no remote
     * coordination: a capture that won the race answered the settlement's
     * state guard with ConflictException forever (the provider's own retry
     * loop re-delivered the lost transition), and the buyer's captured
     * money never settled locally nor returned — W7/G7's law stays intact
     * for ORGANIC failures; the abandonment is a DIFFERENT fact with its
     * own state and its own recovery.</p>
     */
    private void failInFlight(PaymentIntent intent) {
        intent.markCancellationPending();
        paymentIntentRepository.save(intent);
        // the payment row keeps PENDING — its terminal state follows the
        // remote truth (succeeded -> COMPLETED via the settlement; failed/
        // canceled/expired -> FAILED via the same edges)
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "CANCELLATION_PENDING"));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), intent.getId()));
        log.info("Auto-refund recorded intent {} as CANCELLATION_PENDING — the in-flight charge was"
                + " abandoned at cancellation; the remote race (capture vs. its own failure) resolves it",
                intent.getId());
    }

    /**
     * Row 15 — the abandonment's recovery edge: a capture that WON the race
     * settled the books through the settlement ({@code confirm} published
     * {@link PaymentAbandonmentCapturedEvent} after committing SUCCEEDED),
     * and the cancellation's own decision — "this money returns to the
     * buyer" — executes here through the ONE refund contract. DURABLE (the
     * Modulith Event Publication Registry resubmits after a crash) and
     * IDEMPOTENT three ways: the REFUNDED no-op read, the
     * abandonment-scoped PSP idempotency key (every resubmission replays
     * the SAME remote refund), and the full-refund amount that never
     * depends on refunded-so-far state.
     */
    @Observed(name = "payment.abandonment.refund")
    @Retry(name = "paymentProcessing")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refundAbandonedCapture(UUID paymentIntentId) {
        PaymentIntent intent = paymentIntentRepository.findById(paymentIntentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + paymentIntentId));
        if (intent.getStatus() == PaymentIntentStatus.REFUNDED
                || intent.getStatus() == PaymentIntentStatus.PARTIALLY_REFUNDED) {
            // the idempotent no-op read: the abandonment's refund already
            // executed (a registry resubmission, an operator's manual
            // refund, or the webhook sync raced ahead) — replaying is a
            // read, never a second payment
            log.info("Abandonment refund for intent {} is already {} — the resubmission is a no-op read",
                    paymentIntentId, intent.getStatus());
            return;
        }
        refundFully(intent, StableRefundScope.forAbandonment(intent.getId()));
    }

    private PaymentSummary toPaymentSummary(PaymentIntent paymentIntent) {
        return new PaymentSummary(
                paymentIntent.getId(),
                paymentIntent.getBookingId(),
                paymentIntent.getAdCampaignId(),
                paymentIntent.getOrderId(),
                paymentIntent.getLoanId(),
                paymentIntent.getConsumerId(),
                paymentIntent.getAmountCents(),
                paymentIntent.getCurrency(),
                paymentIntent.getOrigin(),
                paymentIntent.getStatus().name(),
                paymentIntent.getRefundedAmountCents(),
                paymentIntent.getCommissionRate(),
                paymentIntent.getPspIntentId(),
                paymentIntent.getCreatedAt(),
                paymentIntent.getUpdatedAt()
        );
    }

    private void verifyConsumerOwnership(PaymentIntent intent, Authentication authentication) {
        if (currentUserProvider.isAdmin(authentication)) {
            return;
        }
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!intent.getConsumerId().equals(currentUserId)) {
            throw new AccessDeniedException("You do not own this payment intent");
        }
    }

    private PspChannel requireChannel() {
        PspChannel channel = pspChannel.getIfAvailable();
        if (channel == null) {
            throw new ServiceUnavailableException(
                    "Real payment channel is not configured. Set PAYMENTS_STRIPE_API_KEY and "
                            + "PAYMENTS_STRIPE_WEBHOOK_SECRET to enable the PSP channel.");
        }
        return channel;
    }
}
