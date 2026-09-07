package com.marketplace.ledger;

import com.marketplace.shared.security.AuthHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L20 authorization slice (roadmap §5 acceptance 3): the provider-facing
 * ledger reads are guarded by the unit ownership convention
 * {@code @authHelper.ownsProvider} — another provider's balance must be a
 * 403 (AccessDeniedException at the service seam), the owner's must read.
 * Same pattern as {@code AvailabilityServiceSecurityTest}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { LedgerService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class LedgerServiceSecurityTest {

    @Autowired
    private LedgerService ledgerService;

    @MockitoBean
    private LedgerEntryRepository entryRepository;

    @MockitoBean
    private ProviderBalanceRepository balanceRepository;

    @MockitoBean(name = "authHelper")
    private AuthHelper authHelper;

    @Test
    @WithMockUser(roles = "PROVIDER")
    void getBalanceForOwner_whenNotOwner_thenAccessDenied() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> ledgerService.getBalanceForOwner(providerId));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void getStatementForOwner_whenNotOwner_thenAccessDenied() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> ledgerService.getStatementForOwner(providerId, Pageable.ofSize(10)));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void getBalanceForOwner_whenOwner_thenInvokes() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(true);
        ProviderBalance existing = ProviderBalance.empty(providerId);
        existing.credit(4500L);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(existing));

        ProviderBalance result = ledgerService.getBalanceForOwner(providerId);

        assertThat(result.getAvailableCents()).isEqualTo(4500L);
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void getStatementForOwner_whenOwner_thenInvokes() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(true);
        when(entryRepository.findByProviderIdOrderByCreatedAtDesc(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<LedgerEntry> result = ledgerService.getStatementForOwner(providerId, Pageable.ofSize(10));

        assertThat(result).isNotNull();
        verify(entryRepository).findByProviderIdOrderByCreatedAtDesc(providerId, Pageable.ofSize(10));
    }
}
