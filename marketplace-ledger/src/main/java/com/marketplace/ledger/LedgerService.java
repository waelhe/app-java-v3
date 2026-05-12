package com.marketplace.ledger;

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

    public ProviderBalance creditFromPayment(UUID providerId, UUID paymentIntentId, long amountCents) {
        if (entryRepository.findBySourceId(paymentIntentId).isPresent()) {
            return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        }
        entryRepository.save(LedgerEntry.paymentCredit(providerId, paymentIntentId, amountCents));
        ProviderBalance balance = balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        balance.credit(amountCents);
        return balanceRepository.save(balance);
    }

    @Transactional(readOnly = true)
    public ProviderBalance getBalance(UUID providerId) {
        return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
    }
}
