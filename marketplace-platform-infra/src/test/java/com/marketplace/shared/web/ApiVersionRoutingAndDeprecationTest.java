package com.marketplace.shared.web;

import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B.1 (official-compliance plan §6 wave B) — the declared unit gate
 * "اختبار توجيه/إهمال" (routing/deprecation test): measures the FULL official
 * versioning contract of {@link ApiVersioningConfig} on a real MVC context —
 * the same {@code WebMvcConfigurer} channel the production application
 * applies (Boot's WebMvc auto-configuration collects every WebMvcConfigurer
 * bean; here {@code @EnableWebMvc}'s DelegatingWebMvcConfiguration collects
 * the very same bean directly).
 *
 * <p>Official reference — Spring Framework, Web on Servlet Stack › API
 * Versioning (docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/api-version.html):
 * <ul>
 *   <li>Resolver: {@code useRequestHeader("X-API-Version")} — the version is
 *       resolved from that request header.</li>
 *   <li>Default: {@code setDefaultVersion("1.0")} — "You can also specify a
 *       default version to use", so header-less requests route to 1.0 instead
 *       of failing with MissingApiVersionException (400).</li>
 *   <li>Validation: "If a request version is not supported,
 *       InvalidApiVersionException is raised resulting in a 400 response" —
 *       the supported set is initialized from declared versions in annotated
 *       controller mappings (the probe declares {@code 1.0}, the same
 *       declaration every Track-A controller carries).</li>
 *   <li>Deprecation: the {@code StandardApiVersionDeprecationHandler} "can set
 *       the 'Deprecation' 'Sunset' headers and 'Link' headers as defined in
 *       RFC 9745 and RFC 8594".</li>
 * </ul>
 *
 * <p>The probe controller mirrors the measured house shape exactly:
 * {@code @RequestMapping(value = ..., version = "1.0")} — seven Track-A
 * controllers carry this declaration today (AdminController, UserController,
 * AuthController, ReviewerPublicProfileController, ProviderFollowController,
 * BookingController, PaymentsController).
 */
class ApiVersionRoutingAndDeprecationTest {

    private static final ZonedDateTime DEPRECATION_DATE =
            ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime SUNSET_DATE =
            ZonedDateTime.of(2027, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final String MIGRATION_LINK =
            "https://developer.marketplace.com/api/migration";

    /**
     * The probe mirrors the measured Track-A controller declaration shape —
     * the version attribute is the official routing declaration, exactly as
     * the seven production controllers carry it.
     */
    @RestController
    static class ProbeController {
        @RequestMapping(value = "/api/v1/probe", method = RequestMethod.GET, version = "1.0")
        public String probe() {
            return "ok";
        }
    }

    /**
     * Builds the real MVC context with the real {@link ApiVersioningConfig}
     * bean and the probe, then hands it to MockMvc — the official test
     * channel ("Client support for API versioning is available also … for
     * testing in MockMvc and WebTestClient").
     */
    private static MockMvc mockMvc(MarketplaceProperties properties) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.register(ProbeConfiguration.class, ApiVersioningConfig.class, ProbeController.class);
        context.addBeanFactoryPostProcessor(factory ->
                factory.registerSingleton("marketplaceProperties", properties));
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static MarketplaceProperties properties(Map<String, MarketplaceProperties.ApiVersioning.Deprecation> deprecations) {
        return new MarketplaceProperties(
                new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                new MarketplaceProperties.Security(
                        new MarketplaceProperties.Security.Jwt(
                                new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", "", ""),
                                "marketplace-api"),
                        new MarketplaceProperties.Security.Session(2, false),
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", "", "", ""),
                                new MarketplaceProperties.Security.OAuth2.PublicClient("", "")),
                        new MarketplaceProperties.Security.Pseudonymization("", List.of()),
                        new MarketplaceProperties.Security.AdminSeed("")),
                new MarketplaceProperties.ApiVersioning(deprecations));
    }

    @Nested
    class RoutingContract {

        @Test
        void headerlessRequestRoutesToTheDefaultVersion() throws Exception {
            mockMvc(properties(Map.of()))
                    .perform(get("/api/v1/probe"))
                    .andExpect(status().isOk());
        }

        @Test
        void declaredVersionRoutes() throws Exception {
            mockMvc(properties(Map.of()))
                    .perform(get("/api/v1/probe").header("X-API-Version", "1.0"))
                    .andExpect(status().isOk());
        }

        @Test
        void unsupportedVersionIsRejectedWith400() throws Exception {
            // "If a request version is not supported, InvalidApiVersionException
            // is raised resulting in a 400 response" — the supported set is
            // initialized from the declared mapping versions ({1.0} here, the
            // same set the production surface declares).
            mockMvc(properties(Map.of()))
                    .perform(get("/api/v1/probe").header("X-API-Version", "99.0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void unparsableVersionIsRejectedWith400() throws Exception {
            mockMvc(properties(Map.of()))
                    .perform(get("/api/v1/probe").header("X-API-Version", "not-a-version"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class DeprecationPolicy {

        @Test
        void noDeprecationConfiguredMeansNoHintsAndNoHandler() throws Exception {
            // The zero-change guard: empty map = no deprecation handler is
            // registered at all — the live 1.0 surface stays hint-free.
            mockMvc(properties(Map.of()))
                    .perform(get("/api/v1/probe").header("X-API-Version", "1.0"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Deprecation"))
                    .andExpect(header().doesNotExist("Sunset"))
                    .andExpect(header().doesNotExist("Link"));
        }

        @Test
        void deprecatedVersionCarriesRfc9745AndRfc8594Hints() throws Exception {
            Map<String, MarketplaceProperties.ApiVersioning.Deprecation> deprecations = deprecations();

            mockMvc(properties(deprecations))
                    .perform(get("/api/v1/probe").header("X-API-Version", "1.0"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Deprecation"))
                    .andExpect(header().exists("Sunset"))
                    .andExpect(header().string("Link", org.hamcrest.Matchers.containsString(MIGRATION_LINK)));

            // The dates travel in the headers' WIRE formats — pinned by
            // round-trip parsing, not substring: RFC 9745's Deprecation value
            // is "@" + Unix seconds (measured: "@1793491200"), and RFC 8594's
            // Sunset value is an IMF-fixdate. Both must parse back to the
            // exact configured instants — the contract callers rely on.
            var result = mockMvc(properties(deprecations))
                    .perform(get("/api/v1/probe").header("X-API-Version", "1.0"))
                    .andReturn();
            String deprecation = result.getResponse().getHeader("Deprecation");
            String sunset = result.getResponse().getHeader("Sunset");
            assertThat(deprecation).startsWith("@");
            assertThat(java.time.Instant.ofEpochSecond(
                    Long.parseLong(deprecation.substring(1))))
                    .isEqualTo(DEPRECATION_DATE.toInstant());
            assertThat(java.time.ZonedDateTime.parse(
                    sunset, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                    .isEqualTo(SUNSET_DATE.toInstant());
        }

        @Test
        void headerlessDefaultRouteAlsoCarriesTheDeprecationHints() throws Exception {
            // The default version resolves to 1.0 — the headerless majority of
            // today's callers must see the same managed hints when 1.0 is
            // deprecated (the deprecation follows the RESOLVED request version).
            mockMvc(properties(deprecations()))
                    .perform(get("/api/v1/probe"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Deprecation"))
                    .andExpect(header().exists("Sunset"));
        }
    }

    private static Map<String, MarketplaceProperties.ApiVersioning.Deprecation> deprecations() {
        return Map.of("1.0", new MarketplaceProperties.ApiVersioning.Deprecation(
                DEPRECATION_DATE, SUNSET_DATE, MIGRATION_LINK));
    }

    @Configuration
    @EnableWebMvc
    static class ProbeConfiguration {
        // The MVC infrastructure (RequestMappingHandlerMapping with the
        // ApiVersionStrategy) — ApiVersioningConfig registers separately as a
        // WebMvcConfigurer bean: the production channel.
    }
}
