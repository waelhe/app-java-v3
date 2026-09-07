package com.marketplace.ledger;

import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class LedgerService {

    private final LedgerEntryRepository entryRepository;
    private final ProviderBalanceRepository balanceRepository;

    public LedgerService(LedgerEntryRepository entryRepository, ProviderBalanceRepository balanceRepository) {
        this.entryRepository = entryRepository;
        this.balanceRepository = balanceRepository;
    }

    @Observed(name = "ledger.credit.payment")
    public ProviderBalance creditFromPayment(UUID providerId, UUID paymentIntentId, long amountCents) {
        if (entryRepository.findBySourceId(paymentIntentId).isPresent()) {
            return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        }
        entryRepository.save(LedgerEntry.paymentCredit(providerId, paymentIntentId, amountCents));
        ProviderBalance balance = balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        balance.credit(amountCents);
        return balanceRepository.save(balance);
    }

    @Observed(name = "ledger.debit.commission")
    public ProviderBalance debitFromCommission(UUID providerId, UUID paymentIntentId, long amountCents) {
        UUID sourceId = UUID.nameUUIDFromBytes(("commission-" + paymentIntentId.toString()).getBytes());
        if (entryRepository.findBySourceId(sourceId).isPresent()) {
            return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        }
        entryRepository.save(LedgerEntry.commissionDebit(providerId, sourceId, amountCents));
        ProviderBalance balance = balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        balance.debit(amountCents);
        return balanceRepository.save(balance);
    }

    @Transactional(readOnly = true)
    public ProviderBalance getBalance(UUID providerId) {
        return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
    }

    /**
     * Provider-facing balance read (L20): the same number the ADMIN endpoint
     * returns, guarded by the unit's ownership convention —
     * {@code @authHelper.ownsProvider} exactly like
     * {@code AvailabilityService#createSlot}. The ADMIN surface stays
     * unchanged (the existing endpoints are the admin's way in); this method
     * is the provider's.
     */
    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    @Transactional(readOnly = true)
    public ProviderBalance getBalanceForOwner(UUID providerId) {
        return getBalance(providerId);
    }

    /**
     * Provider statement (L20): newest-first ledger movement page for the
     * owning provider only. Pagination is the caller's {@link Pageable}
     * (house {@code PagedResponse} contract).
     */
    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    @Transactional(readOnly = true)
    public Page<LedgerEntry> getStatementForOwner(UUID providerId, Pageable pageable) {
        return entryRepository.findByProviderIdOrderByCreatedAtDesc(providerId, pageable);
    }
}
