package com.marketplace.provider;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

class ProviderProfileTest {

    @Test
    void shouldCreateProfileWithPendingStatus() {
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Test Provider")
                .set(field(ProviderProfile::getBio), "A bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();

        assertThat(profile.getDisplayName()).isEqualTo("Test Provider");
        assertThat(profile.getBio()).isEqualTo("A bio");
        assertThat(profile.getStatus()).isEqualTo(ProviderStatus.PENDING);
        assertThat(profile.getId()).isNotNull();
    }

    @Test
    void shouldUpdateProfile() {
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Old Name")
                .set(field(ProviderProfile::getBio), "Old bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        profile.update("New Name", "New bio");

        assertThat(profile.getDisplayName()).isEqualTo("New Name");
        assertThat(profile.getBio()).isEqualTo("New bio");
    }

    @Test
    void shouldVerifyProfile() {
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Test")
                .set(field(ProviderProfile::getBio), "Bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        profile.verify();

        assertThat(profile.getStatus()).isEqualTo(ProviderStatus.VERIFIED);
    }

    @Test
    void shouldSuspendProfile() {
        ProviderProfile profile = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "Test")
                .set(field(ProviderProfile::getBio), "Bio")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        profile.suspend();

        assertThat(profile.getStatus()).isEqualTo(ProviderStatus.SUSPENDED);
    }

    @Test
    void shouldCreateViaStaticFactory() {
        ProviderProfile profile = ProviderProfile.create("Factory Name", "Factory bio");
        assertThat(profile.getDisplayName()).isEqualTo("Factory Name");
        assertThat(profile.getBio()).isEqualTo("Factory bio");
        assertThat(profile.getStatus()).isEqualTo(ProviderStatus.PENDING);
        assertThat(profile.getId()).isNotNull();
    }

    @Test
    void shouldCreateWithRandomId() {
        ProviderProfile p1 = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "A")
                .set(field(ProviderProfile::getBio), "Bio A")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
        ProviderProfile p2 = Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getDisplayName), "B")
                .set(field(ProviderProfile::getBio), "Bio B")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();

        assertThat(p1.getId()).isNotEqualTo(p2.getId());
    }
}
