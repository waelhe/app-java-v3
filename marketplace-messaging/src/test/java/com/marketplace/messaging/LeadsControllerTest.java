package com.marketplace.messaging;

import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L34 (realestate systems plan §5 — lead capture): the controller seams —
 * the IP capture into the service, the "me" provider resolution (the L20
 * ledger seam), and the status codes the surface promises.
 */
@ExtendWith(MockitoExtension.class)
class LeadsControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PROVIDER_ID = UUID.randomUUID();

    @Mock
    private LeadsService leadsService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private LeadsController controller;

    @Test
    void createLeadPassesRemoteAddrThrough() {
        UUID listingId = UUID.randomUUID();
        LeadRequest request = new LeadRequest("Sami", "+963991234567", "hello");
        Authentication auth = null;
        HttpServletRequest http = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(http.getRemoteAddr()).thenReturn("198.51.100.4");
        LeadResponse expected = new LeadResponse(UUID.randomUUID(), listingId, "Sami",
                "+963991234567", "hello", "NEW", null);
        when(leadsService.createLead(eq(listingId), eq(request), eq(auth), eq("198.51.100.4")))
                .thenReturn(expected);

        ResponseEntity<LeadResponse> response = controller.createLead(listingId, request, auth, http);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void myLeadsKeysTheInboxByTheCallersUserId() {
        // The A1/V2 fact: the lead's provider column lives in the users.id
        // space — the caller's id IS the key, no profile indirection.
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        when(leadsService.listLeads(eq(USER_ID), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<?> response = controller.myLeads(null, PageRequest.of(0, 20), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void transitionLeadDelegatesWithTheCallerAsOwner() {
        UUID leadId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        LeadResponse expected = new LeadResponse(leadId, UUID.randomUUID(), "Sami",
                "+963991234567", "hello", "READ", null);
        when(leadsService.transitionLead(eq(leadId), eq(USER_ID), eq(LeadStatus.READ)))
                .thenReturn(expected);

        ResponseEntity<LeadResponse> response = controller.transitionLead(leadId,
                new LeadsController.LeadTransitionRequest(LeadStatus.READ), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("READ");
        verify(leadsService).transitionLead(eq(leadId), eq(USER_ID), eq(LeadStatus.READ));
    }
}
