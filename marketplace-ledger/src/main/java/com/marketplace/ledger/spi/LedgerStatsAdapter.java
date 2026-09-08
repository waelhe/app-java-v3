package com.marketplace.ledger.spi;

import com.marketplace.ledger.LedgerEntryRepository;
import com.marketplace.shared.api.LedgerStatsPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * L25 (feature-expansion roadmap §5): the ledger module's implementation of
 * the {@link LedgerStatsPort} cross-module contract (the
 * {@code ReviewStatsAdapter} house pattern). The net computation lives here
 * because the ledger owns the entry types and their signs — the provider
 * module never learns how a credit, a commission debit and a refund debit
 * combine.
 */
@Component
@Transactional(readOnly = true)
public class LedgerStatsAdapter implements LedgerStatsPort {

    private final LedgerEntryRepository entryRepository;

    public LedgerStatsAdapter(LedgerEntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    @Override
    public long findNetCentsForProviderBetween(UUID providerId, Instant from, Instant to) {
        return entryRepository.sumNetCentsForProviderBetween(providerId, from, to);
    }
}
