package com.marketplace.community;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L41 — the /me surface's delegation contract: the status decision is the
 * command's own fact (created ⇒ 201, idempotent ⇒ 200), the caller's user
 * id rides the CurrentUserProvider seam, and the leave answers 204. The
 * HTTP validation shape (400) and the security shape (401 anonymous) are
 * pinned by the WebMvc and integration tests on the real chain.
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodMembershipControllerTest {

    @Mock
    private NeighborhoodMembershipService membershipService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private NeighborhoodMembershipController controller;

    private UUID userId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();

    private NeighborhoodMembershipView view(UUID location) {
        return new NeighborhoodMembershipView(
                UUID.randomUUID(), userId, location, "SELF_DECLARED",
                Instant.parse("2026-09-17T09:30:00Z"),
                Instant.parse("2026-09-17T09:30:00Z"),
                Instant.parse("2026-09-17T09:30:00Z"));
    }

    @Test
    void join_created_answers201() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(membershipService.join(userId, locationId))
                .thenReturn(new NeighborhoodMembershipService.MembershipCommandResult(
                        view(locationId), true));

        ResponseEntity<NeighborhoodMembershipView> result = controller.join(
                new NeighborhoodMembershipController.NeighborhoodJoinRequest(locationId),
                authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().locationId()).isEqualTo(locationId);
    }

    @Test
    void join_idempotent_answers200() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(membershipService.join(userId, locationId))
                .thenReturn(new NeighborhoodMembershipService.MembershipCommandResult(
                        view(locationId), false));

        ResponseEntity<NeighborhoodMembershipView> result = controller.join(
                new NeighborhoodMembershipController.NeighborhoodJoinRequest(locationId),
                authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getMine_delegatesWithTheCallerId() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(membershipService.getMine(userId)).thenReturn(view(locationId));

        ResponseEntity<NeighborhoodMembershipView> result = controller.getMine(authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().userId()).isEqualTo(userId);
    }

    @Test
    void leave_answers204() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);

        ResponseEntity<Void> result = controller.leave(authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(membershipService).leave(userId);
    }
}
