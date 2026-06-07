package com.marketplace.provider;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderStatusTest {

    @Test
    void pending_acceptsVerified() {
        assertDoesNotThrow(() -> ProviderStatus.PENDING.validateTransitionTo(ProviderStatus.VERIFIED));
    }

    @Test
    void pending_acceptsSuspended() {
        assertDoesNotThrow(() -> ProviderStatus.PENDING.validateTransitionTo(ProviderStatus.SUSPENDED));
    }

    @Test
    void verified_acceptsSuspended() {
        assertDoesNotThrow(() -> ProviderStatus.VERIFIED.validateTransitionTo(ProviderStatus.SUSPENDED));
    }

    @Test
    void suspended_acceptsVerified() {
        assertDoesNotThrow(() -> ProviderStatus.SUSPENDED.validateTransitionTo(ProviderStatus.VERIFIED));
    }

    @Test
    void pending_rejectsSameStatus() {
        assertThrows(ConflictException.class, () -> ProviderStatus.PENDING.validateTransitionTo(ProviderStatus.PENDING));
    }

    @Test
    void verified_rejectsPending() {
        assertThrows(ConflictException.class, () -> ProviderStatus.VERIFIED.validateTransitionTo(ProviderStatus.PENDING));
    }
}
