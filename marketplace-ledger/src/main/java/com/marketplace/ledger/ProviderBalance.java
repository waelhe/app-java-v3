package com.marketplace.ledger;

import com.marketplace.shared.api.Currencies;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "provider_balances")
@Audited
public class ProviderBalance extends BaseEntity {

    /**
     * R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's currency):
     * the composite balance key — one row per (provider, ISO 4217 currency).
     * The pre-fix single {@code provider_id} key aggregated DIFFERENT
     * currencies into one number (the defect: 100 SAR + 100 USD read as
     * 200 units); V75 widened the table's primary key the same way. Mapping
     * shape: {@code @EmbeddedId} over a Java record embeddable (Jakarta
     * Persistence §2.4.1 composite keys; Spring Data JPA reference
     * "Composite keys" — {@code findById} now takes this id), verified
     * against the reactor's Hibernate 7.4.5.
     */
    @Embeddable
    public record ProviderBalanceId(
            @Column(name = "provider_id") UUID providerId,
            @Column(name = "currency", length = 3) String currency) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        public ProviderBalanceId {
            currency = Currencies.normalizeOrDefault(currency, Currencies.DEFAULT_CODE);
        }
    }

    @EmbeddedId
    private ProviderBalanceId id;

    @Column(name = "available_cents", nullable = false)
    private long availableCents;

    protected ProviderBalance() {}

    private ProviderBalance(ProviderBalanceId id, long availableCents) {
        this.id = id;
        this.availableCents = availableCents;
    }

    public static ProviderBalance empty(UUID providerId, String currency) {
        return new ProviderBalance(new ProviderBalanceId(providerId, currency), 0);
    }

    /** {@inheritDoc} — the entity's id is the composite (provider, currency) key. */
    @Override public UUID getId(){ return id == null ? null : id.providerId(); }
    public ProviderBalanceId getKey(){ return id; }
    public UUID getProviderId(){ return id == null ? null : id.providerId(); }
    public String getCurrency(){ return id == null ? null : id.currency(); }
    public long getAvailableCents(){return availableCents;}
    /**
     * Checked arithmetic (CodeRabbit agent findings, row 22 / CWE-190):
     * a credit must never wrap the signed balance — an overflow aborts the
     * entry-and-balance transaction (the ArithmeticException propagates,
     * the caller's transaction rolls back) instead of materializing a
     * wrapped value the ledger would then persist as truth.
     */
    public void credit(long amountCents){ this.availableCents = Math.addExact(this.availableCents, amountCents); }
    /**
     * Checked arithmetic (CodeRabbit agent findings, row 22 / CWE-190):
     * provider-selected ad prices could accumulate debits large enough to
     * wrap an unfunded negative balance into a large POSITIVE one —
     * {@link Math#subtractExact} makes the wrap an ArithmeticException
     * that aborts the entry-and-balance transaction, so the balance is
     * either honestly debited or honestly refused, never silently wrapped.
     */
    public void debit(long amountCents){ this.availableCents = Math.subtractExact(this.availableCents, amountCents); }
}
