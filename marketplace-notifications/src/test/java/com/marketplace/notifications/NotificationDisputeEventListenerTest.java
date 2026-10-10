package com.marketplace.notifications;

import com.marketplace.shared.api.DisputeOpenedEvent;
import com.marketplace.shared.api.DisputeResolvedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Task 5-f: the B-06 dispute events' first consumers — the listener's
 * delivery contract on the {@code BookingCancelledEventListenerTest}
 * pattern: the delegation carries the complete party fact, the
 * {@code @ApplicationModuleListener} annotation is pinned (the AFTER_COMMIT
 * / own-transaction / framework-retry contract every listener here rides),
 * and a failed delivery propagates (the registry entry stays incomplete —
 * the framework's resubmission is the standing housekeeping).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = NotificationDisputeEventListenerTest.TestConfig.class)
class NotificationDisputeEventListenerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        NotificationEventListener notificationEventListener(NotificationService notificationService) {
            return new NotificationEventListener(notificationService);
        }
    }

    @MockitoBean
    NotificationService notificationService;

    @Autowired
    NotificationEventListener listener;

    @Test
    void onDisputeOpened_deliversTheOpenersAcknowledgment() {
        UUID disputeId = UUID.randomUUID();
        UUID openedBy = UUID.randomUUID();

        listener.onDisputeOpened(new DisputeOpenedEvent(disputeId, UUID.randomUUID(), openedBy));

        verify(notificationService).onDisputeOpened(disputeId, openedBy);
    }

    @Test
    void onDisputeResolved_deliversTheAdjudicationFactWithTheExecutedOutcome() {
        UUID disputeId = UUID.randomUUID();
        UUID openedBy = UUID.randomUUID();

        listener.onDisputeResolved(new DisputeResolvedEvent(
                disputeId, UUID.randomUUID(), openedBy, "REFUND_CONSUMER", 5000L));

        verify(notificationService).onDisputeResolved(
                eq(disputeId), eq(openedBy), eq("REFUND_CONSUMER"), eq(5000L));
    }

    @Test
    void onDisputeResolved_moneyLessDecision_carriesNullOutcome() {
        UUID disputeId = UUID.randomUUID();
        UUID openedBy = UUID.randomUUID();

        listener.onDisputeResolved(new DisputeResolvedEvent(
                disputeId, UUID.randomUUID(), openedBy, "NO_ACTION", null));

        verify(notificationService).onDisputeResolved(
                eq(disputeId), eq(openedBy), eq("NO_ACTION"), isNull());
    }

    @Test
    void onDisputeOpened_propagatesFailure_soTheRegistryStaysIncomplete() {
        UUID disputeId = UUID.randomUUID();
        UUID openedBy = UUID.randomUUID();
        doThrow(new RuntimeException("delivery failed"))
                .when(notificationService).onDisputeOpened(any(), any());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> listener.onDisputeOpened(
                        new DisputeOpenedEvent(disputeId, UUID.randomUUID(), openedBy)));
        verify(notificationService).onDisputeOpened(disputeId, openedBy);
    }

    @Test
    void disputeConsumers_useApplicationModuleListenerAnnotation() throws NoSuchMethodException {
        assertThat(NotificationEventListener.class
                .getMethod("onDisputeOpened", DisputeOpenedEvent.class)
                .getAnnotation(ApplicationModuleListener.class)).isNotNull();
        assertThat(NotificationEventListener.class
                .getMethod("onDisputeResolved", DisputeResolvedEvent.class)
                .getAnnotation(ApplicationModuleListener.class)).isNotNull();
    }
}
