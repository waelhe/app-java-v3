package com.marketplace.provider;

import org.junit.jupiter.api.Test;
import com.marketplace.shared.api.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProviderServiceTest {

    @Test
    void verifyChangesStatusToVerified() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderProfile profile = ProviderProfile.create("Provider A", "bio");
        when(repository.findById(profile.getId())).thenReturn(java.util.Optional.of(profile));

        ProviderService service = new ProviderService(repository);
        ProviderProfile verified = service.verify(profile.getId());

        assertThat(verified.getStatus()).isEqualTo(ProviderStatus.VERIFIED);
    }

    @Test
    void suspendChangesStatusToSuspended() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderProfile profile = ProviderProfile.create("Provider B", "bio");
        when(repository.findById(profile.getId())).thenReturn(java.util.Optional.of(profile));

        ProviderService service = new ProviderService(repository);
        ProviderProfile suspended = service.suspend(profile.getId());

        assertThat(suspended.getStatus()).isEqualTo(ProviderStatus.SUSPENDED);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderService service = new ProviderService(repository);
        java.util.UUID unknownId = java.util.UUID.randomUUID();
        when(repository.findById(unknownId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getById(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
