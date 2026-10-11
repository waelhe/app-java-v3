package com.marketplace.institutions;

import com.marketplace.shared.api.UrgentAlertsPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the public read
 * surface's contracts — the read goes THROUGH THE PORT's own semantics
 * (the adapter's answer mapped verbatim, never a second eligibility
 * path), locationId is required, and the caller's validity rides the
 * /me seam (the neighborhoods' own discipline).
 */
@ExtendWith(MockitoExtension.class)
class UrgentAlertControllerTest {

    @Mock
    private UrgentAlertsPort urgentAlertsPort;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);

    private UrgentAlertController controller;

    @BeforeEach
    void wireController() {
        // Mockito's @InjectMocks cannot construct final classes (Clock) — the
        // fixed clock is the honest wiring (the service test's own shape).
        controller = new UrgentAlertController(urgentAlertsPort, currentUserProvider, clock);
    }

    @Test
    void activeRidesThePortSemanticsVerbatim() {
        UUID locationId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(urgentAlertsPort.findActive(locationId, now)).thenReturn(List.of(
                new UrgentAlertsPort.UrgentAlertCard(
                        UUID.randomUUID(), UUID.randomUUID(), "أمانة محافظة الرياض",
                        "MUNICIPALITY", "CRITICAL", "انقطاع المياه صباح الخميس",
                        "توقف ضخ المياه في الحي من 8 صباحاً حتى 2 ظهراً.",
                        locationId, now.minusSeconds(60), now.plusSeconds(3600), now)));

        List<UrgentAlertResponse> result = controller.active(locationId, authentication);

        assertThat(result).hasSize(1);
        UrgentAlertResponse row = result.get(0);
        assertThat(row.sourceName()).isEqualTo("أمانة محافظة الرياض");
        assertThat(row.sourceType()).isEqualTo("MUNICIPALITY");
        assertThat(row.level()).isEqualTo("CRITICAL");
        assertThat(row.locationId()).isEqualTo(locationId);
        assertThat(row.withdrawn()).isFalse();
        assertThat(row.withdrawnAt()).isNull();
    }

    @Test
    void activeResolvesTheCallerThroughTheMeSeam() {
        UUID locationId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(urgentAlertsPort.findActive(locationId, now)).thenReturn(List.of());

        controller.active(locationId, authentication);

        verify(currentUserProvider).getCurrentUserId(authentication);
        // The window's anchor is the service's own clock — the same instant the port's consumers use.
        verify(urgentAlertsPort).findActive(locationId, now);
    }
}
