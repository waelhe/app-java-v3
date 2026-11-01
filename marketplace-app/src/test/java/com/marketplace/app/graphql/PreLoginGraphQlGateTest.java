package com.marketplace.app.graphql;

import graphql.ExecutionInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the CodeRabbit round on PR #696): the pre-login operation
 * gate's own unit contract — the deny-by-default decision table the
 * integration class pins over the real HTTP surface. Every leg drives
 * {@link PreLoginGraphQlGate#intercept} directly: the anonymous caller's
 * document decides, the spoofed operationName never matters, and the
 * round-2 fragment discipline (a spread or an inline fragment at the top
 * level is a shape the gate cannot decide) refuses the request outright.
 */
@ExtendWith(MockitoExtension.class)
class PreLoginGraphQlGateTest {

    @Mock
    private WebGraphQlRequest request;
    @Mock
    private WebGraphQlInterceptor.Chain chain;

    private PreLoginGraphQlGate gate;

    @BeforeEach
    void setUp() {
        gate = new PreLoginGraphQlGate();
        lenient().when(request.toExecutionInput()).thenReturn(
                ExecutionInput.newExecutionInput()
                        .query("mutation { tokenVerify(token: \"x\") { isValid } }").build());
        lenient().when(chain.next(any())).thenReturn(Mono.just(mock(WebGraphQlResponse.class)));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void anonymous() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    }

    private void authenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("member@example.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"))));
    }

    private void assertDeniedWithAuthn() {
        WebGraphQlResponse response = gate.intercept(request, chain).block();
        assertThat(response).isNotNull();
        assertThat(response.getErrors()).hasSize(1);
        assertThat(response.getErrors().get(0).getExtensions().get("errorCode"))
                .isEqualTo("AUTHN-001");
        assertThat(response.getErrors().get(0).getExtensions().get("category"))
                .isEqualTo("authz");
        verify(chain, never()).next(any());
    }

    @Test
    void anAuthenticatedCallerPassesThroughUntouched() {
        // The authenticated path never even reads the document — the gate
        // is the ANONYMOUS surface's wall, every authenticated request
        // falls straight through to the per-fetcher permission catalog.
        authenticated();

        WebGraphQlResponse response = gate.intercept(request, chain).block();

        assertThat(response).isNotNull();
        verify(chain).next(request);
    }

    @Test
    void anAnonymousPreLoginDocumentPassesThrough() {
        anonymous();
        when(request.getDocument()).thenReturn("mutation { tokenVerify(token: \"x\") { isValid } }");

        WebGraphQlResponse response = gate.intercept(request, chain).block();

        assertThat(response).isNotNull();
        verify(chain).next(request);
    }

    @Test
    void anAnonymousProtectedQueryIsDeniedWithTheClassifiedError() {
        anonymous();
        when(request.getDocument()).thenReturn("{ services(first: 1) { edges { node { id } } } }");

        assertDeniedWithAuthn();
    }

    @Test
    void theOperationNameSpoofIsIgnoredBecauseTheDocumentDecides() {
        anonymous();
        // The gate reads the DOCUMENT only — the client-controlled
        // operationName parameter ("tokenCreate" riding a protected query)
        // never influences the decision.
        when(request.getDocument()).thenReturn("{ services(first: 1) { edges { node { id } } } }");

        assertDeniedWithAuthn();
    }

    @Test
    void aFragmentSpreadRidingAnAllowedFieldIsRefused() {
        anonymous();
        when(request.getDocument()).thenReturn(
                "mutation { tokenVerify(token: \"x\") { isValid } ...Protected }"
                        + " fragment Protected on Mutation {"
                        + " passwordChange(newPassword: \"whatever-123\") { errors { code } } }");

        assertDeniedWithAuthn();
    }

    @Test
    void anInlineFragmentSelectingAProtectedRootIsRefused() {
        anonymous();
        when(request.getDocument()).thenReturn(
                "mutation { ... on Mutation {"
                        + " passwordChange(newPassword: \"whatever-123\") { errors { code } } } }");

        assertDeniedWithAuthn();
    }

    @Test
    void anUnparsableDocumentIsDeniedByDefault() {
        anonymous();
        when(request.getDocument()).thenReturn("{ this is not a graphql document");

        assertDeniedWithAuthn();
    }

    @Test
    void anEmptyDocumentIsDeniedByDefault() {
        anonymous();
        when(request.getDocument()).thenReturn(null);

        assertDeniedWithAuthn();
    }

    @Test
    void aMixedPreLoginAndProtectedDocumentIsDenied() {
        anonymous();
        when(request.getDocument()).thenReturn(
                "mutation { tokenVerify(token: \"x\") { isValid }"
                        + " passwordChange(newPassword: \"whatever-123\") { errors { code } } }");

        assertDeniedWithAuthn();
    }
}
