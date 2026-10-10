package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Waves D1-D4 + the events-through-Modulith relocation: the cross-module
 * event contracts now living in shared-api answer for their value
 * semantics here (the records the publishing modules emit and the
 * notifications/AI consumers dedupe on). The pins that matter: the
 * urgent-alert publication carries the honest attribution (source name +
 * level + scope — CMP-46), the withdrawal carries only the identity pair,
 * and the relocated knowledge/dispute contracts keep their exact field
 * shapes the consumers already read.
 */
class CrossModuleEventContractsTest {

    private static final Instant T = Instant.parse("2026-10-10T00:00:00Z");

    @Test
    void urgentAlertPublishedEvent_carriesTheHonestAttribution() {
        UUID alertId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UrgentAlertPublishedEvent event = new UrgentAlertPublishedEvent(
                alertId, sourceId, "دفاع مدني — حي القدس", locationId, "CRITICAL",
                "إغلاق مؤقت لمخرج الشرطة", T);
        assertThat(event.alertId()).isEqualTo(alertId);
        assertThat(event.sourceId()).isEqualTo(sourceId);
        assertThat(event.sourceName()).isEqualTo("دفاع مدني — حي القدس");
        assertThat(event.locationId()).isEqualTo(locationId);
        assertThat(event.level()).isEqualTo("CRITICAL");
        assertThat(event.title()).isEqualTo("إغلاق مؤقت لمخرج الشرطة");
        assertThat(event.occurredAt()).isEqualTo(T);
        assertThat(event.toString()).contains("CRITICAL").contains(alertId.toString());
    }

    @Test
    void urgentAlertPublishedEvent_recordSemantics() {
        UUID alertId = UUID.randomUUID();
        UrgentAlertPublishedEvent a = new UrgentAlertPublishedEvent(
                alertId, UUID.randomUUID(), "بلدية", UUID.randomUUID(), "SEVERE", "t", T);
        UrgentAlertPublishedEvent b = new UrgentAlertPublishedEvent(
                a.alertId(), a.sourceId(), a.sourceName(), a.locationId(), a.level(), a.title(), a.occurredAt());
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void urgentAlertWithdrawnEvent_carriesOnlyTheIdentityPair() {
        UUID alertId = UUID.randomUUID();
        UrgentAlertWithdrawnEvent a = new UrgentAlertWithdrawnEvent(alertId, T);
        UrgentAlertWithdrawnEvent b = new UrgentAlertWithdrawnEvent(alertId, T);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.alertId()).isEqualTo(alertId);
        assertThat(a.occurredAt()).isEqualTo(T);
        assertThat(a.toString()).contains(alertId.toString());
    }

    @Test
    void relocatedKnowledgeContracts_keepTheirExactShapes() {
        UUID entryId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        KnowledgeEntryPublishedEvent published = new KnowledgeEntryPublishedEvent(
                entryId, locationId, "SOURCES", "أصل المعلومة", "نصها", authorId);
        KnowledgeEntryWithdrawnEvent withdrawn =
                new KnowledgeEntryWithdrawnEvent(entryId, locationId);
        assertThat(published.entryId()).isEqualTo(entryId);
        assertThat(published.locationId()).isEqualTo(locationId);
        assertThat(published.category()).isEqualTo("SOURCES");
        assertThat(published.title()).isEqualTo("أصل المعلومة");
        assertThat(published.body()).isEqualTo("نصها");
        assertThat(published.authorId()).isEqualTo(authorId);
        assertThat(published).isEqualTo(new KnowledgeEntryPublishedEvent(
                entryId, locationId, "SOURCES", "أصل المعلومة", "نصها", authorId));
        assertThat(withdrawn).isEqualTo(new KnowledgeEntryWithdrawnEvent(entryId, locationId));
        assertThat(published.toString()).contains("SOURCES").contains(entryId.toString());
        assertThat(withdrawn.toString()).contains(entryId.toString());
    }

    @Test
    void relocatedDisputeContracts_keepTheirExactShapes() {
        UUID disputeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID opener = UUID.randomUUID();
        DisputeOpenedEvent opened = new DisputeOpenedEvent(disputeId, bookingId, opener);
        DisputeResolvedEvent resolved =
                new DisputeResolvedEvent(disputeId, bookingId, opener, "REFUND_CONSUMER", 5000L);
        assertThat(opened.disputeId()).isEqualTo(disputeId);
        assertThat(opened.bookingId()).isEqualTo(bookingId);
        assertThat(opened.openedBy()).isEqualTo(opener);
        assertThat(opened).isEqualTo(new DisputeOpenedEvent(disputeId, bookingId, opener));
        assertThat(resolved.disputeId()).isEqualTo(disputeId);
        assertThat(resolved.openedBy()).isEqualTo(opener);
        assertThat(resolved.resolution()).isEqualTo("REFUND_CONSUMER");
        assertThat(resolved.refundedAmountCents()).isEqualTo(5000L);
        assertThat(resolved).isEqualTo(new DisputeResolvedEvent(
                disputeId, bookingId, opener, "REFUND_CONSUMER", 5000L));
        assertThat(resolved.toString()).contains("REFUND_CONSUMER");
    }
}
