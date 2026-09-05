package com.marketplace.ledger;

import io.micrometer.observation.annotation.Observed;
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
}
