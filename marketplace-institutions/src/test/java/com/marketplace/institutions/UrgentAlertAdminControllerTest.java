package com.marketplace.institutions;

import com.marketplace.shared.api.PagedResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the admin REST
 * surface's statuses — the direct controller-invocation house pattern:
 * 201 on the delegation's entry and on the publish, 200 on the reads,
 * the verification moves and the withdrawal, the paged envelope riding
 * {@code PagedResponse}.
 */
@ExtendWith(MockitoExtension.class)
class UrgentAlertAdminControllerTest {

    @Mock
    private UrgentAlertService service;

    @InjectMocks
    private UrgentAlertAdminController controller;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), java.time.ZoneOffset.UTC);

    private UrgentAlertSource source() {
        UrgentAlertSource source = UrgentAlertSource.delegate(
                "أمانة محافظة الرياض", UrgentAlertSourceType.MUNICIPALITY);
        source.requestVerification();
        source.approveVerification();
        return source;
    }

    @Test
    void createSourceAnswers201() {
        var request = new UrgentAlertSourceRequest("الدفاع المدني", UrgentAlertSourceType.CIVIL_DEFENSE);
        when(service.createSource(request)).thenReturn(
                UrgentAlertSource.delegate("الدفاع المدني", UrgentAlertSourceType.CIVIL_DEFENSE));

        ResponseEntity<UrgentAlertSourceResponse> result = controller.createSource(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().name()).isEqualTo("الدفاع المدني");
        assertThat(result.getBody().verificationState()).isEqualTo("UNVERIFIED");
    }

    @Test
    void confirmAndRejectAnswer200() {
        UrgentAlertSource source = source();
        when(service.reviewSource(source.getId(), true)).thenReturn(source);

        ResponseEntity<UrgentAlertSourceResponse> confirmed =
                controller.confirmVerification(source.getId());

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().verificationState()).isEqualTo("VERIFIED");

        when(service.reviewSource(source.getId(), false)).thenReturn(source);
        ResponseEntity<UrgentAlertSourceResponse> rejected =
                controller.rejectVerification(source.getId());
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void queueAnswers200WithThePagedEnvelope() {
        var pageable = PageRequest.of(0, 20);
        when(service.reviewQueue(InstitutionVerificationState.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(
                        UrgentAlertSource.delegate("بلدية", UrgentAlertSourceType.MUNICIPALITY)),
                        pageable, 1));

        ResponseEntity<PagedResponse<UrgentAlertSourceResponse>> result =
                controller.queue(InstitutionVerificationState.PENDING, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
    }

    @Test
    void publishAnswers201() {
        var request = new UrgentAlertPublishRequest(UUID.randomUUID(), UUID.randomUUID(),
                UrgentAlertLevel.CRITICAL, "انقطاع المياه صباح الخميس", "توقف ضخ المياه في الحي.",
                clock.instant().minusSeconds(60), clock.instant().plusSeconds(3600));
        UrgentAlertSource source = source();
        UrgentAlert alert = UrgentAlert.publish(source.getId(), request.locationId(),
                UrgentAlertLevel.CRITICAL, request.title(), request.body(),
                request.validFrom(), request.validUntil());
        when(service.publishAlert(request)).thenReturn(new UrgentAlertService.ActiveAlert(alert, source));

        ResponseEntity<UrgentAlertResponse> result = controller.publish(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().sourceName()).isEqualTo("أمانة محافظة الرياض");
        assertThat(result.getBody().level()).isEqualTo("CRITICAL");
        assertThat(result.getBody().withdrawn()).isFalse();
    }

    @Test
    void withdrawAnswers200WithTheHonestyFlags() {
        UrgentAlertSource source = source();
        UrgentAlert alert = UrgentAlert.publish(source.getId(), UUID.randomUUID(),
                UrgentAlertLevel.ADVISORY, "تنبيه", "نص التنبيه.",
                clock.instant().minusSeconds(60), null);
        alert.withdraw(clock.instant());
        when(service.withdrawAlert(alert.getId())).thenReturn(new UrgentAlertService.ActiveAlert(alert, source));

        ResponseEntity<UrgentAlertResponse> result = controller.withdraw(alert.getId());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().withdrawn()).isTrue();
        assertThat(result.getBody().withdrawnAt()).isEqualTo(clock.instant());
    }
}
