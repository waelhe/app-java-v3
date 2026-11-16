package com.marketplace.payments;

import com.marketplace.shared.api.LoanCancelledEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * W7 (the origin-by-state matrix's own measured gap G6): the loan
 * cancellation's money-edge listener — the {@code BookingCancelledEventListenerTest}
 * twin verbatim, closing the matrix's documented absence («لا اختبار وحدة
 * مخصص لـLoanCancelledEventListener — مغطى تكامليًا فقط»). The listener's
 * contract: the ONE refund contract ({@code autoRefundByLoan}) on the
 * event's own loan id, exception propagation (the AFTER_COMMIT unit's own
 * retry semantics), and the Modulith listener annotation (the late-lander
 * registration).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = LoanCancelledEventListenerTest.TestConfig.class)
class LoanCancelledEventListenerTest {

    @Configuration
    static class TestConfig {
        @Bean
        LoanCancelledEventListener loanCancelledEventListener(PaymentsService paymentsService) {
            return new LoanCancelledEventListener(paymentsService);
        }
    }

    @MockitoBean
    PaymentsService paymentsService;

    @Autowired
    LoanCancelledEventListener listener;

    @Test
    void onLoanCancelled_callsAutoRefundWithTheEventsOwnLoanId() {
        UUID loanId = UUID.randomUUID();
        LoanCancelledEvent event = new LoanCancelledEvent(loanId, UUID.randomUUID());

        listener.onLoanCancelled(event);

        verify(paymentsService).autoRefundByLoan(loanId);
    }

    @Test
    void onLoanCancelled_propagatesException() {
        UUID loanId = UUID.randomUUID();
        LoanCancelledEvent event = new LoanCancelledEvent(loanId, UUID.randomUUID());

        doThrow(new RuntimeException("Payment service error"))
                .when(paymentsService).autoRefundByLoan(loanId);

        assertThrows(RuntimeException.class,
                () -> listener.onLoanCancelled(event));
    }

    @Test
    void onLoanCancelled_usesApplicationModuleListenerAnnotation() throws NoSuchMethodException {
        var method = LoanCancelledEventListener.class.getMethod(
                "onLoanCancelled", LoanCancelledEvent.class);
        ApplicationModuleListener ann = method.getAnnotation(ApplicationModuleListener.class);
        assertNotNull(ann);
    }
}
