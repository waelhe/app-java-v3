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
    private Authentication authentication;

    @InjectMocks
    private DisputeController disputeController;

    @Test
    void open_returnsOk() {
        UUID bookingId = UUID.randomUUID();
        String reason = "late arrival";
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), reason);
        when(disputeService.open(bookingId, reason, authentication)).thenReturn(dispute);

        ResponseEntity<Dispute> response = disputeController.open(bookingId, reason, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(dispute);
    }

    @Test
    void list_returnsOk() {
        UUID bookingId = UUID.randomUUID();
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), "damage");
        when(disputeService.listForBooking(bookingId, authentication)).thenReturn(List.of(dispute));

        ResponseEntity<List<Dispute>> response = disputeController.list(bookingId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(dispute);
    }

    @Test
    void resolve_returnsOk() {
        UUID disputeId = UUID.randomUUID();
        Dispute dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "noise");
        when(disputeService.resolve(disputeId, authentication)).thenReturn(dispute);

        ResponseEntity<Dispute> response = disputeController.resolve(disputeId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(dispute);
    }
}
