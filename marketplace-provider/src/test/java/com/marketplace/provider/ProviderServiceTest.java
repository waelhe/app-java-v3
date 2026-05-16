package com.marketplace.provider;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import com.marketplace.shared.api.ResourceNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.Mockito.*;

class ProviderServiceTest {

    private final ProviderRepository repository = mock(ProviderRepository.class);
    private final ProviderService service = new ProviderService(repository);

    @Test
    void verifyChangesStatusToVerified() {
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Provider A")
                .set(field(ProviderProfile::getBio), "bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        when(repository.findById(profile.getId())).thenReturn(Optional.of(profile));

        ProviderProfile verified = service.verify(profile.getId());

        assertThat(verified.getStatus()).isEqualTo(ProviderStatus.VERIFIED);
    }

    @Test
    void suspendChangesStatusToSuspended() {
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Provider B")
                .set(field(ProviderProfile::getBio), "bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        when(repository.findById(profile.getId())).thenReturn(Optional.of(profile));

        ProviderProfile suspended = service.suspend(profile.getId());

        assertThat(suspended.getStatus()).isEqualTo(ProviderStatus.SUSPENDED);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID unknownId = Instancio.create(UUID.class);
        when(repository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSavesAndReturnsProfile() {
        when(repository.save(any(ProviderProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderProfile profile = service.create("New Provider", "bio text");
        assertThat(profile.getDisplayName()).isEqualTo("New Provider");
        assertThat(profile.getBio()).isEqualTo("bio text");
        assertThat(profile.getStatus()).isEqualTo(ProviderStatus.PENDING);
    }

    @Test
    void getByIdReturnsProfile() {
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Existing")
                .set(field(ProviderProfile::getBio), "bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.VERIFIED)
                .create();
        when(repository.findById(profile.getId())).thenReturn(Optional.of(profile));
        ProviderProfile found = service.getById(profile.getId());
        assertThat(found.getDisplayName()).isEqualTo("Existing");
    }

    @Test
    void updateChangesDisplayNameAndBio() {
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Old Name")
                .set(field(ProviderProfile::getBio), "Old bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        when(repository.findById(profile.getId())).thenReturn(Optional.of(profile));
        ProviderProfile updated = service.update(profile.getId(), "New Name", "New bio");
        assertThat(updated.getDisplayName()).isEqualTo("New Name");
        assertThat(updated.getBio()).isEqualTo("New bio");
    }

    @Test
    void profileCreateSetsPendingStatus() {
        ProviderProfile profile = ProviderProfile.create("Test", "desc");
        assertThat(profile.getDisplayName()).isEqualTo("Test");
        assertThat(profile.getBio()).isEqualTo("desc");
        assertThat(profile.getStatus()).isEqualTo(ProviderStatus.PENDING);
        assertThat(profile.getId()).isNotNull();
    }
}
