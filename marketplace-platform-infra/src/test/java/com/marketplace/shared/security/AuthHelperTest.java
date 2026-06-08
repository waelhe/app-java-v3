package com.marketplace.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AuthHelperTest {

    private final CurrentUserProvider currentUserProvider = mock();
    private final ProviderLookupPort providerLookupPort = mock();
    private final AuthHelper authHelper = new AuthHelper(currentUserProvider, providerLookupPort);

    private final Authentication auth = mock();
    private final UUID userId = UUID.randomUUID();

    @Test
    void isCurrentUser_returnsTrueWhenMatch() {
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        assertThat(authHelper.isCurrentUser(userId, auth)).isTrue();
    }

    @Test
    void isCurrentUser_returnsFalseWhenMismatch() {
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(UUID.randomUUID());
        assertThat(authHelper.isCurrentUser(userId, auth)).isFalse();
    }

    @Test
    void isAdmin_delegatesToProvider() {
        when(currentUserProvider.isAdmin(auth)).thenReturn(true);
        assertThat(authHelper.isAdmin(auth)).isTrue();

        when(currentUserProvider.isAdmin(auth)).thenReturn(false);
        assertThat(authHelper.isAdmin(auth)).isFalse();
    }

    @Test
    void ownsProvider_returnsTrueWhenUserMatches() {
        UUID providerId = UUID.randomUUID();
        ProviderSummary summary = mock();
        when(summary.userId()).thenReturn(userId);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(providerLookupPort.findById(providerId)).thenReturn(Optional.of(summary));

        assertThat(authHelper.ownsProvider(providerId, auth)).isTrue();
    }

    @Test
    void ownsProvider_returnsFalseWhenUserMismatch() {
        UUID providerId = UUID.randomUUID();
        ProviderSummary summary = mock();
        when(summary.userId()).thenReturn(UUID.randomUUID());
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(providerLookupPort.findById(providerId)).thenReturn(Optional.of(summary));

        assertThat(authHelper.ownsProvider(providerId, auth)).isFalse();
    }

    @Test
    void ownsProvider_returnsFalseWhenProviderNotFound() {
        UUID providerId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(providerLookupPort.findById(providerId)).thenReturn(Optional.empty());

        assertThat(authHelper.ownsProvider(providerId, auth)).isFalse();
    }
}
