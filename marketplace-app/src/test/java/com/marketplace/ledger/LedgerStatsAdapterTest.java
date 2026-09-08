package com.marketplace.ledger;

import com.marketplace.ledger.spi.LedgerStatsAdapter;
import com.marketplace.shared.api.LedgerStatsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * L25 (feature-expansion roadmap §5): the ledger module's stats-port
 * implementation is a read-only delegation with the exact window bounds —
 * the signed-sum semantics themselves run against real PostgreSQL in
 * {@code ProviderStatsIntegrationTest}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { LedgerStatsAdapter.class })
class LedgerStatsAdapterTest {

    @Autowired
    private LedgerStatsPort ledgerStatsPort;

    @MockitoBean
    private LedgerEntryRepository entryRepository;

    @Test
    void delegatesTheSignedWindowSum_verbatim() {
        UUID providerId = UUID.randomUUID();
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-01T00:00:00Z");
        when(entryRepository.sumNetCentsForProviderBetween(providerId, from, to)).thenReturn(6_000L);

        long net = ledgerStatsPort.findNetCentsForProviderBetween(providerId, from, to);

        assertThat(net).isEqualTo(6_000L);
        verify(entryRepository).sumNetCentsForProviderBetween(providerId, from, to);
        verifyNoMoreInteractions(entryRepository);
    }
}
