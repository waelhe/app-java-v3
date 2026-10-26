package com.marketplace.identity;

import com.marketplace.shared.api.AccountSessionsRevoked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Row 13 (agent findings 2026-10-26 14:33 export, CWE-613): the owner's
 * "sign out everywhere" — the two halves of one intent. The stored OAuth
 * authorizations die inside the transaction, AND the revocation fact is
 * published in the SAME transaction for the AFTER_COMMIT session kill
 * (the {@code AccountStatusSessionInvalidator} edge — a live browser
 * session otherwise keeps its authenticated security context and
 * authorizes again immediately, re-creating exactly the replacement
 * grants the revocation just removed, without any credential challenge).
 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    private static final String PRINCIPAL = "owner@example.com";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private TokenRevocationService service;

    @BeforeEach
    void setUp() {
        service = new TokenRevocationService(jdbcTemplate, eventPublisher);
    }

    @Test
    void deactivateAllFor_killsTheAuthorizations_andPublishesTheSessionRevocationFact() {
        Authentication caller = new TestingAuthenticationToken(PRINCIPAL, "credentials");
        when(jdbcTemplate.update(anyString(), eq(PRINCIPAL))).thenReturn(3);

        int killed = service.deactivateAllFor(caller);

        assertThat(killed).isEqualTo(3);
        verify(jdbcTemplate).update(
                "DELETE FROM oauth2_authorization WHERE principal_name = ?", PRINCIPAL);
        // The session half rides the house R8 channel — published inside
        // the same transaction, consumed AFTER_COMMIT.
        verify(eventPublisher).publishEvent(new AccountSessionsRevoked(PRINCIPAL));
    }

    @Test
    void deactivateAllFor_withNoStoredAuthorizations_stillPublishesTheRevocationFact() {
        Authentication caller = new TestingAuthenticationToken(PRINCIPAL, "credentials");
        when(jdbcTemplate.update(anyString(), eq(PRINCIPAL))).thenReturn(0);

        int killed = service.deactivateAllFor(caller);

        assertThat(killed).isZero();
        // Zero rows deleted is still a complete sign-out intent: the
        // SESSIONS must die too (they authorize anew on the next request).
        verify(eventPublisher).publishEvent(new AccountSessionsRevoked(PRINCIPAL));
    }
}
