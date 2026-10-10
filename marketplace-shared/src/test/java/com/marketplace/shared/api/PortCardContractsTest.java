package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Waves D1-D4: the discovery ports' nested card records — the values the
 * data-owner adapters hand the rail assembler. Each pin keeps the card's
 * full honest shape (source identity, lifecycle state, scope) so a card
 * can never lose a leg on its way across the boundary, and pins the
 * followed-source union vocabulary (USER/GROUP/PROVIDER) the
 * FollowedSourcesPort contract names.
 */
class PortCardContractsTest {

    private static final Instant T = Instant.parse("2026-10-10T00:00:00Z");

    @Test
    void discoveryPostCard_keepsTheFullHonestShape() {
        UUID postId = UUID.randomUUID();
        CommunityDiscoveryPort.DiscoveryPostCard card = new CommunityDiscoveryPort.DiscoveryPostCard(
                postId, UUID.randomUUID(), "LOST_FOUND", "ACTIVE", "قطتي ضاعت", "قرب الجامع",
                "VISIBLE", UUID.randomUUID(), T);
        assertThat(card.postId()).isEqualTo(postId);
        assertThat(card.category()).isEqualTo("LOST_FOUND");
        assertThat(card.lostFoundState()).isEqualTo("ACTIVE");
        assertThat(card.status()).isEqualTo("VISIBLE");
        assertThat(card.updatedAt()).isEqualTo(T);
        assertThat(card).isEqualTo(new CommunityDiscoveryPort.DiscoveryPostCard(
                card.postId(), card.authorId(), "LOST_FOUND", "ACTIVE", "قطتي ضاعت", "قرب الجامع",
                "VISIBLE", card.locationId(), T));
        assertThat(card.toString()).contains("LOST_FOUND").contains("قطتي ضاعت");
    }

    @Test
    void discoveryEventCard_keepsTheStatusHonestShape() {
        UUID eventId = UUID.randomUUID();
        CommunityDiscoveryPort.DiscoveryEventCard card = new CommunityDiscoveryPort.DiscoveryEventCard(
                eventId, UUID.randomUUID(), "تنظيف الحديقة", "أدوات مقدمة", "ACTIVE",
                "الحديقة العامة", T, T.plusSeconds(7200), T);
        assertThat(card.eventId()).isEqualTo(eventId);
        assertThat(card.status()).isEqualTo("ACTIVE");
        assertThat(card.startsAt()).isEqualTo(T);
        assertThat(card.endsAt()).isEqualTo(T.plusSeconds(7200));
        assertThat(card).isEqualTo(new CommunityDiscoveryPort.DiscoveryEventCard(
                card.eventId(), card.locationId(), "تنظيف الحديقة", "أدوات مقدمة", "ACTIVE",
                "الحديقة العامة", T, T.plusSeconds(7200), T));
        assertThat(card.toString()).contains("ACTIVE").contains("تنظيف الحديقة");
    }

    @Test
    void discoveryJobCard_keepsTheOpenOpportunityShape() {
        UUID jobId = UUID.randomUUID();
        JobsDiscoveryPort.DiscoveryJobCard card = new JobsDiscoveryPort.DiscoveryJobCard(
                jobId, "مطور جافا", "فرصة في حيّك", "FULL_TIME", "HYBRID", "OPEN", T);
        assertThat(card.jobId()).isEqualTo(jobId);
        assertThat(card.employmentType()).isEqualTo("FULL_TIME");
        assertThat(card.workplaceType()).isEqualTo("HYBRID");
        assertThat(card.status()).isEqualTo("OPEN");
        assertThat(card).isEqualTo(new JobsDiscoveryPort.DiscoveryJobCard(
                jobId, "مطور جافا", "فرصة في حيّك", "FULL_TIME", "HYBRID", "OPEN", T));
        assertThat(card.toString()).contains("OPEN").contains("مطور جافا");
    }

    @Test
    void urgentAlertCard_carriesTheDelegatedAttribution() {
        UUID alertId = UUID.randomUUID();
        UrgentAlertsPort.UrgentAlertCard card = new UrgentAlertsPort.UrgentAlertCard(
                alertId, UUID.randomUUID(), "دفاع مدني", "CIVIL_DEFENSE", "CRITICAL",
                "إغلاق مؤقت", "تفاصيل التنبيه", UUID.randomUUID(), T, T.plusSeconds(3600), T);
        assertThat(card.alertId()).isEqualTo(alertId);
        assertThat(card.sourceName()).isEqualTo("دفاع مدني");
        assertThat(card.sourceType()).isEqualTo("CIVIL_DEFENSE");
        assertThat(card.level()).isEqualTo("CRITICAL");
        assertThat(card.validUntil()).isEqualTo(T.plusSeconds(3600));
        assertThat(card).isEqualTo(new UrgentAlertsPort.UrgentAlertCard(
                card.alertId(), card.sourceId(), "دفاع مدني", "CIVIL_DEFENSE", "CRITICAL",
                "إغلاق مؤقت", "تفاصيل التنبيه", card.locationId(), T, T.plusSeconds(3600), T));
        assertThat(card.toString()).contains("CRITICAL").contains(alertId.toString());
    }

    @Test
    void followedSource_speaksTheClosedFollowVocabulary() {
        UUID sourceId = UUID.randomUUID();
        FollowedSourcesPort.FollowedSource user = new FollowedSourcesPort.FollowedSource("USER", sourceId);
        FollowedSourcesPort.FollowedSource provider = new FollowedSourcesPort.FollowedSource("PROVIDER", sourceId);
        assertThat(user).isEqualTo(new FollowedSourcesPort.FollowedSource("USER", sourceId));
        assertThat(user).isNotEqualTo(provider);
        assertThat(user.type()).isEqualTo("USER");
        assertThat(user.sourceId()).isEqualTo(sourceId);
        assertThat(user.toString()).contains("USER").contains(sourceId.toString());
    }
}
