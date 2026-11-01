package com.marketplace.app.graphql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The wave-4 review's ordering + budget-isolation contract over the REAL
 * chain: the anonymous {@code accountDelete} flood is rejected by its own
 * per-caller budget BEFORE the shared {@code graphqlAccountMutation}
 * permit is ever consulted — so the authenticated customer/staff admin
 * mutations keep their budget while one anonymous caller floods.
 *
 * <p><b>The regression this pins (the wave-4 review, Major · Stability &
 * Availability):</b> {@code accountDelete}/{@code confirmEmailChange}
 * were anonymous-reachable (PreLoginGraphQlGate PRE_LOGIN_OPERATIONS)
 * yet rode the SHARED authenticated budget with no per-caller guard, and
 * the body-level guard call ran AFTER the shared {@code @RateLimiter}
 * advice had already acquired the shared permit — one anonymous flood
 * exhausted the documented {@code customer*}/{@code staff*}/{@code
 * address*} budget for every user. The fix is two-layered: the roots
 * ride the anonymous surface with {@link PerCallerBudget}, and the
 * aspect's ORDER (100) precedes resilience4j's RateLimiterAspect (405),
 * so a per-caller rejection never reaches the shared limiter.</p>
 *
 * <p><b>Measured here:</b> twelve anonymous {@code accountDelete}
 * attempts from one caller — the later ones answer the classified RL-001
 * rate-limit error at the response level (the root throws before any
 * payload is built), and an authenticated {@code customerCreate} (the
 * {@code graphqlAccountMutation} surface) still executes its domain flow
 * without any rate-limit error — the shared budget survived the flood
 * untouched.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AccountAnonymousBudgetOrderingIntegrationTest {

    private static final String ADMIN_SUBJECT = "saleor-budget-ordering-admin";
    private static final String ADMIN_EMAIL = "saleor-budget-ordering-admin@example.com";
    private static final UUID ADMIN_ROW_ID = UUID.fromString(
            "00000000-0000-0000-0000-0000000b4d67");
    private static final String RL_001 = "RL-001";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"})
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    @BeforeEach
    void seedAdminPrincipal() {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", ADMIN_ROW_ID);
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'ADMIN')
                """, ADMIN_ROW_ID, ADMIN_SUBJECT, ADMIN_EMAIL, "Budget Ordering Admin");
    }

    private boolean carriesRateLimit(HttpGraphQlTester.Response response) {
        return response.errors().getErrors().stream()
                .anyMatch(error -> RL_001.equals(
                        String.valueOf(error.getExtensions().get("errorCode"))));
    }

    @Test
    void anAnonymousAccountDeleteFloodIsRejectedByItsOwnBudgetAndTheSharedAuthenticatedBudgetSurvives() {
        HttpGraphQlTester anonymous = testerFor(null, null);

        // Twelve attempts from ONE anonymous caller: the per-caller budget
        // (perCallerGrant, 10/min fail-fast) rejects the tail — the exact
        // classified RL-001 sentence, never limiter internals.
        int rateLimited = 0;
        for (int attempt = 0; attempt < 12; attempt++) {
            var response = anonymous.document("""
                    mutation {
                        accountDelete(token: "not-a-real-deletion-right-%d") {
                            errors { code }
                        }
                    }
                    """.formatted(attempt)).execute();
            if (carriesRateLimit(response)) {
                rateLimited++;
            }
        }
        assertThat(rateLimited)
                .as("the anonymous flood's tail must be rejected by the caller's own budget")
                .isGreaterThanOrEqualTo(1);

        // The authenticated customer/staff surface (graphqlAccountMutation)
        // keeps its budget: the flood never reached it (the aspect's order
        // consumes the per-caller permit BEFORE the shared limiter advice).
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        var customer = admin.document("""
                mutation {
                    customerCreate(input: { email: "budget-ordering-%s@example.com" }) {
                        user { id }
                        errors { code }
                    }
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8))).execute();
        assertThat(carriesRateLimit(customer))
                .as("the shared authenticated budget must be untouched by the anonymous flood")
                .isFalse();
        assertThat(customer.path("customerCreate.user.id").entity(String.class).get())
                .isNotBlank();
    }

    private HttpGraphQlTester testerFor(String subject, String role) {
        HttpGraphQlTester.Builder builder = HttpGraphQlTester.builder(
                WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql"));
        if (subject == null) {
            return builder.build();
        }
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of(role))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);
        return builder.defaultHeader("Authorization", "Bearer test-token").build();
    }
}
