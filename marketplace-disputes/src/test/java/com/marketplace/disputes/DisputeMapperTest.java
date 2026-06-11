package com.marketplace.disputes;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DisputeMapperTest {

    private final DisputeMapper mapper = Mappers.getMapper(DisputeMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        UUID bookingId = UUID.randomUUID();
        UUID openedBy = UUID.randomUUID();
        Dispute dispute = Dispute.open(bookingId, openedBy, "Test reason");

        DisputeResponse response = mapper.toResponse(dispute);

        assertEquals(dispute.getId(), response.id());
        assertEquals(bookingId, response.bookingId());
        assertEquals(openedBy, response.openedBy());
        assertEquals(DisputeStatus.OPEN, response.status());
        assertEquals("Test reason", response.reason());
    }
}
