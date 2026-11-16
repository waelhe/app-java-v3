package com.marketplace.institutions.spi;

import com.marketplace.institutions.InstitutionVerificationState;
import com.marketplace.institutions.UrgentAlert;
import com.marketplace.institutions.UrgentAlertLevel;
import com.marketplace.institutions.UrgentAlertService;
import com.marketplace.institutions.UrgentAlertSource;
import com.marketplace.institutions.UrgentAlertSourceType;
import com.marketplace.shared.api.UrgentAlertsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the port seam's
 * mapping contracts — the card carries the source's honest attribution
 * (its name and type ride VERBATIM), the level rides as TEXT (CMP-46),
 * and the eligibility engine's verdict IS the port's answer (whatever
 * {@code activeAlerts} refuses never reaches a consumer).
 */
@ExtendWith(MockitoExtension.class)
class UrgentAlertsAdapterTest {

    private static final UUID NEIGHBORHOOD_ID = UUID.randomUUID();

    @Mock
    private UrgentAlertService urgentAlertService;

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);

    @Test
    void findActiveMapsTheEnginePairToThePortCard() {
        UrgentAlertSource source = verifiedSource();
        UrgentAlert alert = UrgentAlert.publish(source.getId(), NEIGHBORHOOD_ID, UrgentAlertLevel.SEVERE,
                "تحذير من الضباب الكثيف", "رؤية محدودة على مداخل الحي حتى العاشرة صباحاً.",
                now.minusSeconds(300), now.plusSeconds(7200));
        when(urgentAlertService.activeAlerts(NEIGHBORHOOD_ID, now))
                .thenReturn(List.of(new UrgentAlertService.ActiveAlert(alert, source)));

        List<UrgentAlertsPort.UrgentAlertCard> cards =
                new UrgentAlertsAdapter(urgentAlertService).findActive(NEIGHBORHOOD_ID, now);

        assertThat(cards).hasSize(1);
        UrgentAlertsPort.UrgentAlertCard card = cards.get(0);
        assertThat(card.alertId()).isEqualTo(alert.getId());
        assertThat(card.sourceId()).isEqualTo(source.getId());
        assertThat(card.sourceName()).isEqualTo("أمانة محافظة الرياض");
        assertThat(card.sourceType()).isEqualTo("MUNICIPALITY");
        // CMP-46: the level rides as TEXT — the string every surface renders.
        assertThat(card.level()).isEqualTo("SEVERE");
        assertThat(card.title()).isEqualTo("تحذير من الضباب الكثيف");
        assertThat(card.body()).isEqualTo("رؤية محدودة على مداخل الحي حتى العاشرة صباحاً.");
        assertThat(card.locationId()).isEqualTo(NEIGHBORHOOD_ID);
        assertThat(card.validFrom()).isEqualTo(now.minusSeconds(300));
        assertThat(card.validUntil()).isEqualTo(now.plusSeconds(7200));
        assertThat(card.updatedAt()).isEqualTo(alert.getUpdatedAt());
    }

    @Test
    void findActiveRidesTheEngineVerdictVerbatim() {
        // The engine already refused everything ineligible (withdrawn,
        // expired, unverified source) — the port carries its answer as-is.
        when(urgentAlertService.activeAlerts(NEIGHBORHOOD_ID, now)).thenReturn(List.of());

        assertThat(new UrgentAlertsAdapter(urgentAlertService).findActive(NEIGHBORHOOD_ID, now)).isEmpty();
    }

    private UrgentAlertSource verifiedSource() {
        UrgentAlertSource source = UrgentAlertSource.delegate("أمانة محافظة الرياض", UrgentAlertSourceType.MUNICIPALITY);
        source.requestVerification();
        source.approveVerification();
        assertThat(source.getVerificationState()).isEqualTo(InstitutionVerificationState.VERIFIED);
        return source;
    }
}
