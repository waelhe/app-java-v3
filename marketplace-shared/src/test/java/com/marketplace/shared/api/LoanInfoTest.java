package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * The {@link LoanInfo#requireParty} gate — the ADR-0009 dispute seam's
 * party check, measured in-module the same way {@code BookingInfo}'s
 * {@code requireParticipant} is (the 05bda28 coverage discipline: real
 * logic in shared/api is tested here, not excluded).
 */
class LoanInfoTest {

    private final UUID borrowerId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @Test
    void requireParty_throwsForNonParty() {
        var info = new LoanInfo(UUID.randomUUID(), borrowerId, ownerId);
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> info.requireParty(stranger))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not a party");
    }

    @Test
    void requireParty_success_asBorrower() {
        var info = new LoanInfo(UUID.randomUUID(), borrowerId, ownerId);
        info.requireParty(borrowerId);
    }

    @Test
    void requireParty_success_asOwner() {
        var info = new LoanInfo(UUID.randomUUID(), borrowerId, ownerId);
        info.requireParty(ownerId);
    }

    @Test
    void recordCarriesTheThreeIdentifiers() {
        UUID loanId = UUID.randomUUID();
        var info = new LoanInfo(loanId, borrowerId, ownerId);
        assertThat(info.loanId()).isEqualTo(loanId);
        assertThat(info.borrowerId()).isEqualTo(borrowerId);
        assertThat(info.ownerId()).isEqualTo(ownerId);
    }
}
