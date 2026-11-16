package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L45 — the administrative moderation surface's delegation contract
 * (the L30 pattern's controller leg): the admin's user id rides the
 * CurrentUserProvider seam, the two type gates (status/action) parse
 * BEFORE any service call, and the class-level ADMIN gate + the
 * service-level defense-in-depth gate are pinned by the WebMvc and
 * integration tests on the real chain (the plan's criterion 4 negative
 * 403 lives there).
 */
@ExtendWith(MockitoExtension.class)
class ModerationAdminControllerTest {

    @Mock
    private ContentReportService reportService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private ModerationAdminController controller;

    private UUID adminId = UUID.randomUUID();
    private UUID reportId = UUID.randomUUID();

    @Test
    void queue_delegatesWithTheParsedStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        when(reportService.getReports(ReportStatus.OPEN, pageable))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<PagedResponse<ContentReportView>> response =
                controller.queue("OPEN", pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reportService).getReports(ReportStatus.OPEN, pageable);
    }

    @Test
    void queue_blankStatus_isTheWholeQueue() {
        Pageable pageable = PageRequest.of(0, 20);
        when(reportService.getReports(null, pageable))
                .thenReturn(new PageImpl<>(List.of()));

        controller.queue("  ", pageable);

        verify(reportService).getReports(null, pageable);
    }

    @Test
    void queue_invalidStatus_is400BeforeAnyServiceCall() {
        assertThatThrownBy(() -> controller.queue("DONE", PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("OPEN, RESOLVED, DISMISSED");
        org.mockito.Mockito.verifyNoInteractions(reportService);
    }

    @Test
    void resolve_delegatesWithTheAdminIdAndParsedAction() {
        ContentReportView view = new ContentReportView(reportId, UUID.randomUUID(),
                "POST", UUID.randomUUID(), "SPAM", "RESOLVED", "Spam confirmed",
                adminId, Instant.parse("2026-09-18T10:15:00Z"),
                Instant.parse("2026-09-18T10:00:00Z"),
                Instant.parse("2026-09-18T10:15:00Z"));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(adminId);
        when(reportService.resolveReport(adminId, reportId, ModerationAction.HIDE_CONTENT,
                "Spam confirmed")).thenReturn(view);

        ResponseEntity<ContentReportView> response = controller.resolve(reportId,
                new ModerationAdminController.ResolveReportRequest("HIDE_CONTENT", "Spam confirmed"),
                authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(view);
    }

    @Test
    void resolve_invalidAction_is400BeforeAnyServiceCall() {
        assertThatThrownBy(() -> controller.resolve(reportId,
                new ModerationAdminController.ResolveReportRequest("DELETE", null), authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DISMISS, HIDE_CONTENT");
        org.mockito.Mockito.verifyNoInteractions(reportService);
    }
}
