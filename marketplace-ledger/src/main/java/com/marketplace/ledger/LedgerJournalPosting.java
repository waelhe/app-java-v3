package com.marketplace.ledger;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import java.util.UUID;

@Entity
@Table(name = "ledger_journal_postings")
@Audited
public class LedgerJournalPosting extends BaseEntity {
    @Id private UUID id;
    @Column(name="source_type",nullable=false,length=24) private String sourceType;
    @Column(name="source_id",nullable=false) private UUID sourceId;
    @Column(name="payment_intent_id",nullable=false) private UUID paymentIntentId;
    @Column(name="operation_key",nullable=false,length=80) private String operationKey;
    @Column(name="debit_account",nullable=false,length=40) private String debitAccount;
    @Column(name="credit_account",nullable=false,length=40) private String creditAccount;
    @Column(name="amount_minor",nullable=false) private long amountMinor;
    @Column(name="currency",nullable=false,length=3) private String currency;
    @Column(name="cumulative_refund_minor") private Long cumulativeRefundMinor;
    @Column(name="recipient_id") private UUID recipientId;
    @Column(name="memo",nullable=false,length=500) private String memo;
    protected LedgerJournalPosting() { }
    public LedgerJournalPosting(UUID id,String type,UUID source,UUID payment,String operation,String debit,
                                String credit,long amount,String currency,Long cumulative,String memo) {
        this(id,type,source,payment,operation,debit,credit,amount,currency,cumulative,null,memo);
    }
    public LedgerJournalPosting(UUID id,String type,UUID source,UUID payment,String operation,String debit,
                                String credit,long amount,String currency,Long cumulative,UUID recipientId,String memo) {
        this.id=id;sourceType=type;sourceId=source;paymentIntentId=payment;operationKey=operation;
        debitAccount=debit;creditAccount=credit;amountMinor=amount;this.currency=currency;
        cumulativeRefundMinor=cumulative;this.recipientId=recipientId;this.memo=memo;
    }
    @Override public UUID getId(){return id;}
    public String getSourceType(){return sourceType;}
    public UUID getSourceId(){return sourceId;}
    public UUID getPaymentIntentId(){return paymentIntentId;}
    public String getOperationKey(){return operationKey;}
    public String getDebitAccount(){return debitAccount;}
    public String getCreditAccount(){return creditAccount;}
    public long getAmountMinor(){return amountMinor;}
    public String getCurrency(){return currency;}
    public Long getCumulativeRefundMinor(){return cumulativeRefundMinor;}
    public UUID getRecipientId(){return recipientId;}
}
