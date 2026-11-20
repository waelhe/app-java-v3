package com.marketplace.ledger;
import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
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
 @Mock JdbcTemplate jdbcTemplate;
 @Test void captureCreatesOneBalancedPairAndReplayIsIdempotent(){
  var service=new LedgerJournalService(postings,jdbcTemplate);UUID request=UUID.randomUUID(),payment=UUID.randomUUID();
  var intent=new PaymentIntentDetails(payment,null,UUID.randomUUID(),null,"SUCCEEDED","DELIVERY",1234,"SAR",null,null,null,request,0L);
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.empty(),Optional.of(new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"CAPTURE","PSP_CLEARING","DELIVERY_CUSTOMER_ADVANCES",1234,"SAR",null,"test")));
  service.capture(intent);service.capture(intent);
  ArgumentCaptor<LedgerJournalPosting> saved=ArgumentCaptor.forClass(LedgerJournalPosting.class);verify(postings,times(1)).save(saved.capture());
  assertEquals("PSP_CLEARING",saved.getValue().getDebitAccount());assertEquals("DELIVERY_CUSTOMER_ADVANCES",saved.getValue().getCreditAccount());assertEquals(saved.getValue().getAmountMinor(),1234L);}
 @Test void refundPostsOnlyNewCumulativeDeltaToClearing(){
  var service=new LedgerJournalService(postings,jdbcTemplate);UUID request=UUID.randomUUID(),payment=UUID.randomUUID();
  var intent=new PaymentIntentDetails(payment,null,UUID.randomUUID(),null,"PARTIALLY_REFUNDED","DELIVERY",1000,"SAR",null,null,null,request,400L);
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.of(new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"CAPTURE","PSP_CLEARING","DELIVERY_CUSTOMER_ADVANCES",1000,"SAR",null,"capture")));
  when(postings.findTopBySourceTypeAndSourceIdAndCumulativeRefundMinorIsNotNullOrderByCumulativeRefundMinorDesc("DELIVERY",request)).thenReturn(Optional.of(new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"REFUND:250","DELIVERY_CUSTOMER_ADVANCES","PSP_CLEARING",250,"SAR",250L,"previous")));
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"REFUND:400")).thenReturn(Optional.empty());
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"RECOGNIZE")).thenReturn(Optional.empty());
  service.refund(intent);
  ArgumentCaptor<LedgerJournalPosting> saved=ArgumentCaptor.forClass(LedgerJournalPosting.class);verify(postings).save(saved.capture());
  assertEquals(150L,saved.getValue().getAmountMinor());assertEquals(400L,saved.getValue().getCumulativeRefundMinor());assertEquals("DELIVERY_CUSTOMER_ADVANCES",saved.getValue().getDebitAccount());}
 @Test void refundAcquiresThePerBatchAdvisoryLockBeforeTheDeltaRead(){
  // A3 (owner-gate batch 6a): the lock precedes the read — the whole
  // read-modify-write critical section runs inside the transaction bounded
  // by the lock (the documented recommendation's own wording: «لا قفل على
  // القراءة وحدها»). The key is derived per delivery batch — one lock scope
  // per webhook stream, distinct batches independent.
  var service=new LedgerJournalService(postings,jdbcTemplate);UUID request=UUID.randomUUID(),payment=UUID.randomUUID();
  var intent=new PaymentIntentDetails(payment,null,UUID.randomUUID(),null,"PARTIALLY_REFUNDED","DELIVERY",1000,"SAR",null,null,null,request,400L);
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.of(new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"CAPTURE","PSP_CLEARING","DELIVERY_CUSTOMER_ADVANCES",1000,"SAR",null,"capture")));
  when(postings.findTopBySourceTypeAndSourceIdAndCumulativeRefundMinorIsNotNullOrderByCumulativeRefundMinorDesc("DELIVERY",request)).thenReturn(Optional.empty());
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"REFUND:400")).thenReturn(Optional.empty());
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"RECOGNIZE")).thenReturn(Optional.empty());
  service.refund(intent);
  ArgumentCaptor<String> lockSql=ArgumentCaptor.forClass(String.class);
  InOrder order=org.mockito.Mockito.inOrder(jdbcTemplate,postings);
  order.verify(jdbcTemplate).execute(lockSql.capture());
  order.verify(postings).findTopBySourceTypeAndSourceIdAndCumulativeRefundMinorIsNotNullOrderByCumulativeRefundMinorDesc("DELIVERY",request);
  assertEquals("SELECT pg_advisory_xact_lock(hashtextextended('marketplace.delivery-refund-delta:' || '"+request+"', 0))",lockSql.getValue());}

 @Test void recognizeAfterPartialRefundPostsOnlyTheNetRevenueAndCourierPayable(){
  var service=new LedgerJournalService(postings,jdbcTemplate);UUID request=UUID.randomUUID(),payment=UUID.randomUUID(),courier=UUID.randomUUID();
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.of(
   new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"CAPTURE","PSP_CLEARING","DELIVERY_CUSTOMER_ADVANCES",1000,"SAR",null,"capture")));
  when(postings.findTopBySourceTypeAndSourceIdAndCumulativeRefundMinorIsNotNullOrderByCumulativeRefundMinorDesc("DELIVERY",request)).thenReturn(Optional.of(
   new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"REFUND:300","DELIVERY_CUSTOMER_ADVANCES","PSP_CLEARING",300,"SAR",300L,"refund")));
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"RECOGNIZE")).thenReturn(Optional.empty());
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"COURIER_PAYABLE")).thenReturn(Optional.empty());
  service.recognize(new DeliveryCompletedEvent(request,payment,1000,"SAR",200,Instant.now(),courier));
  ArgumentCaptor<LedgerJournalPosting> saved=ArgumentCaptor.forClass(LedgerJournalPosting.class);
  verify(postings,times(2)).save(saved.capture());
  var byOperation=saved.getAllValues().stream().collect(java.util.stream.Collectors.toMap(LedgerJournalPosting::getOperationKey,x->x));
  assertEquals(700L,byOperation.get("RECOGNIZE").getAmountMinor());
  assertEquals("DELIVERY_REVENUE",byOperation.get("RECOGNIZE").getCreditAccount());
  assertEquals(200L,byOperation.get("COURIER_PAYABLE").getAmountMinor());
  assertEquals("DELIVERY_REVENUE",byOperation.get("COURIER_PAYABLE").getDebitAccount());
  assertEquals(courier,byOperation.get("COURIER_PAYABLE").getRecipientId());
  var order=org.mockito.Mockito.inOrder(jdbcTemplate,postings);
  ArgumentCaptor<String> lockSql=ArgumentCaptor.forClass(String.class);
  order.verify(jdbcTemplate).execute(lockSql.capture());
  order.verify(postings).findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE");
  order.verify(postings).findTopBySourceTypeAndSourceIdAndCumulativeRefundMinorIsNotNullOrderByCumulativeRefundMinorDesc("DELIVERY",request);
  assertEquals("SELECT pg_advisory_xact_lock(hashtextextended('marketplace.delivery-refund-delta:' || '"+request+"', 0))",lockSql.getValue());
 }
 @Test void fullyRefundedDeliveryRecognizesNoRevenueButKeepsCourierPayable(){
  var service=new LedgerJournalService(postings,jdbcTemplate);UUID request=UUID.randomUUID(),payment=UUID.randomUUID(),courier=UUID.randomUUID();
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.of(
   new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"CAPTURE","PSP_CLEARING","DELIVERY_CUSTOMER_ADVANCES",1000,"SAR",null,"capture")));
  when(postings.findTopBySourceTypeAndSourceIdAndCumulativeRefundMinorIsNotNullOrderByCumulativeRefundMinorDesc("DELIVERY",request)).thenReturn(Optional.of(
   new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,payment,"REFUND:1000","DELIVERY_CUSTOMER_ADVANCES","PSP_CLEARING",1000,"SAR",1000L,"full refund")));
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"COURIER_PAYABLE")).thenReturn(Optional.empty());
  service.recognize(new DeliveryCompletedEvent(request,payment,1000,"SAR",200,Instant.now(),courier));
  ArgumentCaptor<LedgerJournalPosting> saved=ArgumentCaptor.forClass(LedgerJournalPosting.class);
  verify(postings).save(saved.capture());
  assertEquals("COURIER_PAYABLE",saved.getValue().getOperationKey());
  assertEquals(200L,saved.getValue().getAmountMinor());
  assertEquals("DELIVERY_REFUNDS",saved.getValue().getDebitAccount());
  assertEquals("DELIVERY_COURIER_PAYABLE",saved.getValue().getCreditAccount());
  verify(postings,org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.argThat(x->"RECOGNIZE".equals(x.getOperationKey())));
 }
 @Test void revenueCannotBeRecognizedBeforeCapturedPayment(){
  var service=new LedgerJournalService(postings,jdbcTemplate);UUID request=UUID.randomUUID(),payment=UUID.randomUUID();
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.empty());
  assertThrows(IllegalStateException.class,()->service.recognize(new DeliveryCompletedEvent(request,payment,900,"SAR",Instant.now())));}
}
