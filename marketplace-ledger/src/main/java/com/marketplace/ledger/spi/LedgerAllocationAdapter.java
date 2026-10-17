package com.marketplace.ledger.spi;

import com.marketplace.ledger.LedgerEntry;
import com.marketplace.ledger.LedgerEntryRepository;
import com.marketplace.ledger.LedgerEntryType;
import com.marketplace.ledger.ProviderBalance;
import com.marketplace.ledger.ProviderBalanceRepository;
import com.marketplace.shared.api.CurrencyAmount;
import com.marketplace.shared.api.LedgerAllocationPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The unified finance-operator read model's LEDGER side (payments plan
 * Phase 1): the port's implementation, reading exactly the three
 * deterministic postings the ledger's own writer paths key — the same
 * derivations {@code LedgerService.creditFromPayment},
 * {@code debitFromCommission} and {@code debitFromRefund} construct —
 * plus the current balances of the providers those postings moved.
 *
 * <p><b>Read-only and derived:</b> this adapter re-derives nothing and
 * mutates nothing; it answers what the writer paths already keyed. The
 * {@code AD_DEBIT} shape is intentionally not probed here (the port's
 * own record: an ad debit is the bill a campaign window consumes, keyed
 * by {@code (campaign, window)} — never by an intent).
 */
@Component
public class LedgerAllocationAdapter implements LedgerAllocationPort {

    private final LedgerEntryRepository entryRepository;
    private final ProviderBalanceRepository balanceRepository;

    public LedgerAllocationAdapter(LedgerEntryRepository entryRepository,
                                   ProviderBalanceRepository balanceRepository) {
        this.entryRepository = entryRepository;
        this.balanceRepository = balanceRepository;
    }

    /**
     * One read transaction over the two owned tables — entries first
     * (the derivation keys are pure functions of the intent id), then
     * the balances of exactly the providers and currencies the found
     * entries name. An intent with no postings answers the honest empty
     * allocation.
     */
    @Override
    @Transactional(readOnly = true)
    public LedgerAllocation findByPaymentIntentId(UUID paymentIntentId) {
        List<LedgerAllocationEntry> entries = new ArrayList<>();
        entryRepository.findBySourceId(paymentIntentId)
                .ifPresent(credit -> entries.add(toFact(credit)));
        entryRepository.findBySourceId(commissionSourceKey(paymentIntentId))
                .ifPresent(commission -> entries.add(toFact(commission)));
        entryRepository.findBySourceId(refundSourceKey(paymentIntentId))
                .ifPresent(refund -> entries.add(toFact(refund)));
        if (entries.isEmpty()) {
            return LedgerAllocation.empty();
        }

        Map<UUID, List<String>> providersAndCurrencies = new LinkedHashMap<>();
        for (LedgerAllocationEntry entry : entries) {
            providersAndCurrencies
                    .computeIfAbsent(entry.providerId(), unused -> new ArrayList<>())
                    .add(entry.currency());
        }
        List<ProviderBalanceFact> balances = new ArrayList<>();
        for (Map.Entry<UUID, List<String>> provider : providersAndCurrencies.entrySet()) {
            for (ProviderBalance balance
                    : balanceRepository.findByIdProviderIdOrderByIdCurrencyAsc(provider.getKey())) {
                balances.add(new ProviderBalanceFact(
                        balance.getProviderId(), balance.getCurrency(), balance.getAvailableCents()));
            }
        }

        Map<String, long[]> netByCurrency = new LinkedHashMap<>();
        for (LedgerAllocationEntry entry : entries) {
            long[] net = netByCurrency.computeIfAbsent(entry.currency(),
                    unused -> new long[]{0L});
            net[0] += signedAmount(entry);
        }
        List<CurrencyAmount> netAllocation = netByCurrency.entrySet().stream()
                .map(currencyNet -> new CurrencyAmount(currencyNet.getKey(), currencyNet.getValue()[0]))
                .toList();

        return new LedgerAllocation(List.copyOf(entries), List.copyOf(balances), netAllocation);
    }

    private LedgerAllocationEntry toFact(LedgerEntry entry) {
        return new LedgerAllocationEntry(
                entry.getEntryType().name(),
                entry.getAmountCents(),
                entry.getCurrency(),
                entry.getProviderId(),
                entry.getCreatedAt());
    }

    /** The ledger's own sign convention, applied read-only. */
    private long signedAmount(LedgerAllocationEntry entry) {
        return entry.entryType().equals(LedgerEntryType.PAYMENT_CREDIT.name())
                ? entry.amountCents()
                : -entry.amountCents();
    }

    /** {@code LedgerService.debitFromCommission}'s derivation, verbatim. */
    static UUID commissionSourceKey(UUID paymentIntentId) {
        return UUID.nameUUIDFromBytes(("commission-" + paymentIntentId.toString()).getBytes());
    }

    /** {@code LedgerService.debitFromRefund}'s derivation, verbatim. */
    static UUID refundSourceKey(UUID paymentIntentId) {
        return UUID.nameUUIDFromBytes(("refund-" + paymentIntentId.toString()).getBytes());
    }
}
