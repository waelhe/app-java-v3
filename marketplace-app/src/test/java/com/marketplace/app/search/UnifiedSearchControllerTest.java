package com.marketplace.app.search;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.UnifiedSearchDomain;
import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The unified search controller unit slice: the domain whitelist (unknown →
 * 400), the anonymous/authenticated caller seam, and the delegation shape.
 */
@ExtendWith(MockitoExtension.class)
class UnifiedSearchControllerTest {

    @Mock
    private UnifiedSearchPort unifiedSearch;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private UnifiedSearchController controller;

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static JwtAuthenticationToken jwtToken(String subject) {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("alg", "none"), Map.of("sub", subject));
        return new JwtAuthenticationToken(jwt);
    }

    @Test
    void search_resolvesTheCallerThroughTheSeamAndDelegates() {
        UUID callerId = UUID.randomUUID();
        JwtAuthenticationToken token = jwtToken("sub-1");
        when(currentUserProvider.tryGetCurrentUserId(token)).thenReturn(Optional.of(callerId));
        when(unifiedSearch.search(callerId, UnifiedSearchDomain.COMMUNITY_POSTS, "مفتاح",
                null, PagedRequest.of(0, 20)))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0, true));

        var result = controller.search("COMMUNITY_POSTS", "مفتاح", null,
                org.springframework.data.domain.PageRequest.of(0, 20), token);

        assertEquals(200, result.getStatusCode().value());
        verify(unifiedSearch).search(any(), any(), any(), any(), any());
    }

    @Test
    void search_ofAnUnknownDomain_isBadRequest() {
        assertThrows(BadRequestException.class, () -> controller.search("EVERYTHING", "q", null,
                org.springframework.data.domain.PageRequest.of(0, 20), mock(Authentication.class)));
    }

    @Test
    void search_ofAnAnonymousCaller_passesNullCallerThrough() {
        when(currentUserProvider.tryGetCurrentUserId(any())).thenReturn(Optional.empty());
        when(unifiedSearch.search(null, UnifiedSearchDomain.LISTINGS, "oven",
                null, PagedRequest.of(0, 20)))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0, true));

        var result = controller.search("LISTINGS", "oven", null,
                org.springframework.data.domain.PageRequest.of(0, 20), mock(Authentication.class));

        assertEquals(200, result.getStatusCode().value());
    }
}
