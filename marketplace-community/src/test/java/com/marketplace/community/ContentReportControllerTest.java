package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L45 — the report intake surface's delegation contract: the caller's
 * user id rides the CurrentUserProvider seam, the two type gates
 * (targetType/reason) parse BEFORE any service call, and the write
 * answers 201. The HTTP validation shape (400 blank fields) and the
 * security shape (401 anonymous) are pinned by the WebMvc and
 * integration tests on the real chain.
 */
@ExtendWith(MockitoExtension.class)
class ContentReportControllerTest {

    @Mock
    private ContentReportService reportService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private ContentReportController controller;

    private UUID reporterId = UUID.randomUUID();
    private UUID targetId = UUID.randomUUID();

    private ContentReportView view() {
        return new ContentReportView(UUID.randomUUID(), reporterId, "POST", targetId,
                "SPAM", "OPEN", null, null, null,
                Instant.parse("2026-09-18T10:00:00Z"),
                Instant.parse("2026-09-18T10:00:00Z"));
    }

    @Test
    void create_delegatesWithTheParsedEnums() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(reporterId);
        ContentReportView view = view();
        when(reportService.createReport(reporterId, ReportTargetType.POST, targetId,
                ReportReason.SPAM)).thenReturn(view);

        ResponseEntity<ContentReportView> response = controller.create(
                new ContentReportController.CreateReportRequest("POST", targetId, "SPAM"),
                authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(view);
        verify(reportService).createReport(reporterId, ReportTargetType.POST, targetId,
                ReportReason.SPAM);
    }

    @Test
    void create_invalidTargetType_is400BeforeAnyServiceCall() {
        assertThatThrownBy(() -> controller.create(
                new ContentReportController.CreateReportRequest("LISTING", targetId, "SPAM"),
                authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("POST, COMMENT");
        org.mockito.Mockito.verifyNoInteractions(reportService);
    }

    @Test
    void create_invalidReason_is400BeforeAnyServiceCall() {
        assertThatThrownBy(() -> controller.create(
                new ContentReportController.CreateReportRequest("POST", targetId, "RUDENESS"),
                authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SPAM, HARASSMENT, INAPPROPRIATE, OTHER");
        org.mockito.Mockito.verifyNoInteractions(reportService);
    }
}
