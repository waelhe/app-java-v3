package com.marketplace.ledger.spi;

import com.marketplace.ledger.LedgerEntry;
import com.marketplace.ledger.LedgerEntryRepository;
import com.marketplace.ledger.LedgerEntryType;
import com.marketplace.ledger.ProviderBalance;
import com.marketplace.ledger.ProviderBalanceRepository;
import com.marketplace.shared.api.LedgerAllocationPort;
import com.marketplace.shared.api.LedgerReadPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * W11 (the origin-state matrix's G5 closure — ADR-0013 D3): the
 * LEDGER side of the settlement-eligibility operational read — the
 * {@link LedgerReadPort} implementation, answering exactly what the
 * ledger's own writer paths already keyed.
 *
 * <p><b>Read-only and derived:</b> this adapter re-derives nothing and
 * mutates nothing. The credits are the {@code PAYMENT_CREDIT} entries the
 * completion listener wrote (idempotent by the intent id itself); the
 * balances are the live {@code (provider, currency)} rows the money paths
 * maintain. The ELIGIBILITY law (which credit's owning domain reached its
 * proper end) is not the ledger's to derive — it lives in the payments
 * module, which owns the intent facts; this port just hands over the
 * ledger's own truth (the {@code LedgerAllocationAdapter} twin for the
 * G5 read).
 */
@Component
public class LedgerReadAdapter implements LedgerReadPort {

    private final LedgerEntryRepository entryRepository;
    private final ProviderBalanceRepository balanceRepository;

    public LedgerReadAdapter(LedgerEntryRepository entryRepository,
                             ProviderBalanceRepository balanceRepository) {
        this.entryRepository = entryRepository;
        this.balanceRepository = balanceRepository;
    }

    /**
     * One read transaction over the owned entries table: the provider's
     * settled credits, newest first with the id-DESC tiebreak (the
     * repository method's own ordering law). A provider with no collected
     * money answers the honest empty list.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SettlementCredit> findCreditsByProvider(UUID providerId) {
        return entryRepository
                .findByProviderIdAndEntryTypeOrderByCreatedAtDescIdDesc(providerId, LedgerEntryType.PAYMENT_CREDIT)
                .stream()
                .map(LedgerReadAdapter::toCredit)
                .toList();
    }

    /**
     * The provider's current balances, per currency — the same rows the
     * {@code LedgerService.getBalances} read surfaces (the composite-key
     * property traversal documented on the repository), shaped as the
     * shared {@link LedgerAllocationPort.ProviderBalanceFact} — ONE shape
     * for one fact across the two finance-operator ports.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LedgerAllocationPort.ProviderBalanceFact> findBalancesByProvider(UUID providerId) {
        return balanceRepository.findByIdProviderIdOrderByIdCurrencyAsc(providerId).stream()
                .map(LedgerReadAdapter::toBalanceFact)
                .toList();
    }

    private static SettlementCredit toCredit(LedgerEntry entry) {
        return new SettlementCredit(
                entry.getProviderId(),
                entry.getSourceId(),
                entry.getAmountCents(),
                entry.getCurrency(),
                entry.getCreatedAt());
    }

    private static LedgerAllocationPort.ProviderBalanceFact toBalanceFact(ProviderBalance balance) {
        return new LedgerAllocationPort.ProviderBalanceFact(
                balance.getProviderId(), balance.getCurrency(), balance.getAvailableCents());
    }
}
