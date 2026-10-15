package com.marketplace.app.graphql;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
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

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The observability verification the GraphQL target design demands
 * (§8/Phase 1 item 6: "Verify and enable GraphQL observations" — as a
 * measured fact, not an assumption): Spring Boot 4.1.1's
 * {@code GraphQlObservationAutoConfiguration} is
 * {@code @ConditionalOnBean(ObservationRegistry)} and declares the
 * {@code GraphQlObservationInstrumentation} as a plain {@code Instrumentation}
 * bean — the same collection point the limit instrumentations ride — so
 * with the app's actuator/micrometer stack the request-level
 * {@code graphql.request} observation must fire on every operation.
 *
 * <p>The measurement is end-to-end and honest about the wiring it proves:
 * a test {@link ObservationHandler} bean (picked up by Boot's
 * ObservationRegistry auto-configuration — handlers are collected into the
 * registry the framework builds, not a parallel one) records every
 * observation NAME that stops, and a real query over the real HTTP
 * transport must leave the {@code graphql.request} observation in that
 * list. Field-level ({@code graphql.datafetcher}) observations ride the
 * same instrumentation; their absence here would mean the registry never
 * saw the request at all, which is the contract under test.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class GraphQlObservationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @LocalServerPort
    private int port;

    @MockitoBean
    private com.marketplace.catalog.CatalogService catalogService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private HttpGraphQlTester graphQlTester;

    @BeforeEach
    void setUpGraphQlTester() {
        Jwt testJwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "test-user")
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of("PROVIDER"))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(testJwt);

        when(catalogService.findAll(any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(0), 0));

        graphQlTester = HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    /**
     * A real GraphQL request over the real transport must produce the
     * {@code graphql.request} observation through the auto-configured
     * instrumentation — the framework's own documented observation name
     * (Spring for GraphQL › Observability).
     */
    @Test
    void aRequestEmitsTheGraphqlRequestObservation() {
        graphQlTester.document("query { services(first: 1) { edges { node { id } } } }")
                .execute()
                .errors().verify();

        assertThat(ObservationRecorder.names)
                .as("the auto-configured GraphQlObservationInstrumentation must observe every request")
                .contains("graphql.request");
    }

    /**
     * The data-fetcher observation rides the same instrumentation — a
     * controller-backed field must emit {@code graphql.datafetcher}.
     */
    @Test
    void aFieldEmitsTheDataFetcherObservation() {
        graphQlTester.document("query { services(first: 1) { edges { node { id } } } }")
                .execute()
                .errors().verify();

        assertThat(ObservationRecorder.names).contains("graphql.datafetcher");
    }

    /**
     * Collects the observation names the auto-configured registry actually
     * dispatches — a handler bean is the documented extension point Boot's
     * ObservationRegistry auto-configuration collects.
     */
    @TestConfiguration
    static class ObservationRecorder {

        static final List<String> names = new CopyOnWriteArrayList<>();

        @Bean
        ObservationHandler<Observation.Context> observationNameCollector() {
            return new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                    names.add(context.getName());
                }
            };
        }
    }
}
