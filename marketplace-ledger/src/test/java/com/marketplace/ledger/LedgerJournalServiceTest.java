package com.marketplace.ledger;
import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
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
 @Test void carrierCompensationPostsTheClearingFundedCourierPayableOnce(){
  // A1 (owner-gate batch 6a — ADR-0013 D4): the order-leg courier
  // compensation pair in THIS dedicated journal — PSP_CLEARING funds the
  // courier's payable, the recipient is the courier, and the replay is a
  // no-op (the operation-key idempotence the journal's own addIfAbsent
  // carries).
  var service=new LedgerJournalService(postings,jdbcTemplate);UUID request=UUID.randomUUID(),officialIntent=UUID.randomUUID(),courier=UUID.randomUUID();
  var event=new DeliveryCompletedEvent(request,null,4000L,"SAR",3000L,Instant.now(),courier,UUID.randomUUID());
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CARRIER_COMPENSATION")).thenReturn(Optional.empty(),Optional.of(new LedgerJournalPosting(UUID.randomUUID(),"DELIVERY",request,officialIntent,"CARRIER_COMPENSATION","PSP_CLEARING","DELIVERY_COURIER_PAYABLE",3000,"SAR",null,"first")));
  service.compensateCarrier(event,officialIntent);service.compensateCarrier(event,officialIntent);
  ArgumentCaptor<LedgerJournalPosting> saved=ArgumentCaptor.forClass(LedgerJournalPosting.class);verify(postings,times(1)).save(saved.capture());
  assertEquals("PSP_CLEARING",saved.getValue().getDebitAccount());assertEquals("DELIVERY_COURIER_PAYABLE",saved.getValue().getCreditAccount());assertEquals(3000L,saved.getValue().getAmountMinor());assertEquals(courier,saved.getValue().getRecipientId());assertEquals(officialIntent,saved.getValue().getPaymentIntentId());}
 @Test void carrierCompensationRequiresBothIdsAndRejectsAFareExceedingCompensation(){
  var service=new LedgerJournalService(postings,jdbcTemplate);
  assertThrows(IllegalArgumentException.class,()->service.compensateCarrier(new DeliveryCompletedEvent(null,null,4000L,"SAR",3000L,Instant.now(),UUID.randomUUID(),null),UUID.randomUUID()));
  assertThrows(IllegalArgumentException.class,()->service.compensateCarrier(new DeliveryCompletedEvent(UUID.randomUUID(),null,4000L,"SAR",3000L,Instant.now(),UUID.randomUUID(),null),null));
  assertThrows(IllegalArgumentException.class,()->service.compensateCarrier(new DeliveryCompletedEvent(UUID.randomUUID(),null,4000L,"SAR",4000L,Instant.now(),UUID.randomUUID(),null),UUID.randomUUID()));
  assertThrows(IllegalArgumentException.class,()->service.compensateCarrier(new DeliveryCompletedEvent(UUID.randomUUID(),null,4000L,"SAR",-3000L,Instant.now(),UUID.randomUUID(),null),UUID.randomUUID()));
  assertThrows(IllegalArgumentException.class,()->service.compensateCarrier(new DeliveryCompletedEvent(UUID.randomUUID(),null,4000L,"SAR",3000L,Instant.now(),null,null),UUID.randomUUID()));
  verifyNoInteractions(postings);}
 @Test void carrierCompensationWithZeroCompensationIsTheHonestNoOp(){
  var service=new LedgerJournalService(postings,jdbcTemplate);UUID request=UUID.randomUUID();
  service.compensateCarrier(new DeliveryCompletedEvent(request,null,4000L,"SAR",0L,Instant.now(),null,null),UUID.randomUUID());
  verifyNoInteractions(postings);}
 @Test void revenueCannotBeRecognizedBeforeCapturedPayment(){
  var service=new LedgerJournalService(postings,jdbcTemplate);UUID request=UUID.randomUUID(),payment=UUID.randomUUID();
  when(postings.findBySourceTypeAndSourceIdAndOperationKey("DELIVERY",request,"CAPTURE")).thenReturn(Optional.empty());
  assertThrows(IllegalStateException.class,()->service.recognize(new DeliveryCompletedEvent(request,payment,900,"SAR",Instant.now())));}
}
