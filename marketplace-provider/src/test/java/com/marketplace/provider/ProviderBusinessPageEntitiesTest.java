package com.marketplace.provider;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W2 (yelp-level plan §5 — the business page): the business-page blocks'
 * entity guards — the validation laws the V88/V89 CHECKs enforce at the
 * database level hold at the entity boundary too (fail-loud at
 * construction, the house rule), and the verification lifecycle's
 * transition law matches the V78 four-state shape.
 */
class ProviderBusinessPageEntitiesTest {

    private static final UUID PROVIDER_ID = UUID.randomUUID();

    // -- BusinessHour (G11) ---------------------------------------------------

    @Test
    void businessHour_rejectsAWindowThatDoesNotOpenBeforeItCloses() {
        assertThatThrownBy(() -> BusinessHour.create(PROVIDER_ID, DayOfWeek.MONDAY,
                LocalTime.parse("17:00"), LocalTime.parse("09:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly before");
        assertThatThrownBy(() -> BusinessHour.create(PROVIDER_ID, DayOfWeek.MONDAY,
                LocalTime.parse("09:00"), LocalTime.parse("09:00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void businessHour_carriesTheIsoWeekdayNumbering() {
        BusinessHour monday = BusinessHour.create(PROVIDER_ID, DayOfWeek.MONDAY,
                LocalTime.parse("09:00"), LocalTime.parse("17:00"));
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        BusinessHour sunday = BusinessHour.create(PROVIDER_ID, DayOfWeek.SUNDAY,
                LocalTime.parse("09:00"), LocalTime.parse("17:00"));
        assertThat(sunday.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
    }

    @Test
    void businessHour_update_movesTheWindowAsAWhole() {
        BusinessHour hour = BusinessHour.create(PROVIDER_ID, DayOfWeek.MONDAY,
                LocalTime.parse("09:00"), LocalTime.parse("17:00"));
        hour.update(DayOfWeek.TUESDAY, LocalTime.parse("10:00"), LocalTime.parse("16:00"));
        assertThat(hour.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(hour.getOpensAt()).isEqualTo(LocalTime.parse("10:00"));
        assertThat(hour.getClosesAt()).isEqualTo(LocalTime.parse("16:00"));
    }

    // -- OfferedService (G12) -------------------------------------------------

    @Test
    void offeredService_moneyPairMustBeDeclaredTogether() {
        assertThatThrownBy(() -> OfferedService.create(PROVIDER_ID, "تنظيف عميق", null,
                null, 15_000L, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
        assertThatThrownBy(() -> OfferedService.create(PROVIDER_ID, "تنظيف عميق", null,
                null, null, "SAR", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void offeredService_currencyMustBeIso4217() {
        assertThatThrownBy(() -> OfferedService.create(PROVIDER_ID, "تنظيف عميق", null,
                null, 15_000L, "sar", 0))
                .isInstanceOf(IllegalArgumentException.class);
        OfferedService valid = OfferedService.create(PROVIDER_ID, "تنظيف عميق", "شامل",
                120, 15_000L, "SAR", 0);
        assertThat(valid.getPriceCents()).isEqualTo(15_000L);
        assertThat(valid.getCurrency()).isEqualTo("SAR");
        assertThat(valid.getDurationMinutes()).isEqualTo(120);
        assertThat(valid.getPosition()).isZero();
    }

    @Test
    void offeredService_negativePriceOrDurationOrPosition_rejectedLoudly() {
        assertThatThrownBy(() -> OfferedService.create(PROVIDER_ID, "t", null, -5, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfferedService.create(PROVIDER_ID, "t", null, null, -1L, "SAR", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfferedService.create(PROVIDER_ID, "t", null, null, null, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- ProviderVerificationState (G14) ---------------------------------------

    @Test
    void verificationState_followsTheV78TransitionLaw() {
        // The submission path from every non-pending state.
        assertThat(canMove(ProviderVerificationState.UNVERIFIED, ProviderVerificationState.PENDING)).isTrue();
        assertThat(canMove(ProviderVerificationState.REJECTED, ProviderVerificationState.PENDING)).isTrue();
        assertThat(canMove(ProviderVerificationState.VERIFIED, ProviderVerificationState.PENDING)).isTrue();
        // The resolution pair from PENDING.
        assertThat(canMove(ProviderVerificationState.PENDING, ProviderVerificationState.VERIFIED)).isTrue();
        assertThat(canMove(ProviderVerificationState.PENDING, ProviderVerificationState.REJECTED)).isTrue();
        // The forbidden shortcuts: nobody jumps straight to VERIFIED.
        assertThat(canMove(ProviderVerificationState.UNVERIFIED, ProviderVerificationState.VERIFIED)).isFalse();
        assertThat(canMove(ProviderVerificationState.REJECTED, ProviderVerificationState.VERIFIED)).isFalse();
        // UNVERIFIED is terminal-from (only submission leaves it).
        assertThat(canMove(ProviderVerificationState.UNVERIFIED, ProviderVerificationState.REJECTED)).isFalse();
        // No self-loops.
        assertThat(canMove(ProviderVerificationState.PENDING, ProviderVerificationState.PENDING)).isFalse();
    }

    private boolean canMove(ProviderVerificationState from, ProviderVerificationState to) {
        try {
            from.validateTransitionTo(to);
            return true;
        } catch (IllegalStateException rejected) {
            return false;
        }
    }

    @Test
    void verificationTransitions_onTheProfile_followTheSameLaw() {
        ProviderProfile profile = ProviderProfile.create("اسم", null, UUID.randomUUID());
        assertThat(profile.getVerificationState()).isEqualTo(ProviderVerificationState.UNVERIFIED);

        profile.submitForVerification();
        assertThat(profile.getVerificationState()).isEqualTo(ProviderVerificationState.PENDING);

        profile.confirmVerification();
        assertThat(profile.getVerificationState()).isEqualTo(ProviderVerificationState.VERIFIED);

        // Re-verification after ownership change: VERIFIED → PENDING again.
        profile.submitForVerification();
        assertThat(profile.getVerificationState()).isEqualTo(ProviderVerificationState.PENDING);

        profile.rejectVerification();
        assertThat(profile.getVerificationState()).isEqualTo(ProviderVerificationState.REJECTED);

        // The owner's corrected resubmission.
        profile.submitForVerification();
        assertThat(profile.getVerificationState()).isEqualTo(ProviderVerificationState.PENDING);
    }

    @Test
    void verificationTransition_outOfLaw_failsLoudly() {
        ProviderProfile profile = ProviderProfile.create("اسم", null, UUID.randomUUID());
        assertThatThrownBy(profile::confirmVerification)
                .isInstanceOf(IllegalStateException.class);
    }

    // -- ServiceArea (G13) -----------------------------------------------------

    @Test
    void serviceArea_requiresBothIds() {
        assertThatThrownBy(() -> ServiceArea.create(null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceArea.create(UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
        ServiceArea area = ServiceArea.create(PROVIDER_ID, UUID.randomUUID());
        assertThat(area.getProviderId()).isEqualTo(PROVIDER_ID);
        assertThat(area.getLocationId()).isNotNull();
    }
}
