package com.marketplace.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserDataExportService userDataExportService;

    @Mock
    private AccountSelfDeletionService accountSelfDeletionService;

    @InjectMocks
    private UserController userController;

    @AfterEach
    void clearSecurityContext() {
        // The A-05 logout test seeds the thread's SecurityContextHolder —
        // restore it so no assertion state leaks across tests.
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUser_returnsUserResponse() {
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        UserResponse response = new UserResponse(UUID.randomUUID(), "a@b.com", "Alice", null, null);

        when(userService.syncFromOidc(token)).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(response);

        ResponseEntity<UserResponse> result = userController.getCurrentUser(token);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void exportMyData_syncsFirstThenAggregates() {
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        UserDataExportResponse export = new UserDataExportResponse(
                new UserDataExportResponse.ExportMetadata(java.time.Instant.now(),
                        UserDataExportResponse.SCOPE_NOTICE),
                new UserDataExportResponse.Profile(user.getId(), "sub-1", "a@b.com",
                        "Alice", "CONSUMER", null, null),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(),
                java.util.List.of()); // L35: savedSearches; L41: memberships; L42: posts+comments; #484: reactions; L49: events+seats; L50: market items; L51: group memberships; W4: follows; W3: favorites

        // The /me bootstrap convention: the profile syncs from the token's
        // freshest claims before it is read (the same call /me makes).
        when(userService.syncFromOidc(token)).thenReturn(user);
        when(userDataExportService.exportFor(user)).thenReturn(export);

        ResponseEntity<UserDataExportResponse> result = userController.exportMyData(token);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(export, result.getBody());
        assertEquals("sub-1", result.getBody().profile().subject());
        assertEquals(UserDataExportResponse.SCOPE_NOTICE,
                result.getBody().export().scopeNotice());
    }

    // -- A-05: DELETE /me (the self-service account deletion surface) ------

    @Test
    void deleteMyAccount_delegatesToTheDoubleVerificationThenCompletesTheLogoutAndAnswers204() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("sub-1");
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // The official warning's literal setup: a live SecurityContext the
        // endpoint must clear — "Failing to call SecurityContextLogoutHandler
        // means that the SecurityContext could still be available on
        // subsequent requests, meaning that the user is not actually logged
        // out."
        SecurityContextHolder.getContext().setAuthentication(token);

        ResponseEntity<Void> result = userController.deleteMyAccount(
                token, request, response, new UserController.DeleteAccountRequest("pw"));

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        // The subject comes from the token itself — the requester's own
        // proof, never a caller-supplied identifier.
        verify(accountSelfDeletionService).deleteOwnAccount("sub-1", "pw");
        // The complete logout: the holder's authentication is gone.
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void deleteMyAccount_rejectsUnsupportedAuthenticationBeforeTouchingAnyStore() {
        Authentication other = mock(Authentication.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalArgumentException.class, () -> userController.deleteMyAccount(
                other, request, response, new UserController.DeleteAccountRequest("pw")));

        verifyNoInteractions(accountSelfDeletionService);
    }
}
