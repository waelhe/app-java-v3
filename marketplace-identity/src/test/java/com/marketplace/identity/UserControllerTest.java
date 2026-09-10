package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserDataExportService userDataExportService;

    @InjectMocks
    private UserController userController;

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
                java.util.List.of(), java.util.List.of(), java.util.List.of());

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
}
