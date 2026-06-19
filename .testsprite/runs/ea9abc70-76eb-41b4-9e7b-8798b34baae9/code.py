# Auto-injected credentials — do not modify
__AUTH_CREDENTIAL__ = ""
__AUTH_TYPE__ = "public"
__AUTH_HEADERS__ = {}
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
}