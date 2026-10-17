package com.marketplace.payments.spi;
import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentsService;
import com.marketplace.shared.api.DeliveryPaymentPort;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
/** Payments-owned adapter; direct delivery uses only the shared port. */
@Component @Transactional public class DeliveryPaymentAdapter implements DeliveryPaymentPort {
 private final PaymentsService service; private final PaymentIntentLookupPort lookup;
 public DeliveryPaymentAdapter(PaymentsService service,PaymentIntentLookupPort lookup){this.service=service;this.lookup=lookup;}
 @Override public PaymentIntentDetails createForDelivery(UUID requestId,UUID requesterId,long amountMinor,String currency){
  PaymentIntent p=service.createDeliveryIntent(requestId,requesterId,amountMinor,currency);
  return lookup.findById(p.getId()).orElseThrow(()->new ResourceNotFoundException("Created delivery payment intent is unavailable"));}
 @Override public void cancelOrRefund(UUID requestId){service.autoRefundByDelivery(requestId);}
}
