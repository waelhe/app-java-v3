package com.marketplace.identity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityProviderNameResolverTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final IdentityProviderNameResolver resolver = new IdentityProviderNameResolver(userRepository);

    @Test
    void resolveNames_prefersDisplayName() {
        UUID id = UUID.randomUUID();
        User user = User.create("sub-1", "provider@example.com", "Provider Name", UserRole.PROVIDER);
        when(userRepository.findAllById(Set.of(id))).thenReturn(List.of(
                new User(id, user.getSubject(), user.getEmail(), user.getDisplayName(), user.getRole())
        ));

        Map<UUID, String> result = resolver.resolveNames(Set.of(id));

        assertEquals("Provider Name", result.get(id));
    }

    @Test
    void resolveNames_fallsBackToEmailThenDefault() {
        UUID emailFallbackId = UUID.randomUUID();
        UUID defaultFallbackId = UUID.randomUUID();
        User emailOnly = new User(emailFallbackId, "sub-2", "fallback@example.com", " ", UserRole.PROVIDER);
        User noIdentity = new User(defaultFallbackId, "sub-3", null, null, UserRole.PROVIDER);
        when(userRepository.findAllById(Set.of(emailFallbackId, defaultFallbackId)))
                .thenReturn(List.of(emailOnly, noIdentity));

        Map<UUID, String> result = resolver.resolveNames(Set.of(emailFallbackId, defaultFallbackId));

        assertEquals("fallback@example.com", result.get(emailFallbackId));
        assertEquals("Provider", result.get(defaultFallbackId));
    }

    @Test
    void resolveNames_returnsEmptyForEmptyInput() {
        Map<UUID, String> result = resolver.resolveNames(Set.of());
        assertEquals(Map.of(), result);
    }

    @Test
    void resolveNames_returnsEmptyForNullInput() {
        Map<UUID, String> result = resolver.resolveNames(null);
        assertEquals(Map.of(), result);
    }
}
