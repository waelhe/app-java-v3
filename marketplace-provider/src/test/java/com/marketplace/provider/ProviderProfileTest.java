package com.marketplace.provider;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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

    // -- L36 (realestate systems plan §5): the persona fields -------------

    @Test
    void preL36FactoryForm_defaultsToIndividual() {
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID());

        assertThat(profile.getActorType()).isEqualTo(ProviderActorType.INDIVIDUAL);
        assertThat(profile.getAgencyName()).isNull();
        assertThat(profile.getLicenseNumber()).isNull();
    }

    @Test
    void l36FactoryForm_carriesThePersona() {
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID(),
                ProviderActorType.AGENCY, "Qudsia Prime", "BR-2026-1149");

        assertThat(profile.getActorType()).isEqualTo(ProviderActorType.AGENCY);
        assertThat(profile.getAgencyName()).isEqualTo("Qudsia Prime");
        assertThat(profile.getLicenseNumber()).isEqualTo("BR-2026-1149");
    }

    @Test
    void l36FactoryForm_nullActorTypeIsTheIndividualDefault() {
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID(),
                null, null, null);

        assertThat(profile.getActorType()).isEqualTo(ProviderActorType.INDIVIDUAL);
    }

    @Test
    void l36Update_nullActorTypeKeepsTheClassification() {
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID(),
                ProviderActorType.INDEPENDENT_BROKER, "Qudsia Prime", "BR-1");

        profile.update("John", "Bio", null, null, null);

        // The currency rule: a required classification is never silently
        // reset — while the optional display strings clear (the bio contract).
        assertThat(profile.getActorType()).isEqualTo(ProviderActorType.INDEPENDENT_BROKER);
        assertThat(profile.getAgencyName()).isNull();
        assertThat(profile.getLicenseNumber()).isNull();
    }

    @Test
    void l36Update_explicitActorTypeChangesTheClassification() {
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID());

        profile.update("John", "Bio", ProviderActorType.AGENCY, "Qudsia Prime", "BR-1");

        assertThat(profile.getActorType()).isEqualTo(ProviderActorType.AGENCY);
        assertThat(profile.getAgencyName()).isEqualTo("Qudsia Prime");
        assertThat(profile.getLicenseNumber()).isEqualTo("BR-1");
    }

    @Test
    void preL36UpdateForm_leavesThePersonaUntouched() {
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID(),
                ProviderActorType.AGENCY, "Qudsia Prime", "BR-1");

        profile.update("New Name", "New bio");

        assertThat(profile.getActorType()).isEqualTo(ProviderActorType.AGENCY);
        assertThat(profile.getAgencyName()).isEqualTo("Qudsia Prime");
        assertThat(profile.getLicenseNumber()).isEqualTo("BR-1");
    }
}
