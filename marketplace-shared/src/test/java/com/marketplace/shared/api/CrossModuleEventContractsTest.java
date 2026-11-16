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
 * shapes the consumers already read (the dispute records ride ADR-0009's
 * five-component subject-aware shapes — the union the merge measured).
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
        UUID loanId = UUID.randomUUID();
        UUID opener = UUID.randomUUID();
        // ADR-0009's five-component shape: the subject pair carries exactly
        // one of bookingId/loanId (the V203 pairing constraint's event twin).
        DisputeOpenedEvent bookingOpen = new DisputeOpenedEvent(
                disputeId, DisputeSubject.BOOKING, bookingId, null, opener);
        DisputeOpenedEvent loanOpen = new DisputeOpenedEvent(
                disputeId, DisputeSubject.LOAN, null, loanId, opener);
        assertThat(bookingOpen.disputeId()).isEqualTo(disputeId);
        assertThat(bookingOpen.subjectType()).isEqualTo(DisputeSubject.BOOKING);
        assertThat(bookingOpen.bookingId()).isEqualTo(bookingId);
        assertThat(bookingOpen.loanId()).isNull();
        assertThat(bookingOpen.openedBy()).isEqualTo(opener);
        assertThat(bookingOpen).isEqualTo(new DisputeOpenedEvent(
                disputeId, DisputeSubject.BOOKING, bookingId, null, opener));
        assertThat(loanOpen.subjectType()).isEqualTo(DisputeSubject.LOAN);
        assertThat(loanOpen.bookingId()).isNull();
        assertThat(loanOpen.loanId()).isEqualTo(loanId);
        // The resolve fact: the typed resolution plus the executed money
        // outcome (non-null only for the BOOKING refund path).
        DisputeResolvedEvent resolved = new DisputeResolvedEvent(
                disputeId, DisputeSubject.BOOKING, bookingId, null, opener,
                DisputeResolution.REFUND_CONSUMER, 5000L);
        DisputeResolvedEvent loanResolved = new DisputeResolvedEvent(
                disputeId, DisputeSubject.LOAN, null, loanId, opener,
                DisputeResolution.REFUND_CONSUMER, null);
        assertThat(resolved.disputeId()).isEqualTo(disputeId);
        assertThat(resolved.openedBy()).isEqualTo(opener);
        assertThat(resolved.resolution()).isEqualTo(DisputeResolution.REFUND_CONSUMER);
        assertThat(resolved.refundedAmountCents()).isEqualTo(5000L);
        assertThat(resolved).isEqualTo(new DisputeResolvedEvent(
                disputeId, DisputeSubject.BOOKING, bookingId, null, opener,
                DisputeResolution.REFUND_CONSUMER, 5000L));
        assertThat(resolved.toString()).contains("REFUND_CONSUMER");
        // The loan path never moves money through this fact (ADR-0009's
        // ONE refund contract: the lending cancellation chain carries it).
        assertThat(loanResolved.refundedAmountCents()).isNull();
    }
}
