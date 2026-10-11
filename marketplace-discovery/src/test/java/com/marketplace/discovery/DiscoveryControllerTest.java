package com.marketplace.discovery;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.DiscoveryCardView;
import com.marketplace.shared.api.DiscoveryRowType;
import com.marketplace.shared.api.DiscoveryRowView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Wave D-1 (plan #536 §1.4 / JT-20): the REST surface's statuses — the
 * direct controller-invocation house pattern: 200 on the two reads (the
 * aggregated rails and the row topic page), 204 on the impression write,
 * and the type gates' house 400 on every invalid vocabulary value BEFORE
 * any service call (criterion 3).
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryControllerTest {

    private static final UUID CALLER_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();

    @Mock
    private DiscoveryService service;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private DiscoveryController controller;

    private void callerIsResolved() {
        // lenient: the parameter-validation tests (negative page, oversize)
        // reject before the caller resolution ever fires.
        lenient().when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(CALLER_ID);
    }

    private DiscoveryCardView card() {
        return new DiscoveryCardView("NEIGHBORHOOD_POST", SOURCE_ID, Instant.parse("2026-10-07T11:00:00Z"),
                "فقرة القطعة المفقودة", "المحتوى", "ACTIVE", UUID.randomUUID(),
                DiscoveryService.REASON_LOST_FOUND, null);
    }

    @Test
    void homeAnswers200WithTheNonEmptyRows() {
        callerIsResolved();
        when(service.homeRows(CALLER_ID)).thenReturn(List.of(
                new DiscoveryRowView(DiscoveryRowType.LOST_FOUND, List.of(card()), 1)));

        ResponseEntity<List<DiscoveryRowView>> result = controller.home(authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).row()).isEqualTo(DiscoveryRowType.LOST_FOUND);
        assertThat(result.getBody().get(0).totalEligible()).isEqualTo(1);
    }

    @Test
    void rowAnswers200WithThePagedEnvelope() {
        callerIsResolved();
        when(service.rowPage(CALLER_ID, DiscoveryRowType.LOST_FOUND, PagedRequest.of(0, 20)))
                .thenReturn(new PagedResponse<>(List.of(card()), 0, 20, 1, 1, true));

        ResponseEntity<PagedResponse<DiscoveryCardView>> result =
                controller.row(DiscoveryRowType.LOST_FOUND, 0, 20, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
    }

    @Test
    void rowRejectsASizeAboveTheDocumentedBound() {
        callerIsResolved();

        assertThatThrownBy(() -> controller.row(DiscoveryRowType.LOST_FOUND, 0, 51, authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("50");
    }

    @Test
    void rowRejectsANegativePage() {
        callerIsResolved();

        assertThatThrownBy(() -> controller.row(DiscoveryRowType.FOR_YOU, -1, 20, authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("page");
    }

    @Test
    void impressionAnswers204OnTheValidVocabulary() {
        callerIsResolved();
        var request = new DiscoveryController.ImpressionRequest(
                "FOR_YOU", "NEIGHBORHOOD_POST", SOURCE_ID);

        ResponseEntity<Void> result = controller.impression(request, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void impressionRejectsAnInvalidSourceTypeBeforeAnyWrite() {
        callerIsResolved();
        var request = new DiscoveryController.ImpressionRequest(
                "FOR_YOU", "NOT_A_SOURCE", SOURCE_ID);

        assertThatThrownBy(() -> controller.impression(request, authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("NEIGHBORHOOD_POST");
    }

    @Test
    void impressionRejectsAnInvalidRowBeforeAnyWrite() {
        callerIsResolved();
        var request = new DiscoveryController.ImpressionRequest(
                "NOT_A_ROW", "NEIGHBORHOOD_POST", SOURCE_ID);

        assertThatThrownBy(() -> controller.impression(request, authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("FOR_YOU");
    }
}
