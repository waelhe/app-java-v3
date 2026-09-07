package com.marketplace.ledger;

import com.marketplace.shared.api.BadRequestException;
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

    /**
     * L24 (money path 1 of 3): credits the provider's balance when a payment
     * is captured — writes exactly one {@code PAYMENT_CREDIT} entry per
     * payment intent (idempotent by source id). B2 amount guard: a negative
     * amount is rejected with VALIDATION (400); a zero amount is a no-op that
     * returns the current balance without writing a zero-impact entry.
     */
    @Observed(name = "ledger.credit.payment")
    public ProviderBalance creditFromPayment(UUID providerId, UUID paymentIntentId, long amountCents) {
        requireNonNegativeAmount(amountCents);
        if (amountCents == 0) {
            return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        }
        if (entryRepository.findBySourceId(paymentIntentId).isPresent()) {
            return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        }
        entryRepository.save(LedgerEntry.paymentCredit(providerId, paymentIntentId, amountCents));
        ProviderBalance balance = balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        balance.credit(amountCents);
        return balanceRepository.save(balance);
    }

    /**
     * L24 (money path 2 of 3): debits the platform commission from the
     * provider's balance — the deterministic
     * {@code nameUUIDFromBytes("commission-<intentId>")} source id makes
     * replayed listeners no-ops (the debit lands exactly once per intent).
     * B2 amount guard: negative amounts are rejected with VALIDATION (400);
     * zero amounts return the current balance without writing an entry.
     */
    @Observed(name = "ledger.debit.commission")
    public ProviderBalance debitFromCommission(UUID providerId, UUID paymentIntentId, long amountCents) {
        requireNonNegativeAmount(amountCents);
        if (amountCents == 0) {
            return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        }
        UUID sourceId = UUID.nameUUIDFromBytes(("commission-" + paymentIntentId.toString()).getBytes());
        if (entryRepository.findBySourceId(sourceId).isPresent()) {
            return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        }
        entryRepository.save(LedgerEntry.commissionDebit(providerId, sourceId, amountCents));
        ProviderBalance balance = balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        balance.debit(amountCents);
        return balanceRepository.save(balance);
    }

    /**
     * L24 (feature-expansion roadmap §5): the full refund's debit — mirrors
     * the original PAYMENT_CREDIT on the provider's balance. Same idempotent
     * shape as the credit: the derived {@code refund-<intentId>} source id
     * makes the AFTER_COMMIT listener replays no-ops, so the debit lands
     * exactly once per refunded payment intent (a second delivery, a
     * concurrent listener, or a repeated decision can never double-debit).
     * B2 amount guard: negative amounts are rejected with VALIDATION (400);
     * zero amounts return the current balance without writing an entry.
     */
    @Observed(name = "ledger.debit.refund")
    public ProviderBalance debitFromRefund(UUID providerId, UUID paymentIntentId, long amountCents) {
        requireNonNegativeAmount(amountCents);
        if (amountCents == 0) {
            return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        }
        UUID sourceId = UUID.nameUUIDFromBytes(("refund-" + paymentIntentId.toString()).getBytes());
        if (entryRepository.findBySourceId(sourceId).isPresent()) {
            return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        }
        entryRepository.save(LedgerEntry.refundDebit(providerId, sourceId, amountCents));
        ProviderBalance balance = balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
        balance.debit(amountCents);
        return balanceRepository.save(balance);
    }

    @Transactional(readOnly = true)
    public ProviderBalance getBalance(UUID providerId) {
        return balanceRepository.findById(providerId).orElseGet(() -> ProviderBalance.empty(providerId));
    }

    /**
     * B2 (financial integrity): shared precondition for all three money
     * paths — ledger amounts are non-negative cents by contract; a negative
     * value fails fast with VALIDATION (400) before any entry is written.
     *
     * @param amountCents the ledger amount in cents, must be {@code >= 0}
     * @throws BadRequestException when {@code amountCents} is negative
     */
    private void requireNonNegativeAmount(long amountCents) {
        if (amountCents < 0) {
            throw new BadRequestException("Ledger amount must not be negative: " + amountCents + " cents");
        }
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
        return entryRepository.findByProviderIdOrderByCreatedAtDescIdDesc(providerId, pageable);
    }
}
