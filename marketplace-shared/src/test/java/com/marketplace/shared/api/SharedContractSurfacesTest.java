package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The shared contracts' remaining value surfaces — the one place the
 * module's own taxonomy vocabulary, its problem-detail factory, and the
 * cross-module records that predate dedicated pin classes answer for
 * their value semantics (the LedgerAllocationContractsTest /
 * DeliveryContractsTest pin family, extended to the surfaces the
 * coverage gate measured as the bundle's untested tail).
 */
class SharedContractSurfacesTest {

    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-22T12:00:00Z");

    // ------------------------------------------------------------------
    // ApiErrorTaxonomy — every enum constant's own constructor facts
    // ------------------------------------------------------------------

    @Test
    void theTaxonomyVocabularyCarriesItsOwnMachineContract() {
        assertThat(ApiErrorTaxonomy.VALIDATION.errorCode()).isEqualTo("VAL-001");
        assertThat(ApiErrorTaxonomy.VALIDATION.category()).isEqualTo("validation");
        assertThat(ApiErrorTaxonomy.VALIDATION.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ApiErrorTaxonomy.VALIDATION.title()).isEqualTo("Bad Request");
        assertThat(ApiErrorTaxonomy.VALIDATION.typeUri())
                .isEqualTo("https://marketplace.com/errors/validation");

        assertThat(ApiErrorTaxonomy.AUTHN.errorCode()).isEqualTo("AUTHN-001");
        assertThat(ApiErrorTaxonomy.AUTHN.category()).isEqualTo("authz");
        assertThat(ApiErrorTaxonomy.AUTHN.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ApiErrorTaxonomy.AUTHN.typeUri())
                .isEqualTo("https://marketplace.com/errors/unauthorized");

        assertThat(ApiErrorTaxonomy.AUTHZ.errorCode()).isEqualTo("AUTHZ-001");
        assertThat(ApiErrorTaxonomy.AUTHZ.statusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(ApiErrorTaxonomy.NOT_FOUND.errorCode()).isEqualTo("NF-001");
        assertThat(ApiErrorTaxonomy.NOT_FOUND.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(ApiErrorTaxonomy.CONFLICT.errorCode()).isEqualTo("CONFLICT-001");
        assertThat(ApiErrorTaxonomy.CONFLICT.statusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(ApiErrorTaxonomy.RATE_LIMIT.errorCode()).isEqualTo("RL-001");
        assertThat(ApiErrorTaxonomy.RATE_LIMIT.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(ApiErrorTaxonomy.SERVICE_UNAVAILABLE.errorCode()).isEqualTo("SU-001");
        assertThat(ApiErrorTaxonomy.SERVICE_UNAVAILABLE.category()).isEqualTo("availability");
        assertThat(ApiErrorTaxonomy.SERVICE_UNAVAILABLE.statusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(ApiErrorTaxonomy.INTERNAL.errorCode()).isEqualTo("INT-001");
        assertThat(ApiErrorTaxonomy.INTERNAL.category()).isEqualTo("internal");
        assertThat(ApiErrorTaxonomy.INTERNAL.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // The machine vocabulary is closed: exactly the eight documented
        // taxonomy rows (every constant constructed and pinned above).
        assertThat(ApiErrorTaxonomy.values()).hasSize(8);
    }

    // ------------------------------------------------------------------
    // ApiProblemDetails — the taxonomy-to-RFC-9457 factory
    // ------------------------------------------------------------------

    @Test
    void theProblemFactoryCarriesTheTaxonomyIntoTheRfc9457Shape() {
        ProblemDetail problem = ApiProblemDetails.fromTaxonomy(
                ApiErrorTaxonomy.VALIDATION, "detail text", "/api/v1/under-test",
                "user message", "trace-42");

        assertThat(problem.getTitle()).isEqualTo("Bad Request");
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("detail text");
        assertThat(problem.getInstance().toString()).isEqualTo("/api/v1/under-test");
        assertThat(problem.getProperties())
                .containsEntry("userMessage", "user message")
                .containsEntry("traceId", "trace-42")
                .containsEntry("errorCode", "VAL-001");
    }

    @Test
    void theProblemFactoryAnswersTheHonestNullsWhenOptionalFactsAreAbsent() {
        ProblemDetail problem = ApiProblemDetails.fromTaxonomy(
                ApiErrorTaxonomy.NOT_FOUND, null, "/api/v1/under-test", null, null);
        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).isNull();
        assertThat(problem.getProperties()).doesNotContainKey("userMessage");
        assertThat(problem.getProperties()).containsEntry("errorCode", "NF-001");
    }

    // ------------------------------------------------------------------
    // PaymentSummary — the any-origin investigation read model
    // ------------------------------------------------------------------

    @Test
    void thePaymentSummaryCarriesEveryOriginSubjectField() {
        UUID bookingId = UUID.randomUUID();
        PaymentSummary summary = new PaymentSummary(id, bookingId, null, null, null,
                UUID.randomUUID(), 2500L, "SAR", "BOOKING", "CAPTURED", 0L,
                new BigDecimal("0.10"), "psp_123", now, now);

        assertThat(summary.id()).isEqualTo(id);
        assertThat(summary.bookingId()).isEqualTo(bookingId);
        assertThat(summary.adCampaignId()).isNull();
        assertThat(summary.orderId()).isNull();
        assertThat(summary.loanId()).isNull();
        assertThat(summary.amountCents()).isEqualTo(2500L);
        assertThat(summary.currency()).isEqualTo("SAR");
        assertThat(summary.origin()).isEqualTo("BOOKING");
        assertThat(summary.status()).isEqualTo("CAPTURED");
        assertThat(summary.refundedAmountCents()).isZero();
        assertThat(summary.commissionRate()).isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(summary.pspIntentId()).isEqualTo("psp_123");
        assertThat(summary.createdAt()).isEqualTo(now);
        assertThat(summary.updatedAt()).isEqualTo(now);
    }

    // ------------------------------------------------------------------
    // ProviderListingView — the storefront listing read model
    // ------------------------------------------------------------------

    @Test
    void theProviderListingPreWideningFormCarriesTheHonestNulls() {
        ProviderListingView legacy = new ProviderListingView(id, "Title", "Description",
                "stay", 1200L, "SAR", UUID.randomUUID(), "ACTIVE", 4, now, now);
        assertThat(legacy.expiresAt()).isNull();
        assertThat(legacy.pausedReason()).isNull();
        assertThat(legacy.maxGuests()).isEqualTo(4);
        assertThat(legacy.category()).isEqualTo("stay");

        ProviderListingView widened = new ProviderListingView(id, "Title", "Description",
                "stay", 1200L, "SAR", UUID.randomUUID(), "PAUSED", 4, now, now,
                now.plusSeconds(3600), "host request");
        assertThat(widened.pausedReason()).isEqualTo("host request");
        assertThat(widened.expiresAt()).isEqualTo(now.plusSeconds(3600));
    }

    // ------------------------------------------------------------------
    // DeliveryCompletedEvent — the completion event's three forms
    // ------------------------------------------------------------------

    @Test
    void theDeliveryCompletedEventCarriesItsThreeConstructionForms() {
        UUID deliveryRequestId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID courierUserId = UUID.randomUUID();

        DeliveryCompletedEvent full = new DeliveryCompletedEvent(deliveryRequestId,
                paymentIntentId, 5000L, "SAR", 700L, now, courierUserId, UUID.randomUUID());
        assertThat(full.orderId()).isNotNull();
        assertThat(full.courierCompensationMinor()).isEqualTo(700L);

        DeliveryCompletedEvent noOrder = new DeliveryCompletedEvent(deliveryRequestId,
                paymentIntentId, 5000L, "SAR", 700L, now, courierUserId);
        assertThat(noOrder.orderId()).isNull();
        assertThat(noOrder.courierUserId()).isEqualTo(courierUserId);

        DeliveryCompletedEvent amountOnly = new DeliveryCompletedEvent(deliveryRequestId,
                paymentIntentId, 5000L, "SAR", now);
        assertThat(amountOnly.courierCompensationMinor()).isZero();
        assertThat(amountOnly.courierUserId()).isNull();
        assertThat(amountOnly.orderId()).isNull();
        assertThat(amountOnly.deliveredAt()).isEqualTo(now);
    }

    // ------------------------------------------------------------------
    // NeighborhoodFeedPostView — the feed read model's defensive copy
    // ------------------------------------------------------------------

    @Test
    void theNeighborhoodFeedPostViewDefendsItsMediaList() {
        List<NeighborhoodFeedMediaView> mutable = new java.util.ArrayList<>();
        mutable.add(new NeighborhoodFeedMediaView(UUID.randomUUID(),
                "https://signed/1", "https://thumb/1", "image/jpeg", 1));
        NeighborhoodFeedPostView post = new NeighborhoodFeedPostView(id, UUID.randomUUID(),
                UUID.randomUUID(), "lost", "Title", "Body", "PUBLISHED", true, "LOST",
                3L, false, mutable, now, now);

        mutable.add(new NeighborhoodFeedMediaView(UUID.randomUUID(),
                "https://signed/2", "https://thumb/2", "image/jpeg", 2));
        // The compact constructor's List.copyOf: the record's own media is
        // the immutable copy, never the caller's mutable view.
        assertThat(post.media()).hasSize(1);
        assertThat(post.media().get(0).url()).isEqualTo("https://signed/1");
        assertThat(post.media().get(0).thumbUrl()).isEqualTo("https://thumb/1");
        assertThat(post.media().get(0).contentType()).isEqualTo("image/jpeg");
        assertThat(post.media().get(0).position()).isEqualTo(1);
        assertThat(post.declaredCommercial()).isTrue();
        assertThat(post.lostFoundState()).isEqualTo("LOST");
        assertThat(post.reactionsCount()).isEqualTo(3L);
        assertThat(post.reactedByMe()).isFalse();
    }
}
