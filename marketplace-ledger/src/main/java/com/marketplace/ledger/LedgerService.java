package com.marketplace.ledger;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.Currencies;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
     *
     * <p><b>R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's
     * currency):</b> the credit carries the payment's ISO 4217 currency and
     * moves the {@code (provider, currency)} balance of THAT currency — the
     * pre-fix single-key balance aggregated different currencies into one
     * number (100 SAR + 100 USD read as 200 units; the booking side carried
     * the currency all along and the listener ignored it). The entry stores
     * the currency beside the amount; the returned balance is the touched
     * currency's own row.</p>
     */
    @Observed(name = "ledger.credit.payment")
    public ProviderBalanceResponse creditFromPayment(UUID providerId, UUID paymentIntentId,
                                                     long amountCents, String currency) {
        String normalized = Currencies.normalizeOrDefault(currency, Currencies.DEFAULT_CODE);
        requireNonNegativeAmount(amountCents);
        if (amountCents == 0) {
            return ProviderBalanceResponse.from(
                    balanceOf(providerId, normalized));
        }
        if (entryRepository.findBySourceId(paymentIntentId).isPresent()) {
            return ProviderBalanceResponse.from(
                    balanceOf(providerId, normalized));
        }
        entryRepository.save(LedgerEntry.paymentCredit(providerId, paymentIntentId, amountCents, normalized));
        ProviderBalance balance = balanceOf(providerId, normalized);
        balance.credit(amountCents);
        return ProviderBalanceResponse.from(balanceRepository.save(balance));
    }

    /**
     * L24 (money path 2 of 3): debits the platform commission from the
     * provider's balance — the deterministic
     * {@code nameUUIDFromBytes("commission-<intentId>")} source id makes
     * replayed listeners no-ops (the debit lands exactly once per intent).
     * B2 amount guard: negative amounts are rejected with VALIDATION (400);
     * zero amounts return the current balance without writing an entry.
     *
     * <p><b>R9:</b> the commission is debited in the payment's own currency
     * — the same currency the credit wrote — so each payment's fee moves
     * that payment's balance (the plan's own wording).</p>
     */
    @Observed(name = "ledger.debit.commission")
    public ProviderBalance debitFromCommission(UUID providerId, UUID paymentIntentId,
                                               long amountCents, String currency) {
        String normalized = Currencies.normalizeOrDefault(currency, Currencies.DEFAULT_CODE);
        requireNonNegativeAmount(amountCents);
        if (amountCents == 0) {
            return balanceOf(providerId, normalized);
        }
        UUID sourceId = UUID.nameUUIDFromBytes(("commission-" + paymentIntentId.toString()).getBytes());
        if (entryRepository.findBySourceId(sourceId).isPresent()) {
            return balanceOf(providerId, normalized);
        }
        entryRepository.save(LedgerEntry.commissionDebit(providerId, sourceId, amountCents, normalized));
        ProviderBalance balance = balanceOf(providerId, normalized);
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
     *
     * <p><b>R9:</b> the refund mirrors the ORIGINAL credit's currency — the
     * debit lands on the same {@code (provider, currency)} balance the
     * credit moved.</p>
     *
     * <p><b>Concurrent-delivery contract (CodeRabbit #252, adopted):</b> the
     * pre-check above is an optimization, not the guarantee — the guarantee
     * is the {@code UNIQUE} backstop on {@code source_id}. Two deliveries
     * racing past the pre-check collide on the backstop; the loser's
     * transaction rolls back <em>in full</em> (entry and balance move
     * together, so no partial debit can survive), the publication is marked
     * FAILED, and the documented resubmission loop (#210) replays into the
     * pre-check no-op. Catch-and-continue inside the same transaction is
     * deliberately NOT used: after a flush-time constraint violation the
     * persistence context must roll back (Spring Framework DAO Support:
     * technology exceptions are translated to the {@code DataAccessException}
     * hierarchy and non-recoverable persistence failures belong to the
     * transaction boundary, not to in-transaction recovery).</p>
     */
    @Observed(name = "ledger.debit.refund")
    public ProviderBalance debitFromRefund(UUID providerId, UUID paymentIntentId,
                                           long amountCents, String currency) {
        String normalized = Currencies.normalizeOrDefault(currency, Currencies.DEFAULT_CODE);
        requireNonNegativeAmount(amountCents);
        if (amountCents == 0) {
            return balanceOf(providerId, normalized);
        }
        UUID sourceId = UUID.nameUUIDFromBytes(("refund-" + paymentIntentId.toString()).getBytes());
        if (entryRepository.findBySourceId(sourceId).isPresent()) {
            return balanceOf(providerId, normalized);
        }
        entryRepository.save(LedgerEntry.refundDebit(providerId, sourceId, amountCents, normalized));
        ProviderBalance balance = balanceOf(providerId, normalized);
        balance.debit(amountCents);
        return balanceRepository.save(balance);
    }

    /**
     * R9: the provider's balances — one row per currency he holds (ordered
     * by currency), the honest multi-currency read. An empty list is the
     * valid answer for a provider with no ledger history: the money paths
     * materialize a row only when an entry moves a currency.
     */
    @Transactional(readOnly = true)
    public List<ProviderBalanceResponse> getBalances(UUID providerId) {
        return balanceRepository.findByProviderIdOrderByCurrencyAsc(providerId).stream()
                .map(ProviderBalanceResponse::from)
                .toList();
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
     * R9: the {@code (provider, currency)} balance — the live row or the
     * empty projection for a currency the provider does not hold yet (the
     * read-only answer the money paths' zero/idempotent paths return; the
     * writing paths save it after moving the amount).
     */
    private ProviderBalance balanceOf(UUID providerId, String currency) {
        return balanceRepository
                .findById(new ProviderBalance.ProviderBalanceId(providerId, currency))
                .orElseGet(() -> ProviderBalance.empty(providerId, currency));
    }

    /**
     * Provider-facing balance read (L20): the same rows the ADMIN endpoint
     * returns, guarded by the unit's ownership convention —
     * {@code @authHelper.ownsProvider} exactly like
     * {@code AvailabilityService#createSlot}. The ADMIN surface stays
     * unchanged (the existing endpoints are the admin's way in); this method
     * is the provider's.
     */
    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    @Transactional(readOnly = true)
    public List<ProviderBalanceResponse> getBalancesForOwner(UUID providerId) {
        return getBalances(providerId);
    }

    /**
     * Provider statement (L20): newest-first ledger movement page for the
     * owning provider only. Pagination is the caller's {@link Pageable}
     * (house {@code PagedResponse} contract). R9: each movement carries its
     * currency.
     */
    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    @Transactional(readOnly = true)
    public Page<LedgerEntry> getStatementForOwner(UUID providerId, Pageable pageable) {
        return entryRepository.findByProviderIdOrderByCreatedAtDescIdDesc(providerId, pageable);
    }
}
