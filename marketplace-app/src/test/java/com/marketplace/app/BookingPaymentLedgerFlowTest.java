package com.marketplace.app;

import com.marketplace.ledger.LedgerEntry;
import com.marketplace.ledger.LedgerEntryRepository;
import com.marketplace.ledger.LedgerService;
import com.marketplace.ledger.ProviderBalance;
import com.marketplace.ledger.ProviderBalanceRepository;
import com.marketplace.payments.*;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookingPaymentLedgerFlowTest {

    @Test
    void bookingPaymentLedger_endToEnd_successAndIdempotentCredit() {
        PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
        PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);

        PaymentsService paymentsService = new PaymentsService(
                intentRepository, paymentRepository, webhookRepository,
                eventPublisher, currentUserProvider, bookingParticipantProvider, webhookSecurity);

        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService ledgerService = new LedgerService(entryRepository, balanceRepository);

        UUID bookingId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        String key = "idem-1";
        Authentication auth = mock(Authentication.class);

        AtomicReference<PaymentIntent> savedIntent = new AtomicReference<>();
        BookingInfo info = new BookingInfo(providerId, consumerId, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(info);
        when(intentRepository.findByIdempotencyKey(key)).thenAnswer(invocation -> Optional.ofNullable(savedIntent.get()));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentUserProvider.isAdmin(auth)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(consumerId);
        when(intentRepository.findById(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(savedIntent.get()));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.findByPaymentIntentId(any(UUID.class))).thenReturn(Optional.of(Payment.create(UUID.randomUUID(), 5000L)));

        PaymentIntent intent = paymentsService.createIntent(bookingId, consumerId, key);
        savedIntent.set(intent);
        PaymentIntent intent2 = paymentsService.createIntent(bookingId, consumerId, key);

        assertEquals(intent.getId(), intent2.getId(), "idempotency key should return same intent");

        paymentsService.processIntent(intent.getId(), auth);
        PaymentIntent succeeded = paymentsService.confirmIntent(intent.getId(), "ext-ok");
        assertEquals(PaymentIntentStatus.SUCCEEDED, succeeded.getStatus());

        when(entryRepository.findBySourceId(intent.getId())).thenReturn(Optional.empty(), Optional.of(mock(LedgerEntry.class)));
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(ProviderBalance.empty(providerId)));
        when(balanceRepository.save(any(ProviderBalance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProviderBalance first = ledgerService.creditFromPayment(providerId, intent.getId(), 5000L);
        ProviderBalance second = ledgerService.creditFromPayment(providerId, intent.getId(), 5000L);

        assertEquals(5000L, first.getAvailableCents());
        assertEquals(5000L, second.getAvailableCents(), "second credit for same payment intent must be idempotent");
        verify(entryRepository, times(1)).save(any(LedgerEntry.class));
    }

    @Test
    void bookingPaymentLedger_confirmFailure_preventsLedgerCredit() {
        PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
        PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);

        PaymentsService paymentsService = new PaymentsService(
                intentRepository, paymentRepository, webhookRepository,
                eventPublisher, currentUserProvider, bookingParticipantProvider, webhookSecurity);

        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService ledgerService = new LedgerService(entryRepository, balanceRepository);

        UUID id = UUID.randomUUID();
        PaymentIntent createdOnly = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(createdOnly));

        assertThrows(IllegalStateException.class, () -> paymentsService.confirmIntent(id, "ext-fail"));
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
        assertEquals(0L, ledgerService.getBalance(UUID.randomUUID()).getAvailableCents());
    }
}
