package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.marketplace.identity.spi.AddressManagementSpi;
import com.marketplace.identity.spi.AddressView;
import com.marketplace.identity.spi.CustomerTypeSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.identity.spi.UserAccountView;
import com.marketplace.identity.spi.UserManagementSpi;
import com.marketplace.console.spi.CmsNavigationSpi;
import com.marketplace.console.spi.CmsPageSpi;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * DGS data-fetcher layer proof of this packet's mutation roots — the house
 * containerless pattern ({@code @SpringBootTest} over the GraphQL adapter
 * package slice + {@code @EnableDgsTest} + {@link DgsQueryExecutor}): REAL
 * GraphQL documents against the REAL SDL (the agent-1 fragment composed
 * with the central schema at codegen time) and the REAL DGS wiring, with
 * the owning seams mocked at their declared interfaces (the full-stack
 * proofs over real PostgreSQL/Flyway live in the integration suites).
 */
@SpringBootTest(classes = AgentOneMutationDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class AgentOneMutationDgsTest {

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    // The encoded-cursor seam (the customers/permissionGroups test
    // precedent) — the cursor-validation legs build REAL encoded cursors
    // with foreign fingerprints.
    @Autowired
    private EncodingCursorStrategy<ScrollPosition> cursorStrategy;

    @MockitoBean
    private IdentitySpi identitySpi;
    @MockitoBean
    private AccountLifecycleSpi accountLifecycleSpi;
    @MockitoBean
    private AddressManagementSpi addressManagementSpi;
    @MockitoBean
    private CustomerTypeSpi customerTypeSpi;
    @MockitoBean
    private UserManagementSpi userManagementSpi;
    @MockitoBean
    private CmsPageSpi cmsPageSpi;
    @MockitoBean
    private CmsNavigationSpi cmsNavigationSpi;
    @MockitoBean
    private AttributeReadSpi attributeReadSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.CatalogSpi catalogSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductAdminSpi productAdminSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductReadSpi productReadSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantReadSpi variantReadSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeReadSpi productTypeReadSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.CategoryReadSpi categoryReadSpi;
    @MockitoBean
    private com.marketplace.discovery.spi.DiscoverySpi discoverySpi;
    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodFeedPort feedPort;
    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodPostDetailPort postDetailPort;
    @MockitoBean
    private com.marketplace.orders.spi.OrderReadSpi orderReadSpi;
    @MockitoBean
    private com.marketplace.pricing.spi.DiscountReadSpi discountReadSpi;
    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;
    @MockitoBean
    private PlatformTokenGrantService tokenGrantService;
    // The post-merge integration round (PR #696 review): the slice scans
    // ALL of com.marketplace.app.graphql, so it discovers the multi-instance
    // rotation round's JdbcRefreshTokenRotationStore — a real @Component
    // whose constructor needs a JdbcTemplate the containerless slice has
    // no DataSource for. The mock satisfies the wiring; the store's REAL
    // behavior (including the two-instance atomicity) is proven over real
    // PostgreSQL/Flyway in PlatformTokenGrantRotationMultiInstanceIntegrationTest.
    @MockitoBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    // The post-merge integration round's completion of the same wiring
    // contract: A0's central assembly landed the sibling agents' catalog/
    // media/pricing write fetchers in this package, so the full-package
    // slice discovers them too — their owning write SPIs join the mocked
    // seam set (the declared interfaces, never the internals; the REAL
    // implementations are proven in the owning modules' own suites and
    // the full-stack integration tests over real PostgreSQL/Flyway).
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeValueWriteSpi attributeValueWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeWriteSpi attributeWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.CategoryWriteSpi categoryWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionReadSpi collectionReadSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionWriteSpi collectionWriteSpi;
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi productTypeAttributeWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeWriteSpi productTypeWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantWriteSpi productVariantWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductWriteSpi productWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.TranslationWriteSpi translationWriteSpi;
    @MockitoBean
    private com.marketplace.pricing.spi.VoucherWriteSpi voucherWriteSpi;

    // Agent 3's checkout/order-command seams (PR #698): the slice package-scans
    // com.marketplace.app.graphql and wires CheckoutDataFetcher, so the shared
    // context cannot boot without these two mocked.
    @MockitoBean
    private com.marketplace.orders.spi.CheckoutSpi checkoutSpi;
    @MockitoBean
    private com.marketplace.orders.spi.OrderCommandSpi orderCommandSpi;

    private static final UUID ADDRESS_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private void actAs(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of(role))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new org.springframework.security.core.authority
                        .SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    private static String globalId(String type, UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString((type + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------------
    // Address lifecycle — the typed global-ID contract + the delegation
    // ---------------------------------------------------------------------

    @Test
    void accountAddressCreateDelegatesWithDecodedTypedGlobalIdAndRendersTheAddress() {
        actAs("agent-one@example.com", "CONSUMER");
        AddressView view = new AddressView(ADDRESS_ID, USER_ID, "One", "Caller", null,
                "Street 1", null, "City", null, "12345", "DE", null, "+49", false, true);
        when(addressManagementSpi.createForCaller(any(), any(), eq(null))).thenReturn(view);

        Object result = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($input: AddressInput!) {
                    accountAddressCreate(input: $input) {
                        address { id firstName lastName city country { code } isDefaultBillingAddress }
                        errors { code message }
                    }
                }
                """, "data.accountAddressCreate",
                Map.of("input", Map.of("firstName", "One", "lastName", "Caller",
                        "streetAddress1", "Street 1", "city", "City", "postalCode", "12345",
                        "country", "DE", "phone", "+49")));

        assertThat(result).isNotNull();
        verify(addressManagementSpi).createForCaller(any(), argThat(fields ->
                        "DE".equals(fields.country()) && "City".equals(fields.city())),
                eq(null));
    }

    @Test
    void accountAddressCreateRendersTheCallerUserThroughTheSharedMapper() {
        // The 2026-11-06 #806 review (Minor · Functional Correctness): the
        // payload user field — Saleor's accountAddress* payloads return the
        // affected user; the identity projection read renders it through
        // the SHARED user mapper (the CreateToken.user precedent).
        actAs("agent-one@example.com", "CONSUMER");
        AddressView view = new AddressView(ADDRESS_ID, USER_ID, "One", "Caller", null,
                "Street 1", null, "City", null, "12345", "DE", null, "+49", false, true);
        when(addressManagementSpi.createForCaller(any(), any(), eq(null))).thenReturn(view);
        when(identitySpi.findCurrentUserAccount(any())).thenReturn(Optional.of(new UserAccountView(
                USER_ID, "agent-one@example.com", false, true,
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-01-02T00:00:00Z"), java.util.Set.of("CONSUMER"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($input: AddressInput!) {
                    accountAddressCreate(input: $input) {
                        address { id }
                        user { id email isActive }
                        errors { code }
                    }
                }
                """, "data.accountAddressCreate",
                Map.of("input", Map.of("firstName", "One", "lastName", "Caller",
                        "streetAddress1", "Street 1", "city", "City", "postalCode", "12345",
                        "country", "DE")));

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) payload.get("user");
        assertThat(user).isNotNull();
        assertThat(user.get("email")).isEqualTo("agent-one@example.com");
        assertThat(user.get("isActive")).isEqualTo(true);
        // The typed global ID the SHARED mapper emits — never a raw UUID.
        assertThat(user.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("User:" + USER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void accountAddressCreateRendersTheDeprecatedAccountErrorsAliasAsAnEmptyList() {
        // The 2026-11-06 #806 round (Major · Data Integrity & Integration):
        // the deprecated `accountErrors` alias is NON-NULL in the SDL
        // ([AccountError!]!); a payload that set only `errors` answered a
        // null payload plus a GraphQL error for every client still
        // selecting the alias — AFTER the write had committed. All eight
        // address mutations now carry both fields.
        actAs("agent-one@example.com", "CONSUMER");
        AddressView view = new AddressView(ADDRESS_ID, USER_ID, "One", "Caller", null,
                "Street 1", null, "City", null, "12345", "DE", null, "+49", false, true);
        when(addressManagementSpi.createForCaller(any(), any(), eq(null))).thenReturn(view);
        when(identitySpi.findCurrentUserAccount(any())).thenReturn(Optional.of(new UserAccountView(
                USER_ID, "agent-one@example.com", false, true,
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-01-02T00:00:00Z"), java.util.Set.of("CONSUMER"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($input: AddressInput!) {
                    accountAddressCreate(input: $input) {
                        address { id }
                        accountErrors { code }
                        errors { code }
                    }
                }
                """, "data.accountAddressCreate",
                Map.of("input", Map.of("firstName", "One", "lastName", "Caller",
                        "streetAddress1", "Street 1", "city", "City", "postalCode", "12345",
                        "country", "DE")));

        // The payload itself must render (a null accountErrors would have
        // null-ed the WHOLE payload through the non-null field)…
        assertThat(payload).isNotNull();
        // …and the alias answers the same empty list `errors` does.
        assertThat(payload.get("accountErrors")).isEqualTo(List.of());
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    @Test
    void addressCreateRendersTheAddressedUserOnTheStaffLeg() {
        // The 2026-11-06 #806 review — the staff legs load the ADDRESSED
        // account (the created address's owner), not the calling admin.
        actAs("agent-one-admin@example.com", "ADMIN");
        AddressView view = new AddressView(ADDRESS_ID, USER_ID, "One", "Target", null,
                "Street 1", null, "City", null, "12345", "DE", null, "+49", false, false);
        when(addressManagementSpi.createForUser(any(), eq(USER_ID), any())).thenReturn(view);
        when(identitySpi.findUserAccountById(eq(USER_ID), any())).thenReturn(Optional.of(
                new UserAccountView(USER_ID, "target@example.com", false, true,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        java.time.Instant.parse("2026-01-02T00:00:00Z"), java.util.Set.of("CONSUMER"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($input: AddressInput!, $userId: ID!) {
                    addressCreate(input: $input, userId: $userId) {
                        address { id }
                        user { id email }
                        errors { code }
                    }
                }
                """, "data.addressCreate",
                Map.of("input", Map.of("firstName", "One", "lastName", "Target",
                        "streetAddress1", "Street 1", "city", "City", "postalCode", "12345",
                        "country", "DE"),
                        "userId", java.util.Base64.getEncoder()
                                .encodeToString(("User:" + USER_ID)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) payload.get("user");
        assertThat(user).isNotNull();
        assertThat(user.get("email")).isEqualTo("target@example.com");
        verify(identitySpi).findUserAccountById(eq(USER_ID), any());
    }

    @Test
    void addressQueryRejectsARawUuidWithTheCodecClassifiedError() {
        actAs("agent-one@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query { address(id: "%s") { id city } }
                """.formatted(ADDRESS_ID), "data.address"))
                .hasMessageContaining("Invalid global ID");
    }

    @Test
    void addressQueryRejectsAMistypedGlobalId() {
        actAs("agent-one@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query { address(id: "%s") { id city } }
                """.formatted(globalId("User", USER_ID)), "data.address"))
                .hasMessageContaining("Invalid global ID");
    }

    @Test
    void adminAddressCreateDelegatesThroughTheStaffManagedSeam() {
        actAs("agent-one-admin@example.com", "ADMIN");
        AddressView view = new AddressView(ADDRESS_ID, USER_ID, "A", "Customer", null,
                "Street", null, "City", null, null, "US", null, null, false, false);
        when(addressManagementSpi.createForUser(any(), eq(USER_ID), any())).thenReturn(view);

        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($input: AddressInput!, $userId: ID!) {
                    addressCreate(input: $input, userId: $userId) {
                        address { id country { code } }
                        errors { code }
                    }
                }
                """, "data.addressCreate.address.id",
                Map.of("input", Map.of("city", "City", "country", "US"),
                        "userId", globalId("User", USER_ID)));

        verify(addressManagementSpi).createForUser(any(), eq(USER_ID), any());
    }

    // ---------------------------------------------------------------------
    // Account lifecycle — the standing flows + the classified boundaries
    // ---------------------------------------------------------------------

    @Test
    void requestPasswordResetDelegatesAndAnswersTheEmptyErrorPayload() {
        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { requestPasswordReset(email: "who@example.com",
                        redirectUrl: "https://app.example.com/reset") {
                    errors { code }
                } }
                """, "data.requestPasswordReset.errors");
        verify(accountLifecycleSpi).requestPasswordReset("who@example.com");

        // The 2026-11-05 #806 round-3 pin: the deprecated accountErrors
        // alias stays selectable on every lifecycle payload (the SDL
        // declares it [AccountError!]! - a null answer would be a
        // non-null violation for any client selecting the alias).
        Object aliased = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { requestPasswordReset(email: "who@example.com",
                        redirectUrl: "https://app.example.com/reset") {
                    accountErrors { code }
                } }
                """, "data.requestPasswordReset.accountErrors");
        assertThat(aliased).isNotNull();
    }

    @Test
    void requestPasswordResetNormalizesTheAddressLikeRegistration() {
        // The 2026-11-05 #806 round-3 review (Minor · Functional
        // Correctness): registration lowercases the address before the
        // account is stored, so an uppercase-typed reset request must
        // resolve the SAME stored row — trim alone made the lookup miss
        // and the response still read as success (no email sent).
        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { requestPasswordReset(email: "  WHO@Example.COM ",
                        redirectUrl: "https://app.example.com/reset") {
                    errors { code }
                } }
                """, "data.requestPasswordReset.errors");
        verify(accountLifecycleSpi).requestPasswordReset("who@example.com");
    }

    @Test
    void confirmAccountNormalizesTheAddressLikeTheResetRequest() {
        // The 2026-11-06 #806 review (Minor · Functional Correctness): the
        // redemption must key on the SAME normalized address the token was
        // minted for — trim alone made the owner's case-SENSITIVE
        // requireTokenOwnedBy comparison reject a valid token.
        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { confirmAccount(email: "  WHO@Example.COM ",
                        token: "the-token") { errors { code } } }
                """, "data.confirmAccount.errors");
        verify(accountLifecycleSpi).confirmAccount("who@example.com", "the-token");
    }

    @Test
    void setPasswordNormalizesTheAddressLikeTheResetRequest() {
        // The 2026-11-06 #806 review — the reset redemption leg carries
        // the identical trim+lowercase(Locale.ROOT) pair.
        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { setPassword(email: "  WHO@Example.COM ",
                        token: "the-token", password: "new-password") { errors { code } } }
                """, "data.setPassword.errors");
        verify(accountLifecycleSpi).completePasswordReset("who@example.com", "the-token",
                "new-password");
    }

    @Test
    void sendConfirmationEmailResendsTheAuthenticatedCallersOwnVerification() {
        // The pinned Saleor 3.23.45 contract: AUTHENTICATED_USER operating
        // on info.context.user — the resend targets the CALLER's own
        // account, never an attacker-chosen address.
        actAs("agent-one@example.com", "CONSUMER");
        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { sendConfirmationEmail(channel: "",
                        redirectUrl: "https://store.example.com/confirm") { errors { code } } }
                """, "data.sendConfirmationEmail.errors");
        verify(accountLifecycleSpi).resendEmailVerification("agent-one@example.com");
    }

    /**
     * B10 (IDP-03/IDP-07 — the 2026-11-03 adversarial review, the
     * divergence-ledger closure): the account-lifecycle argument laws the
     * ledger row now records, pinned on the wire. The pinned 3.23.45
     * contract itself declares {@code channel: String!} and
     * {@code redirectUrl: String!} — the platform keeps the signatures
     * byte-faithful, and the BEHAVIOR is the recorded ADAPT: a real
     * channel slug answers the classified not-implemented error (the
     * platform is a single-channel store), while the blank string stays
     * the no-op the sibling test rides.
     */
    @Test
    void sendConfirmationEmailRejectsARealChannelSlugWithTheClassifiedError() {
        actAs("agent-one@example.com", "CONSUMER");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { sendConfirmationEmail(channel: "channel-1",
                        redirectUrl: "https://store.example.com/confirm") { errors { code } } }
                """, "data.sendConfirmationEmail.errors"))
                .hasMessageContaining("not implemented on this platform");
        verify(accountLifecycleSpi, never()).resendEmailVerification(any());
    }

    /**
     * B10's redirectUrl pin: the argument is accepted on the wire (the
     * pinned String! signature) and IGNORED in behavior — an
     * attacker-chosen URL must behave identically to the house URL. The
     * owning SPI call never receives it: there is no redirect surface by
     * construction (no open-redirect, no SSRF), exactly what the
     * divergence-ledger row records.
     */
    @Test
    void requestEmailChangeIgnoresTheRedirectUrlEntirely() {
        actAs("agent-one@example.com", "CONSUMER");
        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { requestEmailChange(newEmail: "next@example.com",
                        password: "aStrongPassword1!",
                        redirectUrl: "https://attacker.example/steal") {
                    errors { code }
                } }
                """, "data.requestEmailChange.errors");
        verify(accountLifecycleSpi).requestEmailChange(
                any(), eq("next@example.com"), eq("aStrongPassword1!"));
    }

    /**
     * The 2026-11-08 #806 round-9 review (Minor · Functional Correctness):
     * a successful {@code confirmEmailChange} answers the caller's CURRENT
     * account projection — the sibling authenticated roots'
     * ({@code requestEmailChange} et al.) own shape — carrying the NEW
     * email the redemption just landed, never the hard null that made a
     * successful redemption look like a failure.
     */
    @Test
    void confirmEmailChangeAnswersTheCurrentAccountWithTheNewEmail() {
        actAs("agent-one@example.com", "CONSUMER");
        when(identitySpi.findCurrentUserAccount(any())).thenReturn(Optional.of(new UserAccountView(
                USER_ID, "next@example.com", false, true,
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-01-02T00:00:00Z"), java.util.Set.of("CONSUMER"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { confirmEmailChange(token: "redemption-token") {
                    user { email isActive }
                    errors { code }
                } }
                """, "data.confirmEmailChange");

        verify(accountLifecycleSpi).confirmEmailChange(any(), eq("redemption-token"));
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) payload.get("user");
        assertThat(user).as("the redemption answers the caller's current account").isNotNull();
        assertThat(user.get("email")).isEqualTo("next@example.com");
    }

    /**
     * The shard review (2026-11-01, Minor · Security): bcrypt's ceiling is
     * 72 UTF-8 BYTES, never 72 characters — a 40-character Arabic password
     * is ~80 bytes and passed the old character-only count. The byte
     * ceiling must reject it (never a silent truncation).
     */
    @Test
    void accountRegisterRejectsACompactPasswordThatExceedsTheBcryptByteCeiling() {
        String fortyArabicCharacters = "ط".repeat(40);
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { accountRegister(input: { email: "bytes@example.com",
                        password: "%s" }) {
                    errors { code }
                } }
                """.formatted(fortyArabicCharacters), "data.accountRegister.errors"))
                .hasMessageContaining("72 bytes");
        verify(accountLifecycleSpi, never()).register(any(), any(), any());
    }

    @Test
    void accountRegisterRejectsANonBlankChannelLikeEverySiblingRoot() {
        // The 2026-11-05 #806 round-3 review (Minor): the input's channel
        // field declares the same single-channel contract every sibling
        // root rejects - accepting it silently let a client believe a
        // channel-scoped registration happened.
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { accountRegister(input: { email: "chan@example.com",
                        password: "aStrongPassword1!", channel: "channel-1" }) {
                    errors { code }
                } }
                """, "data.accountRegister.errors"))
                .hasMessageContaining("not implemented on this platform");
        verify(accountLifecycleSpi, never()).register(any(), any(), any());
    }

    @Test
    void accountRegisterDelegatesWithTheComposedDisplayName() {
        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { accountRegister(input: { email: "new@example.com",
                        password: "aStrongPassword1!", firstName: "New", lastName: "Member" }) {
                    errors { code }
                } }
                """, "data.accountRegister.errors");
        verify(accountLifecycleSpi).register(eq("new@example.com"), eq("aStrongPassword1!"),
                eq("New Member"));
    }

    @Test
    void accountAddressUpdateRejectsAMalformedIdThroughTheCodec() {
        actAs("agent-one@example.com", "CONSUMER");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { accountAddressUpdate(id: "not-a-global-id",
                        input: { city: "X", country: DE }) {
                    address { id }
                } }
                """, "data.accountAddressUpdate"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
    }

    @Test
    void passwordChangeDelegatesToTheOwningUseCase() {
        actAs("agent-one@example.com", "CONSUMER");
        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { passwordChange(newPassword: "anotherStrong1!",
                        oldPassword: "aStrongPassword1!") { errors { code } } }
                """, "data.passwordChange.errors");
        verify(accountLifecycleSpi).changePassword(any(), eq("aStrongPassword1!"),
                eq("anotherStrong1!"));
    }

    // ---------------------------------------------------------------------
    // Customer/staff management — the ADMIN boundary + the bulk semantics
    // ---------------------------------------------------------------------

    @Test
    void customerCreateRequiresAnEmail() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { customerCreate(input: { firstName: "No" }) { errors { code } } }
                """, "data.customerCreate"))
                .hasMessageContaining("A customer email is required");
    }

    @Test
    void customerCreateRendersTheDeprecatedAccountErrorsAliasAsAnEmptyList() {
        // The 2026-11-06 #806 round (Minor · Data Integrity & Integration):
        // the deprecated alias fields (accountErrors/staffErrors/shopErrors)
        // are NON-NULL in the SDL — a payload that set only `errors`
        // null-ed the whole mutation response after the committed write
        // for every client still selecting the alias.
        actAs("agent-one-admin@example.com", "ADMIN");
        when(userManagementSpi.createCustomer(any(), any(), anyBoolean())).thenReturn(USER_ID);
        when(identitySpi.findUserAccountById(eq(USER_ID), any()))
                .thenReturn(java.util.Optional.of(new com.marketplace.identity.spi.UserAccountView(
                        USER_ID, "created@example.com", false, true,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        java.time.Instant.parse("2026-01-02T00:00:00Z"), java.util.Set.of("CONSUMER"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { customerCreate(input: { email: "created@example.com",
                        firstName: "Created", lastName: "Customer" }) {
                    user { email }
                    accountErrors { code }
                    errors { code }
                } }
                """, "data.customerCreate");

        assertThat(payload).isNotNull();
        assertThat(payload.get("accountErrors")).isEqualTo(List.of());
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    @Test
    void staffNotificationRecipientCreateRendersTheDeprecatedShopErrorsAliasAsAnEmptyList() {
        // The same #806 round: the recipient family's own alias is
        // `shopErrors` — same non-null contract, same empty-list answer.
        actAs("agent-one-admin@example.com", "ADMIN");
        when(userManagementSpi.createRecipient(any(), isNull(), eq("watcher@example.com"), eq(true)))
                .thenReturn(new com.marketplace.identity.spi.RecipientView(
                        UUID.randomUUID(), null, "watcher@example.com", true));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { staffNotificationRecipientCreate(
                        input: { email: "watcher@example.com", active: true }) {
                    staffNotificationRecipient { email active }
                    shopErrors { code }
                    errors { code }
                } }
                """, "data.staffNotificationRecipientCreate");

        assertThat(payload).isNotNull();
        assertThat(payload.get("shopErrors")).isEqualTo(List.of());
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    @Test
    void customerBulkDeleteDecodesEveryTypedGlobalIdAndDelegates() {
        actAs("agent-one-admin@example.com", "ADMIN");
        when(userManagementSpi.bulkDeleteCustomers(any(), any())).thenReturn(2);
        Integer count = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($ids: [ID!]!) { customerBulkDelete(ids: $ids) { count errors { code } } }
                """, "data.customerBulkDelete.count",
                Map.of("ids", List.of(globalId("User", USER_ID),
                        globalId("User", UUID.randomUUID()))));
        assertThat(count).isEqualTo(2);
    }

    @Test
    void staffNotificationRecipientCreateResolvesTheUserProjection() {
        // The 2026-11-05 #806 round-3 review (Major · Functional
        // Correctness): the oneof's USER arm renders its owner — a
        // recipient created with a user id answers the user projection,
        // never a hardcoded null (the email is empty on user-owned rows,
        // so without the user the response carried NO owner at all).
        actAs("agent-one-admin@example.com", "ADMIN");
        UUID ownerId = UUID.randomUUID();
        when(userManagementSpi.createRecipient(any(), eq(ownerId), isNull(), eq(true)))
                .thenReturn(new com.marketplace.identity.spi.RecipientView(
                        UUID.randomUUID(), ownerId, null, true));
        when(identitySpi.findUserAccountById(eq(ownerId), any()))
                .thenReturn(java.util.Optional.of(new com.marketplace.identity.spi.UserAccountView(
                        ownerId, "owner@example.com", false, true,
                        java.time.Instant.now(), java.time.Instant.now(), java.util.Set.of("CONSUMER"))));

        Object response = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { staffNotificationRecipientCreate(input: { user: "%s", active: true }) {
                    staffNotificationRecipient { id email user { id email } }
                } }
                """.formatted(globalId("User", ownerId)),
                "data.staffNotificationRecipientCreate.staffNotificationRecipient");

        assertThat(response).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> recipient = (Map<String, Object>) response;
        assertThat(recipient.get("email")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) recipient.get("user");
        assertThat(user).isNotNull();
        assertThat(user.get("email")).isEqualTo("owner@example.com");
    }

    @Test
    void userUpdateRejectsBothSelectorsAtOnce() {
        // The 2026-11-05 #806 round-3 review (Major · Functional
        // Correctness): id + externalReference together is a client error —
        // the old externalReference-wins silently ignored the id the client
        // believed it had updated by.
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { customerUpdate(id: "%s",
                        externalReference: "owner@example.com", input: { firstName: "X" }) {
                    user { id }
                } }
                """.formatted(globalId("User", USER_ID)), "data.customerUpdate"))
                .hasMessageContaining("not both");
    }

    @Test
    void staffNotificationRecipientCreateSurfacesTheOwnerOneofValidation() {
        actAs("agent-one-admin@example.com", "ADMIN");
        when(userManagementSpi.createRecipient(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "Exactly one of the user reference or the email address is required"));
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { staffNotificationRecipientCreate(input: { active: true }) {
                    staffNotificationRecipient { id }
                } }
                """, "data.staffNotificationRecipientCreate"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Exactly one of the user reference or the email address");
    }

    @Test
    void userBulkSetActiveRejectsRawUuidsThroughTheCodec() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($ids: [ID!]!) { userBulkSetActive(ids: $ids, isActive: false) { count } }
                """, "data.userBulkSetActive", Map.of("ids",
                List.of(USER_ID.toString()))))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
    }

    // ---------------------------------------------------------------------
    // Customer types — the ADMIN-gated reads with the connection envelope
    // ---------------------------------------------------------------------

    @Test
    void customerTypesRejectsBackwardPaginationThroughTheClassifiedError() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query { customerTypes(last: 2, before: "cursor") { edges { node { id } } } }
                """, "data.customerTypes"))
                .hasMessageContaining("forward-only pagination");
    }

    @Test
    void customerTypesRejectsTheWhereFilterThroughTheClassifiedError() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query { customerTypes(where: { isDefault: true }) { edges { node { id } } } }
                """, "data.customerTypes"))
                .hasMessageContaining("where filter is not implemented");
    }

    @Test
    void customerTypesAnswersTheCursorValidationErrorOnTheDegeneratePage() {
        // The 2026-11-06 #806 review (Minor · Functional Correctness): the
        // cursor verdict precedes the degenerate page — a REAL encoded
        // cursor from a DIFFERENT scroll (the createdAt keys no
        // customerTypes sort carries) answers the classified validation
        // error even when the page size itself is empty, never a
        // success-shaped envelope that hides the malformed cursor.
        actAs("agent-one-admin@example.com", "ADMIN");
        String foreignCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(java.util.Map.of("createdAt",
                        java.time.Instant.parse("2026-10-01T00:00:00Z"),
                        "id", java.util.UUID.randomUUID())));
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query($after: String) { customerTypes(first: 0, after: $after) { edges { node { id } } } }
                """, "data.customerTypes", java.util.Map.of("after", foreignCursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor emitted by"
                        + " customerTypes with the same sortBy");
        verify(customerTypeSpi, never()).scroll(any(), any(), any(), any(), any());
    }

    /**
     * The 2026-11-08 #806 round-9 review (Minor · Functional Correctness):
     * the ADMIN verdict precedes the EMPTY-PAGE early return — a
     * non-admin caller sending {@code first: 0} answers the authorization
     * denial (the SPI's own read gate), never the success-shaped empty
     * connection that used to leak past the gate the scroll body alone
     * enforced.
     */
    @Test
    void customerTypesFirstZeroAnswersTheAdminDenialForANonAdminCaller() {
        actAs("agent-one@example.com", "CONSUMER");
        doThrow(new org.springframework.security.access.AccessDeniedException(
                "Customer type administration requires the ADMIN capability"))
                .when(customerTypeSpi).requireReadAccess(any());

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query { customerTypes(first: 0) { edges { node { id } } } }
                """, "data.customerTypes"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("ADMIN capability")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "AUTHZ-001")
                        .containsEntry("category", "authz"));
        verify(customerTypeSpi).requireReadAccess(any());
        verify(customerTypeSpi, never()).scroll(any(), any(), any(), any(), any());
    }

    /**
     * The 2026-11-06 session-9 #806 final review (Minor · Security &
     * Privacy): the B24 shared authenticated-caller wall — an anonymous
     * token answers isAuthenticated() == true, so the customerType roots
     * must refuse it exactly like the identity family's other fetchers
     * (the pre-login gate stays the HTTP boundary; this is the fetcher's
     * own defense-in-depth when invoked outside it).
     */
    @Test
    void customerTypesRefuseAnAnonymousTokenEvenThoughItAnswersAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.AnonymousAuthenticationToken(
                        "key", "anonymousUser",
                        org.springframework.security.core.authority.AuthorityUtils
                                .createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query { customerTypes(first: 1) { totalCount } }
                """, "data.customerTypes"))
                .hasMessageContaining("Authentication required");
    }

    // ---------------------------------------------------------------------
    // CMS — the page lifecycle + the navigation tree
    // ---------------------------------------------------------------------

    @Test
    void pageCreateRequiresATitle() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { pageCreate(input: { slug: "x", pageType: "%s" }) { errors { code } } }
                """.formatted(globalId("PageType", UUID.randomUUID())), "data.pageCreate"))
                .hasMessageContaining("A page title is required");
    }

    @Test
    void pagesRejectsTheSearchArgumentThroughTheClassifiedError() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query { pages(first: 5, search: "terms") { edges { node { id } } } }
                """, "data.pages"))
                .hasMessageContaining("search is not implemented");
    }

    /**
     * The 2026-11-08 #806 round-9 review (Minor · Functional Correctness):
     * the CMS read gate precedes the empty-page early return on BOTH
     * scroll roots — a non-admin caller sending {@code first: 0} answers
     * the authorization denial, never the success-shaped empty connection.
     */
    @Test
    void cmsScrollRootsFirstZeroAnswerTheAdminDenialForANonAdminCaller() {
        actAs("agent-one@example.com", "CONSUMER");
        doThrow(new org.springframework.security.access.AccessDeniedException(
                "CMS page administration requires the ADMIN capability"))
                .when(cmsPageSpi).requireReadAccess(any());

        for (String root : new String[] {"pages", "pageTypes"}) {
            assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                    query { %s(first: 0) { edges { node { id } } } }
                    """.formatted(root), "data." + root))
                    .as("%s must answer the authorization denial, not the empty page", root)
                    .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                    .hasMessageContaining("ADMIN capability")
                    .satisfies(ex -> assertThat(
                                    ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                            .getExtensions())
                            .containsEntry("errorCode", "AUTHZ-001")
                            .containsEntry("category", "authz"));
        }
        verify(cmsPageSpi, org.mockito.Mockito.times(2)).requireReadAccess(any());
        org.mockito.Mockito.verifyNoMoreInteractions(cmsPageSpi);
    }

    @Test
    void menuItemCreateUsesSaleorInputShapeAndDelegatesItsMenuAndParent() {
        actAs("agent-one-admin@example.com", "ADMIN");
        UUID menuId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID createdId = UUID.randomUUID();
        when(cmsNavigationSpi.createMenuItem(any(), eq(menuId), eq(parentId),
                eq("Home"), eq("/home"), isNull(), isNull()))
                .thenReturn(new CmsNavigationSpi.MenuItemView(createdId, menuId, parentId,
                        "Home", "/home", null, null, 0, Map.of()));

        Object result = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation {
                    menuItemCreate(input: {
                        name: "Home",
                        menu: "%s",
                        parent: "%s",
                        url: "/home"
                    }) {
                        menuItem { id name url menu { id } }
                        errors { code }
                    }
                }
                """.formatted(globalId("Menu", menuId), globalId("MenuItem", parentId)),
                "data.menuItemCreate");

        assertThat(result).isNotNull();
        verify(cmsNavigationSpi).createMenuItem(any(), eq(menuId), eq(parentId),
                eq("Home"), eq("/home"), isNull(), isNull());
    }

    @Test
    void menuItemCreateRejectsUnsupportedCollectionTargetsExplicitly() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation {
                    menuItemCreate(input: {
                        name: "Collection",
                        menu: "%s",
                        collection: "%s"
                    }) {
                        menuItem { id }
                    }
                }
                """.formatted(globalId("Menu", UUID.randomUUID()),
                        globalId("Collection", UUID.randomUUID())),
                "data.menuItemCreate"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Collection-backed menu items are not implemented");
    }

    @Test
    void pageTypeUpdateWithoutIdMatchesSaleorsCreateFallback() {
        actAs("agent-one-admin@example.com", "ADMIN");
        UUID createdId = UUID.randomUUID();
        CmsPageSpi.PageTypeView created = new CmsPageSpi.PageTypeView(
                createdId, "Created", "created", List.of());
        when(cmsPageSpi.createPageType(any(), eq("Created"), eq("created"), eq(List.of())))
                .thenReturn(created);

        Object result = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation {
                    pageTypeUpdate(id: null, input: { name: "Created", slug: "created" }) {
                        pageType { id name slug }
                        errors { code }
                    }
                }
                """, "data.pageTypeUpdate");

        assertThat(result).isNotNull();
        verify(cmsPageSpi).createPageType(any(), eq("Created"), eq("created"), eq(List.of()));
    }

    @Test
    void pageTypeUpdateWithoutIdRejectsRemoveAttributes() {
        // The 2026-11-06 #806 round review (Minor · Functional
        // Correctness): the CREATE leg has no existing page type to
        // remove attributes FROM — a non-empty removeAttributes used to
        // be silently ignored after the write committed; the nonsense
        // combination answers the classified validation error BEFORE
        // anything runs.
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation {
                    pageTypeUpdate(id: null, input: { name: "Created", slug: "created",
                            removeAttributes: ["%s"] }) {
                        pageType { id }
                        errors { code }
                    }
                }
                """.formatted(globalId("Attribute", UUID.randomUUID())), "data.pageTypeUpdate"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("the create leg cannot remove");
        verify(cmsPageSpi, never()).createPageType(any(), any(), any(), any());
    }

    @Test
    void menuItemDeleteRendersTheDeletedRowsOwnLevel() {
        // The 2026-11-06 #806 round review (Minor · Functional
        // Correctness): the deleted row is absent from the POST-deletion
        // tree, so its rendered `level` used to fall back to the default
        // 0 even for a deeply nested item — the level is derived from the
        // surviving parent chain now (parent level + 1).
        actAs("agent-one-admin@example.com", "ADMIN");
        UUID menuId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        CmsNavigationSpi.MenuItemView deleted = new CmsNavigationSpi.MenuItemView(
                deletedId, menuId, parentId, "Nested", "/nested", null, null, 1, java.util.Map.of());
        // The post-deletion tree: the parent survives at level 1 (its own
        // parent — the deleted row's grandparent — is the root's child).
        UUID grandparentId = UUID.randomUUID();
        CmsNavigationSpi.MenuItemView grandparent = new CmsNavigationSpi.MenuItemView(
                grandparentId, menuId, null, "Root Child", "/root-child", null, null, 1, java.util.Map.of());
        CmsNavigationSpi.MenuItemView parent = new CmsNavigationSpi.MenuItemView(
                parentId, menuId, grandparentId, "Parent", "/parent", null, null, 1, java.util.Map.of());
        when(cmsNavigationSpi.deleteMenuItem(any(), any(UUID.class))).thenReturn(deleted);
        when(cmsNavigationSpi.listMenuItems(any(), eq(menuId)))
                .thenReturn(List.of(grandparent, parent));

        Object level = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { menuItemDelete(id: "%s") { menuItem { id level } } }
                """.formatted(globalId("MenuItem", deletedId)),
                "data.menuItemDelete.menuItem.level");

        // The deleted row's parent sits at level 1 → the deleted row
        // itself renders its TRUE level 2 (never the fallback 0).
        assertThat(String.valueOf(level)).isEqualTo("2");
    }

    @Test
    void menuQueryWithoutAnyKeyAnswersTheClassifiedError() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query { menu { id } }
                """, "data.menu"))
                .hasMessageContaining("A menu ID, slug or name is required");
    }

    // ---------------------------------------------------------------------
    // The wave-11 #806 review (Major · Functional Correctness): the declared
    // Page.translation / MenuItem.translation fields — wired to the owning
    // read seam, with the row id as the Saleor translation identity.
    // The stubbed projections speak the owning console services' storage
    // currency — lower-case language codes ('ar'/'en', the (page, language)
    // column shape requireLanguage enforces) — while the wire query speaks
    // the SDL enum's upper-case form (AR/EN); the fetcher converts at the
    // boundary, so a stub keyed by the stored form proves the conversion.
    // ---------------------------------------------------------------------

    @Test
    void pageTranslationResolvesTheAddressedLanguageThroughTheOwningReadSeam() {
        actAs("agent-one-admin@example.com", "ADMIN");
        UUID pageId = UUID.randomUUID();
        UUID translationRowId = UUID.randomUUID();
        CmsPageSpi.PageTranslationView arabic = new CmsPageSpi.PageTranslationView(
                translationRowId, "ar", "ar-slug", "\u0639\u0646\u0648\u0627\u0646",
                "\u0645\u062d\u062a\u0648\u0649", "seo-ar", "seo-desc-ar");
        when(cmsPageSpi.findPageById(any(), eq(pageId))).thenReturn(Optional.of(
                new CmsPageSpi.PageView(pageId, UUID.randomUUID(), "About", "about",
                        "about", "About", "content", true, null, null, null, null,
                        Map.of("ar", arabic))));

        Object title = dgsQueryExecutor.executeAndExtractJsonPath("""
                query {
                    page(id: "%s") {
                        title
                        translation(languageCode: AR) { title language }
                    }
                }
                """.formatted(globalId("Page", pageId)), "data.page.translation.title");

        assertThat(title).isEqualTo("\u0639\u0646\u0648\u0627\u0646");
        // The root read AND the field-level completion both flow through the
        // SAME owning seam (findPageById) — the resolver re-reads the source.
        verify(cmsPageSpi, atLeastOnce()).findPageById(any(), eq(pageId));
    }

    @Test
    void pageTranslationAnswersTheHonestNullForAnUntranslatedLanguage() {
        actAs("agent-one-admin@example.com", "ADMIN");
        UUID pageId = UUID.randomUUID();
        when(cmsPageSpi.findPageById(any(), eq(pageId))).thenReturn(Optional.of(
                new CmsPageSpi.PageView(pageId, UUID.randomUUID(), "About", "about",
                        "about", "About", "content", true, null, null, null, null,
                        Map.of())));

        Object translation = dgsQueryExecutor.executeAndExtractJsonPath("""
                query {
                    page(id: "%s") {
                        translation(languageCode: AR) { title }
                    }
                }
                """.formatted(globalId("Page", pageId)), "data.page.translation");

        assertThat(translation).isNull();
    }

    @Test
    void menuItemTranslationResolvesTheAddressedLanguageThroughTheOwningReadSeam() {
        actAs("agent-one-admin@example.com", "ADMIN");
        UUID itemId = UUID.randomUUID();
        UUID translationRowId = UUID.randomUUID();
        when(cmsNavigationSpi.findMenuItemById(any(), eq(itemId))).thenReturn(Optional.of(
                new CmsNavigationSpi.MenuItemView(itemId, UUID.randomUUID(), null,
                        "Home", "/home", null, null, 0,
                        Map.of("ar", new CmsNavigationSpi.MenuItemTranslationView(
                                translationRowId, "ar", "\u0627\u0644\u0631\u0626\u064a\u0633\u064a\u0629")))));

        Object name = dgsQueryExecutor.executeAndExtractJsonPath("""
                query {
                    menuItem(id: "%s") {
                        name
                        translation(languageCode: AR) { name language }
                    }
                }
                """.formatted(globalId("MenuItem", itemId)), "data.menuItem.translation.name");

        assertThat(name).isEqualTo("\u0627\u0644\u0631\u0626\u064a\u0633\u064a\u0629");
        verify(cmsNavigationSpi, atLeastOnce()).findMenuItemById(any(), eq(itemId));
    }

    @Test
    void menuItemTranslationAnswersTheHonestNullForAnUntranslatedLanguage() {
        actAs("agent-one-admin@example.com", "ADMIN");
        UUID itemId = UUID.randomUUID();
        when(cmsNavigationSpi.findMenuItemById(any(), eq(itemId))).thenReturn(Optional.of(
                new CmsNavigationSpi.MenuItemView(itemId, UUID.randomUUID(), null,
                        "Home", "/home", null, null, 0, Map.of())));

        Object translation = dgsQueryExecutor.executeAndExtractJsonPath("""
                query {
                    menuItem(id: "%s") {
                        translation(languageCode: AR) { name }
                    }
                }
                """.formatted(globalId("MenuItem", itemId)), "data.menuItem.translation");

        assertThat(translation).isNull();
    }

    @Test
    void assignNavigationRejectsARawMenuIdThroughTheCodec() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { assignNavigation(menu: "raw-uuid", navigationType: MAIN) {
                    errors { code }
                } }
                """, "data.assignNavigation"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
    }

    // ---------------------------------------------------------------------
    // The tokens — the declared-but-unexecuted arguments stay classified
    // ---------------------------------------------------------------------

    @Test
    void tokenCreateRejectsTheAudienceArgumentThroughTheClassifiedError() {
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenCreate(audience: "custom:x", email: "a@example.com",
                        password: "pw") { token } }
                """, "data.tokenCreate"))
                .hasMessageContaining("audience is not implemented");
    }

    @Test
    void tokenRefreshRequiresTheRefreshToken() {
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenRefresh { token } }
                """, "data.tokenRefresh"))
                .hasMessageContaining("A refresh token is required");
    }

    // ---------------------------------------------------------------------
    // The rotation's wire contract — the successor rides the payload
    // ---------------------------------------------------------------------

    @Test
    void tokenRefreshReturnsTheSuccessorRefreshTokenOnTheWire() {
        UserAccountView account = new UserAccountView(USER_ID,
                "agent-one@example.com", false, true, java.time.Instant.now(),
                java.time.Instant.now(), java.util.Set.of("CONSUMER"));
        when(tokenGrantService.refresh("the-presented-refresh-token"))
                .thenReturn(new PlatformTokenGrantService.IssuedTokens(
                        "successor-access-token", "successor-refresh-token",
                        "successor-csrf-token", account));
        when(identitySpi.findCurrentUserAccount(any())).thenReturn(java.util.Optional.of(account));

        Object payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenRefresh(refreshToken: "the-presented-refresh-token") {
                    token refreshToken user { id email }
                } }
                """, "data.tokenRefresh");

        assertThat(payload).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) payload;
        // The rotation is one-time-use: the payload MUST carry the
        // successor — the presented token is dead after this call.
        assertThat(fields.get("token")).isEqualTo("successor-access-token");
        assertThat(fields.get("refreshToken")).isEqualTo("successor-refresh-token");
        // The 2026-11-05 #807 round: the payload's user is the ROTATED
        // account's own identity — not just "a user" — so the successor's
        // client binds the session to the right subject from the wire.
        assertThat(fields.get("user")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) fields.get("user");
        assertThat(user.get("id")).isEqualTo(globalId("User", USER_ID));
        assertThat(user.get("email")).isEqualTo("agent-one@example.com");
    }

    // ---------------------------------------------------------------------
    // AUTH-01 + TST-03 (the 2026-11-03 adversarial scan, P1): the token
    // surface's failures are PAYLOAD errors — the pinned Saleor 3.23.45
    // create_token.py / refresh_token.py contracts — never the generic
    // INTERNAL-001 transport envelope the resolver used to answer.
    // ---------------------------------------------------------------------

    @Test
    void tokenCreateAnswersTheSaleorPayloadErrorForBadCredentials() {
        // The pinned reference: a failed password grant answers
        // errors:[{field: "email", message: "Please, enter valid
        // credentials", code: INVALID_CREDENTIALS}].
        when(tokenGrantService.grant("legit@example.com", "wrong-password"))
                .thenThrow(new org.springframework.security.authentication
                        .BadCredentialsException("Bad credentials"));

        Object payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenCreate(email: "legit@example.com",
                        password: "wrong-password") {
                    token refreshToken csrfToken user { email }
                    errors { field message code }
                } }
                """, "data.tokenCreate");

        assertThat(payload).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) payload;
        assertThat(fields.get("token")).isNull();
        assertThat(fields.get("refreshToken")).isNull();
        assertThat(fields.get("csrfToken")).isNull();
        assertThat(fields.get("user")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) fields.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("field", "email")
                .containsEntry("message", "Please, enter valid credentials")
                .containsEntry("code", "INVALID_CREDENTIALS");
    }

    @Test
    void tokenCreateUnifiesUnknownEmailWrongPasswordAndDisabledAccount() {
        // The anti-enumeration contract (the recorded divergence row
        // "tokenCreate authentication rejection"): the provider's own
        // BadCredentialsException already hides the unknown-email
        // distinction; the fetcher's unified catch extends the same
        // discipline to the disabled account — all three legs answer the
        // IDENTICAL payload, byte for byte.
        when(tokenGrantService.grant("unknown@example.com", "whatever"))
                .thenThrow(new org.springframework.security.authentication
                        .BadCredentialsException("Bad credentials"));
        when(tokenGrantService.grant("known@example.com", "wrong-password"))
                .thenThrow(new org.springframework.security.authentication
                        .BadCredentialsException("Bad credentials"));
        when(tokenGrantService.grant("disabled@example.com", "correct-password"))
                .thenThrow(new org.springframework.security.authentication
                        .DisabledException("User is disabled"));

        Map<String, Object> unknownEmail = tokenCreatePayload("unknown@example.com", "whatever");
        Map<String, Object> wrongPassword = tokenCreatePayload("known@example.com", "wrong-password");
        Map<String, Object> disabledAccount = tokenCreatePayload("disabled@example.com", "correct-password");

        assertThat(unknownEmail).isEqualTo(wrongPassword);
        assertThat(wrongPassword).isEqualTo(disabledAccount);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) unknownEmail.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("code", "INVALID_CREDENTIALS");
    }

    @Test
    void tokenRefreshAnswersTheSaleorPayloadErrorForAnInvalidToken() {
        // The pinned reference (refresh_token.py): an invalid, expired or
        // revoked refresh token answers errors:[{field: "refreshToken",
        // message: "Incorrect refreshToken", code: JWT_INVALID_TOKEN}].
        when(tokenGrantService.refresh("a-consumed-refresh-token"))
                .thenThrow(new org.springframework.security.authentication
                        .BadCredentialsException("The refresh token is invalid, expired or revoked"));

        Object payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenRefresh(refreshToken: "a-consumed-refresh-token") {
                    token refreshToken user { email }
                    errors { field message code }
                } }
                """, "data.tokenRefresh");

        assertThat(payload).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) payload;
        assertThat(fields.get("token")).isNull();
        assertThat(fields.get("refreshToken")).isNull();
        assertThat(fields.get("user")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) fields.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("field", "refreshToken")
                .containsEntry("message", "Incorrect refreshToken")
                .containsEntry("code", "JWT_INVALID_TOKEN");
    }

    /** The AUTH-01 helper: the full tokenCreate payload for one leg. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> tokenCreatePayload(String email, String password) {
        return (Map<String, Object>) dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenCreate(email: "%s", password: "%s") {
                    token refreshToken csrfToken user { email }
                    errors { field message code }
                } }
                """.formatted(email, password), "data.tokenCreate");
    }

    @Test
    void tokenVerifyAnswersAStrictJsonPayloadDocument() throws Exception {
        // The integrator's security round: Map.toString() output is NOT
        // JSON — the payload must be the claims serialized through the
        // application's own ObjectMapper, parseable as a JSON object
        // with the Saleor contract's claim fields.
        when(tokenGrantService.verify("a-valid-token"))
                .thenReturn(PlatformTokenGrantService.VerifiedToken.valid(
                        new java.util.LinkedHashMap<>(Map.of(
                                "iss", "https://auth.example.com",
                                "sub", "agent-one@example.com",
                                "aud", List.of("marketplace-api"),
                                "roles", List.of("CONSUMER"),
                                "iat", 1777000000L,
                                "exp", 1777000900L,
                                "jti", "token-id-1")),
                        "agent-one@example.com"));
        // The 2026-11-05 #806 round-3 pin: the invalid verdict carries the
        // pinned Saleor 3.23.45 vocabulary (authentication/utils.py) - the
        // generic invalid sentence under the JWT_INVALID_TOKEN code.
        when(tokenGrantService.verify("not-a-real-jwt"))
                .thenReturn(PlatformTokenGrantService.VerifiedToken.invalid());
        when(tokenGrantService.verify("an-expired-token"))
                .thenReturn(PlatformTokenGrantService.VerifiedToken.signatureExpired());

        Object payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenVerify(token: "a-valid-token") { isValid payload } }
                """, "data.tokenVerify");

        assertThat(payload).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) payload;
        assertThat(fields.get("isValid")).isEqualTo(true);
        String document = (String) fields.get("payload");
        assertThat(document).isNotBlank();
        // The proof: the payload parses as a strict JSON document and
        // carries the claim contract (subject, issuer, audience, roles
        // — never a "{key=value}" toString artifact). Parsed through
        // the platform's own approved serializer (Jackson 3).
        assertThat(document).startsWith("{").endsWith("}").doesNotContain("=");
        tools.jackson.databind.JsonNode claims =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(document);
        assertThat(claims.get("sub").asText()).isEqualTo("agent-one@example.com");
        assertThat(claims.get("iss").asText()).isEqualTo("https://auth.example.com");
        assertThat(claims.get("aud").get(0).asText()).isEqualTo("marketplace-api");
        assertThat(claims.get("roles").get(0).asText()).isEqualTo("CONSUMER");
        assertThat(claims.has("exp")).isTrue();

        // The 2026-11-05 #806 round-3 pins: the invalid verdict answers
        // the accountErrors entry the pinned Saleor 3.23.45 contract
        // declares (verify_token.py's get_payload/get_user vocabulary) -
        // field "token", the failure sentence, the code - under BOTH the
        // errors field and its deprecated accountErrors alias.
        Object invalid = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenVerify(token: "not-a-real-jwt") {
                    isValid payload errors { field code message }
                } }
                """, "data.tokenVerify");
        assertThat(invalid).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> invalidFields = (Map<String, Object>) invalid;
        assertThat(invalidFields.get("isValid")).isEqualTo(false);
        assertThat(invalidFields.get("payload")).isNull();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> invalidErrors =
                (java.util.List<Map<String, Object>>) invalidFields.get("errors");
        assertThat(invalidErrors).hasSize(1);
        assertThat(invalidErrors.get(0).get("field")).isEqualTo("token");
        assertThat(invalidErrors.get(0).get("code")).isEqualTo("JWT_INVALID_TOKEN");
        assertThat(invalidErrors.get(0).get("message")).isEqualTo("Invalid token");

        // And the expired verdict answers its OWN sentence + code.
        Object expired = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenVerify(token: "an-expired-token") { isValid errors { field code message } } }
                """, "data.tokenVerify");
        assertThat(expired).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> expiredFields = (Map<String, Object>) expired;
        assertThat(expiredFields.get("isValid")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> expiredErrors =
                (java.util.List<Map<String, Object>>) expiredFields.get("errors");
        assertThat(expiredErrors).hasSize(1);
        assertThat(expiredErrors.get(0).get("field")).isEqualTo("token");
        assertThat(expiredErrors.get(0).get("code")).isEqualTo("JWT_SIGNATURE_EXPIRED");
        assertThat(expiredErrors.get(0).get("message")).isEqualTo("Signature has expired");

        // The deprecated accountErrors alias stays selectable (the SDL
        // declares it [AccountError!]!): a null answer would be a
        // non-null violation, not a silent alias.
        Object aliased = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { tokenVerify(token: "not-a-real-jwt") { accountErrors { field code } } }
                """, "data.tokenVerify");
        assertThat(aliased).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> aliasedFields = (Map<String, Object>) aliased;
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> aliasErrors =
                (java.util.List<Map<String, Object>>) aliasedFields.get("accountErrors");
        assertThat(aliasErrors).hasSize(1);
        assertThat(aliasErrors.get(0).get("code")).isEqualTo("JWT_INVALID_TOKEN");
    }

    @Test
    void accountUpdateRejectsTheImpersonationArgumentThroughTheClassifiedError() {
        actAs("agent-one@example.com", "CONSUMER");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { accountUpdate(customerId: "%s", input: { firstName: "X" }) { user { id } } }
                """.formatted(globalId("User", USER_ID)), "data.accountUpdate"))
                .hasMessageContaining("app impersonation");
    }

    /**
     * The 2026-11-04 #806 round (Major · Functional Correctness): the
     * identity store carries ONE composed display name, so a partial
     * Saleor-shaped name update (firstName alone, or lastName alone)
     * would compose from the input's single half and REPLACE the whole
     * stored name — silently dropping the other half. The honest contract
     * is both-or-neither through the same classified validation error the
     * impersonation argument answers.
     */
    @Test
    void accountUpdateRejectsThePartialNameUpdateThroughTheClassifiedError() {
        actAs("agent-one@example.com", "CONSUMER");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { accountUpdate(input: { firstName: "OnlyFirst" }) { user { id } } }
                """, "data.accountUpdate"))
                .hasMessageContaining("firstName and lastName together");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { accountUpdate(input: { lastName: "OnlyLast" }) { user { id } } }
                """, "data.accountUpdate"))
                .hasMessageContaining("firstName and lastName together");
    }
    /**
     * The 2026-11-02 #806 review (Minor · Stability): the payload
     * vocabulary translation's own contract promises the
     * {@code GRAPHQL_ERROR} fallback and a never-null return — an
     * absent native code (an SPI row result's null error code) must
     * answer the fallback, never the {@code Enum.valueOf} NPE the old
     * lookup threw past the catch ladder.
     */
    @Test
    void translateAnswersTheFallbackWhenTheNativeCodeIsAbsent() {
        assertThat(SaleorMutationErrors.translate(
                com.marketplace.app.graphql.generated.types.CustomerBulkUpdateErrorCode.class,
                (String) null))
                .isEqualTo(com.marketplace.app.graphql.generated.types.CustomerBulkUpdateErrorCode.GRAPHQL_ERROR);
        assertThat(SaleorMutationErrors.translate(
                com.marketplace.app.graphql.generated.types.ProductErrorCode.class,
                (String) null))
                .isEqualTo(com.marketplace.app.graphql.generated.types.ProductErrorCode.GRAPHQL_ERROR);
    }
}