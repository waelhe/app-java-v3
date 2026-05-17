package com.marketplace.provider;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import com.marketplace.shared.api.ResourceNotFoundException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.Mockito.*;

class ProviderServiceTest {

    @Test
    void verifyChangesStatusToVerified() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Provider A")
                .set(field(ProviderProfile::getBio), "bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        when(repository.findById(profile.getId())).thenReturn(java.util.Optional.of(profile));

        ProviderService service = new ProviderService(repository);
        ProviderProfile verified = service.verify(profile.getId());

        assertThat(verified.getStatus()).isEqualTo(ProviderStatus.VERIFIED);
    }

    @Test
    void suspendChangesStatusToSuspended() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Provider B")
                .set(field(ProviderProfile::getBio), "bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        when(repository.findById(profile.getId())).thenReturn(java.util.Optional.of(profile));

        ProviderService service = new ProviderService(repository);
        ProviderProfile suspended = service.suspend(profile.getId());

        assertThat(suspended.getStatus()).isEqualTo(ProviderStatus.SUSPENDED);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderService service = new ProviderService(repository);
        UUID unknownId = Instancio.create(UUID.class);
        when(repository.findById(unknownId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getById(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_savesAndReturnsProfile() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderService service = new ProviderService(repository);
        when(repository.save(any(ProviderProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderProfile result = service.create("New Provider", "desc");

        assertThat(result.getDisplayName()).isEqualTo("New Provider");
        assertThat(result.getBio()).isEqualTo("desc");
        assertThat(result.getStatus()).isEqualTo(ProviderStatus.PENDING);
    }

    @Test
    void getById_returnsProfileWhenFound() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderService service = new ProviderService(repository);
        UUID id = Instancio.create(UUID.class);
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getId), id)
                .set(field(ProviderProfile::getDisplayName), "Found")
                .create();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(profile));

        ProviderProfile result = service.getById(id);

        assertThat(result.getDisplayName()).isEqualTo("Found");
    }

    @Test
    void update_changesDisplayNameAndBio() {
        ProviderRepository repository = mock(ProviderRepository.class);
        ProviderService service = new ProviderService(repository);
        UUID id = Instancio.create(UUID.class);
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getId), id)
                .set(field(ProviderProfile::getDisplayName), "Old")
                .set(field(ProviderProfile::getBio), "old bio")
                .create();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(profile));

        ProviderProfile result = service.update(id, "New Name", "new bio");

        assertThat(result.getDisplayName()).isEqualTo("New Name");
        assertThat(result.getBio()).isEqualTo("new bio");
    }
}
