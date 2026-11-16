package com.marketplace.ledger;
import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
@ExtendWith(MockitoExtension.class) class LedgerJournalServiceTest{
 @Mock LedgerJournalPostingRepository postings;
 @Test void captureCreatesOneBalancedPairAndReplayIsIdempotent(){
  var service=new LedgerJournalService(postings);UUID request=UUID.randomUUID(),payment=UUID.randomUUID();
  var intent=new PaymentIntentDetails(payment,null,UUID.randomUUID(),null,"SUCCEEDED","DELIVERY",1234,"SAR",null,null,null,request,0L);
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.empty(),Optional.of(new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"CAPTURE","PSP_CLEARING","DELIVERY_CUSTOMER_ADVANCES",1234,"SAR",null,"test")));
  service.capture(intent);service.capture(intent);
  ArgumentCaptor<LedgerJournalPosting> saved=ArgumentCaptor.forClass(LedgerJournalPosting.class);verify(postings,times(1)).save(saved.capture());
  assertEquals("PSP_CLEARING",saved.getValue().getDebitAccount());assertEquals("DELIVERY_CUSTOMER_ADVANCES",saved.getValue().getCreditAccount());assertEquals(saved.getValue().getAmountMinor(),1234L);}
 @Test void refundPostsOnlyNewCumulativeDeltaToClearing(){
  var service=new LedgerJournalService(postings);UUID request=UUID.randomUUID(),payment=UUID.randomUUID();
  var intent=new PaymentIntentDetails(payment,null,UUID.randomUUID(),null,"PARTIALLY_REFUNDED","DELIVERY",1000,"SAR",null,null,null,request,400L);
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.of(new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"CAPTURE","PSP_CLEARING","DELIVERY_CUSTOMER_ADVANCES",1000,"SAR",null,"capture")));
  when(postings.findTopBySourceTypeAndSourceIdAndCumulativeRefundMinorIsNotNullOrderByCumulativeRefundMinorDesc("DELIVERY",request)).thenReturn(Optional.of(new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"REFUND:250","DELIVERY_CUSTOMER_ADVANCES","PSP_CLEARING",250,"SAR",250L,"previous")));
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"REFUND:400")).thenReturn(Optional.empty());
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"RECOGNIZE")).thenReturn(Optional.empty());
  service.refund(intent);
  ArgumentCaptor<LedgerJournalPosting> saved=ArgumentCaptor.forClass(LedgerJournalPosting.class);verify(postings).save(saved.capture());
  assertEquals(150L,saved.getValue().getAmountMinor());assertEquals(400L,saved.getValue().getCumulativeRefundMinor());assertEquals("DELIVERY_CUSTOMER_ADVANCES",saved.getValue().getDebitAccount());}
 @Test void revenueCannotBeRecognizedBeforeCapturedPayment(){
  var service=new LedgerJournalService(postings);UUID request=UUID.randomUUID(),payment=UUID.randomUUID();
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.empty());
  assertThrows(IllegalStateException.class,()->service.recognize(new DeliveryCompletedEvent(request,payment,900,"SAR",Instant.now())));}
}
