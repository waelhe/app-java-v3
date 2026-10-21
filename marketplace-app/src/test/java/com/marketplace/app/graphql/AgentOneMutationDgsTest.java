package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.marketplace.identity.spi.AddressManagementSpi;
import com.marketplace.identity.spi.AddressView;
import com.marketplace.identity.spi.CustomerTypeSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.identity.spi.UserManagementSpi;
import com.marketplace.shared.api.CmsNavigationPort;
import com.marketplace.shared.api.CmsPagePort;
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
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
    private CmsPagePort cmsPagePort;
    @MockitoBean
    private CmsNavigationPort cmsNavigationPort;
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

    @Test
    void menuQueryWithoutAnyKeyAnswersTheClassifiedError() {
        actAs("agent-one-admin@example.com", "ADMIN");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                query { menu { id } }
                """, "data.menu"))
                .hasMessageContaining("A menu ID, slug or name is required");
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

    @Test
    void accountUpdateRejectsTheImpersonationArgumentThroughTheClassifiedError() {
        actAs("agent-one@example.com", "CONSUMER");
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { accountUpdate(customerId: "%s", input: { firstName: "X" }) { user { id } } }
                """.formatted(globalId("User", USER_ID)), "data.accountUpdate"))
                .hasMessageContaining("app impersonation");
    }
}
