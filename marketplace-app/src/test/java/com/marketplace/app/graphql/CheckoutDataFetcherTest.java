package com.marketplace.app.graphql;

import com.marketplace.orders.spi.CheckoutSpi;
import com.marketplace.orders.spi.CheckoutView;
import com.marketplace.orders.spi.OrderCommandSpi;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D-UUID-001 (Option A, decided 2026-10-23) resolver acceptance matrix: the
 * deprecated {@code token} UUID alias is the checkout's NATIVE cart UUID
 * (never decoded through the typed global-ID codec), {@code id} and the
 * deprecated {@code checkoutId} decode through {@link SaleorGlobalIdCodec},
 * the three identifiers are mutually exclusive per request (rejected even
 * when they denote the same cart — no supplied identifier is silently
 * ignored), and EVERY path delegates to the SAME {@link CheckoutSpi} use
 * case with the caller's authentication unchanged: a token never bypasses
 * the authentication wall, the owning use case's ownership/ADMIN wall, or
 * the classified NOT_FOUND answers for guessed and wrong-owner UUIDs.
 */
class CheckoutDataFetcherTest {
    private CheckoutSpi checkoutSpi;
    private CheckoutGraphQlMapper mapper;
    private OrderCommandSpi orderCommandSpi;
    private CheckoutDataFetcher fetcher;
    private SaleorGlobalIdCodec codec;

    @BeforeEach
    void setUp() {
        checkoutSpi = mock(CheckoutSpi.class);
        mapper = mock(CheckoutGraphQlMapper.class);
        orderCommandSpi = mock(OrderCommandSpi.class);
        codec = new SaleorGlobalIdCodec();
        fetcher = new CheckoutDataFetcher(checkoutSpi, orderCommandSpi, mapper, codec);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("checkout-owner", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static String checkoutGlobalId(UUID cartId) {
        return new SaleorGlobalIdCodec().encode("Checkout", cartId);
    }

    private static String variantGlobalId(UUID variantId) {
        return new SaleorGlobalIdCodec().encode("ProductVariant", variantId);
    }

    private static String lineGlobalId(UUID lineId) {
        return new SaleorGlobalIdCodec().encode("CheckoutLine", lineId);
    }

    @Test
    void absentOrBlankOptionalIdAnswersNullWithoutCallingTheOwner() {
        assertThat(fetcher.checkout(null, null)).isNull();
        assertThat(fetcher.checkout("   ", null)).isNull();
        verifyNoInteractions(checkoutSpi, mapper);
    }

    @Test
    void checkoutDeleteAuthorizationDenialPrecedesGlobalIdDecoding() {
        doThrow(new AccessDeniedException("ADMIN required")).when(orderCommandSpi).requireAdmin();

        assertThatThrownBy(() -> fetcher.checkoutDelete("not-a-checkout-global-id"))
                .isInstanceOf(AccessDeniedException.class);

        verify(orderCommandSpi).requireAdmin();
        verifyNoInteractions(checkoutSpi);
    }

    @Test
    void malformedNonEmptyIdRemainsAClassifiedValidationFailure() {
        assertThatThrownBy(() -> fetcher.checkout("not-a-checkout-global-id", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(checkoutSpi, mapper);
    }

    // ------------------------------------------------------------------
    // D-UUID-001: the deprecated token alias is the native cart UUID
    // ------------------------------------------------------------------

    @Test
    void tokenAloneDelegatesWithTheNativeCartUuidUntouchedByTheGlobalIdCodec() {
        UUID token = UUID.randomUUID();
        CheckoutView view = new CheckoutView(token, List.of(), 0);
        when(checkoutSpi.findCheckout(eq(token), any())).thenReturn(view);
        when(mapper.toGraphQlCheckout(view)).thenReturn(Map.of("id", checkoutGlobalId(token)));

        assertThat(fetcher.checkout(null, token)).isNotNull();

        ArgumentCaptor<Authentication> authenticationCaptor =
                ArgumentCaptor.forClass(Authentication.class);
        verify(checkoutSpi).findCheckout(eq(token), authenticationCaptor.capture());
        assertThat(authenticationCaptor.getValue())
                .isSameAs(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void queryRejectsIdAndTokenTogetherEvenWhenTheyDenoteTheSameCart() {
        UUID cartId = UUID.randomUUID();
        String id = checkoutGlobalId(cartId);

        assertThatThrownBy(() -> fetcher.checkout(id, cartId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only one checkout identifier");

        verifyNoInteractions(checkoutSpi, mapper);
    }

    @Test
    void guessedTokenUuidAnswersNullOnTheQueryWithNoPayloadLeakage() {
        UUID guessed = UUID.randomUUID();
        when(checkoutSpi.findCheckout(eq(guessed), any()))
                .thenThrow(new ResourceNotFoundException("Checkout", guessed));

        assertThat(fetcher.checkout(null, guessed)).isNull();
    }

    // ------------------------------------------------------------------
    // the mutation matrix: token / checkoutId / id delegation + conflicts
    // ------------------------------------------------------------------

    @Test
    void tokenAloneOnLinesAddDelegatesToTheSameUseCaseWithTheNativeUuid() {
        UUID token = UUID.randomUUID();
        String variantId = variantGlobalId(UUID.randomUUID());
        CheckoutView view = new CheckoutView(token, List.of(), 0);
        when(checkoutSpi.addLines(eq(token), anyList(), any())).thenReturn(view);

        Map<String, Object> result = fetcher.checkoutLinesAdd(null, null, token,
                List.of(Map.of("quantity", 2, "variantId", variantId)));

        assertThat(result.get("errors")).isEqualTo(List.of());
        verify(checkoutSpi).addLines(eq(token), anyList(),
                eq(SecurityContextHolder.getContext().getAuthentication()));
    }

    @Test
    void legacyCheckoutIdAloneOnLinesAddDecodesThroughTheTypedGlobalIdCodec() {
        UUID cartId = UUID.randomUUID();
        String variantId = variantGlobalId(UUID.randomUUID());
        when(checkoutSpi.addLines(eq(cartId), anyList(), any()))
                .thenReturn(new CheckoutView(cartId, List.of(), 0));

        fetcher.checkoutLinesAdd(null, checkoutGlobalId(cartId), null,
                List.of(Map.of("quantity", 1, "variantId", variantId)));

        verify(checkoutSpi).addLines(eq(cartId), anyList(), any());
    }

    @Test
    void linesAddRejectsEveryIdentifierPairEvenWhenBothDenoteTheSameCart() {
        UUID cartId = UUID.randomUUID();
        String id = checkoutGlobalId(cartId);
        String legacyId = checkoutGlobalId(cartId);
        String variantId = variantGlobalId(UUID.randomUUID());
        List<Map<String, Object>> lines = List.of(Map.of("quantity", 1, "variantId", variantId));

        assertInvalidIdentifierPayload(fetcher.checkoutLinesAdd(id, legacyId, null, lines));
        assertInvalidIdentifierPayload(fetcher.checkoutLinesAdd(id, null, cartId, lines));
        assertInvalidIdentifierPayload(fetcher.checkoutLinesAdd(null, legacyId, cartId, lines));

        verifyNoInteractions(checkoutSpi);
    }

    @Test
    void linesAddWithoutAnyIdentifierIsRejected() {
        Map<String, Object> result = fetcher.checkoutLinesAdd(null, null, null, List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("code", "INVALID");
        assertThat(errors.get(0)).containsEntry("field", "id");
        assertThat((String) errors.get(0).get("message")).contains("required");
        verifyNoInteractions(checkoutSpi);
    }

    @Test
    void tokenAloneOnLinesUpdateDelegatesWithTheNativeUuid() {
        UUID token = UUID.randomUUID();
        String lineId = lineGlobalId(UUID.randomUUID());
        when(checkoutSpi.updateLines(eq(token), anyList(), any()))
                .thenReturn(new CheckoutView(token, List.of(), 0));

        Map<String, Object> result = fetcher.checkoutLinesUpdate(null, null, token,
                List.of(Map.of("lineId", lineId, "quantity", 3)));

        assertThat(result.get("errors")).isEqualTo(List.of());
        verify(checkoutSpi).updateLines(eq(token), anyList(), any());
    }

    @Test
    void linesUpdateRejectsConflictingIdentifiers() {
        UUID cartId = UUID.randomUUID();
        Map<String, Object> line = Map.of("quantity", 1);

        assertInvalidIdentifierPayload(
                fetcher.checkoutLinesUpdate(checkoutGlobalId(cartId), null, cartId, List.of(line)));
        assertInvalidIdentifierPayload(
                fetcher.checkoutLinesUpdate(checkoutGlobalId(cartId), checkoutGlobalId(cartId), null, List.of(line)));

        verifyNoInteractions(checkoutSpi);
    }

    @Test
    void tokenAloneOnLinesDeleteDelegatesWithTheNativeUuid() {
        UUID token = UUID.randomUUID();
        String lineId = lineGlobalId(UUID.randomUUID());
        when(checkoutSpi.deleteLines(eq(token), anyList(), any()))
                .thenReturn(new CheckoutView(token, List.of(), 0));

        Map<String, Object> result = fetcher.checkoutLinesDelete(null, token, List.of(lineId));

        assertThat(result.get("errors")).isEqualTo(List.of());
        verify(checkoutSpi).deleteLines(eq(token), anyList(), any());
    }

    @Test
    void linesDeleteRejectsIdAndTokenTogetherAndRequiresAnIdentifier() {
        UUID cartId = UUID.randomUUID();

        assertInvalidIdentifierPayload(
                fetcher.checkoutLinesDelete(checkoutGlobalId(cartId), cartId, List.of()));

        Map<String, Object> noIdentifier = fetcher.checkoutLinesDelete(null, null, List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors =
                (List<Map<String, Object>>) noIdentifier.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("code", "INVALID");
        assertThat((String) errors.get(0).get("message")).contains("required");

        verifyNoInteractions(checkoutSpi);
    }

    @Test
    void tokenAloneOnLineDeleteDelegatesWithTheNativeUuid() {
        UUID token = UUID.randomUUID();
        String lineId = lineGlobalId(UUID.randomUUID());
        when(checkoutSpi.deleteLines(eq(token), anyList(), any()))
                .thenReturn(new CheckoutView(token, List.of(), 0));

        Map<String, Object> result = fetcher.checkoutLineDelete(null, null, token, lineId);

        assertThat(result.get("errors")).isEqualTo(List.of());
        verify(checkoutSpi).deleteLines(eq(token), anyList(), any());
    }

    @Test
    void lineDeleteRejectsConflictingIdentifiersBeforeTouchingTheOwner() {
        UUID cartId = UUID.randomUUID();
        String lineId = lineGlobalId(UUID.randomUUID());

        assertInvalidIdentifierPayload(
                fetcher.checkoutLineDelete(checkoutGlobalId(cartId), checkoutGlobalId(cartId), null, lineId));
        assertInvalidIdentifierPayload(
                fetcher.checkoutLineDelete(null, checkoutGlobalId(cartId), cartId, lineId));

        verifyNoInteractions(checkoutSpi);
    }

    @Test
    void guessedTokenUuidOnLinesAddAnswersTheClassifiedNotFoundWithoutLeakage() {
        UUID guessed = UUID.randomUUID();
        String variantId = variantGlobalId(UUID.randomUUID());
        when(checkoutSpi.addLines(eq(guessed), anyList(), any()))
                .thenThrow(new ResourceNotFoundException("Checkout", guessed));

        Map<String, Object> result = fetcher.checkoutLinesAdd(null, null, guessed,
                List.of(Map.of("quantity", 1, "variantId", variantId)));

        assertThat(result.get("checkout")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("code", "NOT_FOUND");
        String message = (String) errors.get(0).get("message");
        assertThat(message).doesNotContain("consumer").doesNotContain("owner");
    }

    // ------------------------------------------------------------------
    // the security gate: a token is never an authentication grant
    // ------------------------------------------------------------------

    @Test
    void tokenNeverBypassesTheAuthenticationWall() {
        SecurityContextHolder.clearContext();
        UUID token = UUID.randomUUID();

        assertThatThrownBy(() -> fetcher.checkout(null, token))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> fetcher.checkoutLinesAdd(null, null, token, List.of()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> fetcher.checkoutLinesDelete(null, token, List.of()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        verifyNoInteractions(checkoutSpi, mapper);
    }

    @Test
    void adminAuthenticationFlowsToTheUseCaseUnchangedOnEveryIdentifierPath() {
        Authentication admin = UsernamePasswordAuthenticationToken.authenticated(
                "checkout-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(admin);

        UUID cartId = UUID.randomUUID();
        when(checkoutSpi.findCheckout(any(), any())).thenReturn(new CheckoutView(cartId, List.of(), 0));

        fetcher.checkout(checkoutGlobalId(cartId), null);
        fetcher.checkout(null, cartId);

        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        verify(checkoutSpi, org.mockito.Mockito.times(2)).findCheckout(any(), captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(auth -> assertThat(auth).isSameAs(admin));
    }

    private static void assertInvalidIdentifierPayload(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("code", "INVALID");
        assertThat((String) errors.get(0).get("message")).contains("Only one checkout identifier");
    }
}
