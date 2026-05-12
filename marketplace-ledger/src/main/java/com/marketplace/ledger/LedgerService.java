package com.marketplace.ledger;

import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class LedgerService {
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ProviderBalanceRepository providerBalanceRepository;
    private final PayoutRepository payoutRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository, ProviderBalanceRepository providerBalanceRepository, PayoutRepository payoutRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.providerBalanceRepository = providerBalanceRepository;
        this.payoutRepository = payoutRepository;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<LedgerEntry> list(Pageable pageable) { return ledgerEntryRepository.findAll(pageable); }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ProviderBalance balance(UUID providerId) { return providerBalanceRepository.findByProviderId(providerId).orElseGet(() -> ProviderBalance.create(providerId)); }

    @PreAuthorize("hasRole('ADMIN')")
    public Payout createPayout(UUID providerId, Long amountCents) {
        ProviderBalance balance = providerBalanceRepository.findByProviderId(providerId).orElseGet(() -> providerBalanceRepository.save(ProviderBalance.create(providerId)));
        if (balance.getAvailableCents() < amountCents) { throw new IllegalStateException("Insufficient provider balance"); }
        balance.apply(-amountCents);
        ledgerEntryRepository.save(LedgerEntry.create(providerId, providerId, LedgerEntryType.PAYOUT, -amountCents));
        return payoutRepository.save(Payout.create(providerId, amountCents));
    }

    @EventListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        if (!"SUCCEEDED".equals(event.state()) || ledgerEntryRepository.existsBySourceIdAndType(event.paymentIntentId(), LedgerEntryType.BOOKING_PAYMENT)) {
            return;
        }
        ledgerEntryRepository.save(LedgerEntry.create(null, event.paymentIntentId(), LedgerEntryType.BOOKING_PAYMENT, 0L));
    }
}
