package com.marketplace.disputes;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DisputeTest {

    @Test
    void openCreatesDispute() {
        var bookingId = UUID.randomUUID();
        var openedBy = UUID.randomUUID();
        var dispute = Dispute.open(bookingId, openedBy, "Not satisfied");

        assertNotNull(dispute.getId());
        assertEquals(bookingId, dispute.getBookingId());
        assertEquals(DisputeStatus.OPEN, DisputeStatus.OPEN);
    }

    @Test
    void resolveChangesStatus() {
        var dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "Reason");
        dispute.resolve();
    }
}
