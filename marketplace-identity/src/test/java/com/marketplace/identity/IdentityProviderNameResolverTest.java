package com.marketplace.identity;

import org.junit.jupiter.api.Test;

import org.instancio.Instancio;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.instancio.Select.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityProviderNameResolverTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final IdentityProviderNameResolver resolver = new IdentityProviderNameResolver(userRepository);

    @Test
    void resolveNames_prefersDisplayName() {
        UUID id = Instancio.create(UUID.class);
        User user = Instancio.of(User.class)
                .set(field(User::getSubject), "sub-1")
                .set(field(User::getEmail), "provider@example.com")
                .set(field(User::getDisplayName), "Provider Name")
                .set(field(User::getRole), UserRole.PROVIDER)
                .create();
        when(userRepository.findAllById(Set.of(id))).thenReturn(List.of(
                new User(id, user.getSubject(), user.getEmail(), user.getDisplayName(), user.getRole())
        ));

        Map<UUID, String> result = resolver.resolveNames(Set.of(id));

        assertEquals("Provider Name", result.get(id));
    }

    @Test
    void resolveNames_fallsBackToEmailThenDefault() {
        UUID emailFallbackId = Instancio.create(UUID.class);
        UUID defaultFallbackId = Instancio.create(UUID.class);
        User emailOnly = Instancio.of(User.class)
                .set(field(User::getId), emailFallbackId)
                .set(field(User::getSubject), "sub-2")
                .set(field(User::getEmail), "fallback@example.com")
                .set(field(User::getDisplayName), " ")
                .set(field(User::getRole), UserRole.PROVIDER)
                .create();
        User noIdentity = Instancio.of(User.class)
                .set(field(User::getId), defaultFallbackId)
                .set(field(User::getSubject), "sub-3")
                .set(field(User::getEmail), (String) null)
                .set(field(User::getDisplayName), (String) null)
                .set(field(User::getRole), UserRole.PROVIDER)
                .create();
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
