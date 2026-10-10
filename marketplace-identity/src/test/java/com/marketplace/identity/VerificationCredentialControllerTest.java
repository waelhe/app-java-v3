package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The controller unit slice of the verification credential family (the
 * {@code UserControllerTest} shape): delegation, the self-subject contract
 * (the subject comes from the token, never from a request parameter), and
 * the honest rejection of a non-JWT authentication (the syncFromOidc L23
 * defect lesson, measured here on the new family).
 */
@ExtendWith(MockitoExtension.class)
class VerificationCredentialControllerTest {

    @Mock
    private VerificationCredentialService service;

    @InjectMocks
    private VerificationCredentialController controller;

    @Test
    void submit_delegatesToTheTokenSubjectAndAnswersCreated() {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("alg", "none"), Map.of("sub", "sub-1"));
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        VerificationCredential credential = VerificationCredential.submit(
                UUID.randomUUID(), VerificationCredentialType.IDENTITY, "https://evidence/1", "note");
        when(service.submit("sub-1", "IDENTITY", "https://evidence/1", "note")).thenReturn(credential);

        var result = controller.submit(
                new VerificationCredentialController.SubmitCredentialRequest(
                        "IDENTITY", "https://evidence/1", "note"), token);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(credential.getId(), result.getBody().id());
        assertEquals("PENDING", result.getBody().status());
    }

    @Test
    void submit_ofANonJwtAuthentication_isBadRequest() {
        Authentication notJwt = mock(Authentication.class);

        assertThrows(BadRequestException.class, () -> controller.submit(
                new VerificationCredentialController.SubmitCredentialRequest("IDENTITY", null, null),
                notJwt));
        verifyNoServiceInteractions();
    }

    private void verifyNoServiceInteractions() {
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void mine_mapsTheHistoryToResponses() {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("alg", "none"), Map.of("sub", "sub-1"));
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        VerificationCredential credential = VerificationCredential.submit(
                UUID.randomUUID(), VerificationCredentialType.RESIDENCE, null, null);
        when(service.mine("sub-1")).thenReturn(List.of(credential));

        var result = controller.mine(token);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(1, result.getBody().size());
        assertEquals("RESIDENCE", result.getBody().get(0).credentialType());
    }
}
