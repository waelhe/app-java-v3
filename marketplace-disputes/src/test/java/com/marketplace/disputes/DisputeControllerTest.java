package com.marketplace.disputes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisputeControllerTest {

    @Mock
    private DisputeService disputeService;

    @Mock
    private DisputeMapper disputeMapper;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private DisputeController disputeController;

    @Test
    void open_returnsOk() {
        UUID bookingId = UUID.randomUUID();
        String reason = "late arrival";
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), reason);
        DisputeResponse response = new DisputeResponse(dispute.getId(), dispute.getBookingId(),
                dispute.getOpenedBy(), dispute.getStatus(), dispute.getReason(), null, null);
        when(disputeService.open(bookingId, reason, authentication)).thenReturn(dispute);
        when(disputeMapper.toResponse(dispute)).thenReturn(response);

        ResponseEntity<DisputeResponse> result = disputeController.open(bookingId, reason, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
    }

    @Test
    void list_returnsOk() {
        UUID bookingId = UUID.randomUUID();
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), "damage");
        DisputeResponse response = new DisputeResponse(dispute.getId(), dispute.getBookingId(),
                dispute.getOpenedBy(), dispute.getStatus(), dispute.getReason(), null, null);
        when(disputeService.listForBooking(bookingId, authentication)).thenReturn(List.of(dispute));
        when(disputeMapper.toResponse(dispute)).thenReturn(response);

        ResponseEntity<List<DisputeResponse>> result = disputeController.list(bookingId, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsExactly(response);
    }

    @Test
    void resolve_returnsOk() {
        UUID disputeId = UUID.randomUUID();
        Dispute dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "noise");
        DisputeResponse response = new DisputeResponse(dispute.getId(), dispute.getBookingId(),
                dispute.getOpenedBy(), dispute.getStatus(), dispute.getReason(), null, null);
        when(disputeService.resolve(disputeId, authentication)).thenReturn(dispute);
        when(disputeMapper.toResponse(dispute)).thenReturn(response);

        ResponseEntity<DisputeResponse> result = disputeController.resolve(disputeId, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
    }
}
