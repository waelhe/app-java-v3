package com.marketplace.ledger;
import com.marketplace.shared.api.*;import org.springframework.modulith.events.ApplicationModuleListener;import org.springframework.stereotype.Component;
@Component public class LedgerDeliveryPaymentEventListener{
 private final LedgerJournalService journal;private final PaymentIntentLookupPort lookup;
 public LedgerDeliveryPaymentEventListener(LedgerJournalService j,PaymentIntentLookupPort l){journal=j;lookup=l;}
 @ApplicationModuleListener public void onPaymentStateChanged(PaymentStateChangedEvent e){
  if(!"COMPLETED".equals(e.state())&&!"REFUNDED".equals(e.state())&&!"PARTIALLY_REFUNDED".equals(e.state()))return;
  PaymentIntentDetails p=lookup.findById(e.paymentIntentId()).orElseThrow(()->new IllegalStateException("Payment event references an unknown intent"));
  if(!p.isDeliveryOrigin())return;
  if("COMPLETED".equals(e.state())){if(!"SUCCEEDED".equals(p.status()))throw new IllegalStateException("Completion event is not backed by successful delivery payment");journal.capture(p);}
  else{if(!"REFUNDED".equals(p.status())&&!"PARTIALLY_REFUNDED".equals(p.status()))throw new IllegalStateException("Refund event is not backed by refund state");journal.refund(p);}
 }
 @ApplicationModuleListener public void onDeliveryCompleted(DeliveryCompletedEvent e){journal.recognize(e);}
}
