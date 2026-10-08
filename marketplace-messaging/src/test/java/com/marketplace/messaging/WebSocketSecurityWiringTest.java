package com.marketplace.messaging;

import java.util.List;

import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.security.messaging.web.csrf.CsrfChannelInterceptor;
import org.springframework.security.messaging.web.csrf.XorCsrfChannelInterceptor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §7-а of the Spring Security 7.1.1 compliance wave — the guard for the
 * documented manual WebSocket wiring. The reference's "Disable CSRF within
 * WebSockets" listing prescribes exactly this shape when CSRF must be off:
 * no {@code @EnableWebSocketSecurity} (its CSRF leg is "not configurable"),
 * the Spring Security components added by hand — a
 * {@code SecurityContextChannelInterceptor} and an
 * {@code AuthorizationChannelInterceptor} (with the
 * {@code SpringAuthorizationEventPublisher}) on the client inbound channel,
 * plus the {@code AuthenticationPrincipalArgumentResolver}.
 *
 * <p>This slice boots the REAL broker machinery ({@code WebSocketConfig}'s
 * {@code @EnableWebSocketMessageBroker}) with the REAL security wiring
 * ({@code WebSocketSecurityConfig} + {@code WebSocketAuthorizationConfig})
 * and pins three facts:</p>
 * <ol>
 *   <li>the inbound channel's interceptor chain is exactly
 *       [JWT lifter, identity translator, SecurityContext, Authorization] —
 *       the documented security interceptors present and AFTER the lifter
 *       that lifts the CONNECT's bearer into the principal;</li>
 *   <li>NO CSRF interceptor of any kind is registered — the deleted
 *       deviation (the undocumented {@code csrfChannelInterceptor} bean-name
 *       override of {@code @EnableWebSocketSecurity}'s internal
 *       {@code getBeanOrNull} lookup) cannot silently return;</li>
 *   <li>no WebSocket security config class carries the annotation anymore —
 *       the no-CSRF state is the documented posture of this wiring, not an
 *       override of something enabled.</li>
 * </ol>
 */
@SpringJUnitConfig({ WebSocketConfig.class, WebSocketSecurityConfig.class,
        WebSocketAuthorizationConfig.class, WebSocketSecurityWiringTest.WiringDeps.class })
class WebSocketSecurityWiringTest {

    @Autowired
    @Qualifier("clientInboundChannel")
    private AbstractMessageChannel clientInboundChannel;

    @Test
    void inboundChannelCarriesTheDocumentedSecurityInterceptorsAfterTheLifters() {
        List<ChannelInterceptor> interceptors = clientInboundChannel.getInterceptors();

        // The framework's own ImmutableMessageChannelInterceptor occupies the
        // head of the chain (AbstractMessageBrokerConfiguration registers it
        // before invoking any configurer), so the load-bearing property is
        // the RELATIVE order: JWT lifter, then identity translator, then the
        // documented SecurityContext interceptor, then the documented
        // Authorization interceptor — each strictly after the previous one.
        int jwtLifter = indexOf(interceptors, JwtChannelAuthenticationInterceptor.class);
        int identityTranslator = indexOf(interceptors, WebSocketIdentityChannelInterceptor.class);
        int securityContext = indexOf(interceptors, SecurityContextChannelInterceptor.class);
        int authorization = indexOf(interceptors, AuthorizationChannelInterceptor.class);

        assertThat(jwtLifter).as("the JWT lifter registers").isGreaterThanOrEqualTo(0);
        assertThat(identityTranslator).as("the identity translator registers").isGreaterThanOrEqualTo(0);
        assertThat(securityContext).as("the documented SecurityContextChannelInterceptor registers").isGreaterThanOrEqualTo(0);
        assertThat(authorization).as("the documented AuthorizationChannelInterceptor registers").isGreaterThanOrEqualTo(0);

        assertThat(jwtLifter).as("the JWT lifter stays first among the configurers' interceptors (the load-bearing class-level @Order)")
                .isLessThan(identityTranslator);
        assertThat(identityTranslator).as("the identity translator follows the lifter")
                .isLessThan(securityContext);
        assertThat(securityContext).as("the security context populates after the lifters")
                .isLessThan(authorization);
    }

    @Test
    void noCsrfInterceptorIsRegisteredAnywhereInTheChain() {
        List<ChannelInterceptor> interceptors = clientInboundChannel.getInterceptors();

        assertThat(interceptors)
                .noneMatch(i -> i instanceof CsrfChannelInterceptor)
                .noneMatch(i -> i instanceof XorCsrfChannelInterceptor)
                .noneMatch(i -> i.getClass().getSimpleName().equals("WebSocketCsrfConfiguration"));
    }

    @Test
    void theSecurityWiringCarriesNoEnableWebSocketSecurityAnnotation() {
        assertThat(WebSocketSecurityConfig.class.isAnnotationPresent(EnableWebSocketSecurity.class))
                .as("the documented manual path replaces the annotation, whose CSRF leg is 'not configurable'")
                .isFalse();
        assertThat(WebSocketAuthorizationConfig.class.isAnnotationPresent(EnableWebSocketSecurity.class))
                .isFalse();
        assertThat(WebSocketConfig.class.isAnnotationPresent(EnableWebSocketSecurity.class))
                .isFalse();
    }

    private static int indexOf(List<ChannelInterceptor> interceptors, Class<?> type) {
        for (int i = 0; i < interceptors.size(); i++) {
            if (type.isInstance(interceptors.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The slice's dependencies: the conversation repository the subscription
     * guard consults, the guard itself (a production @Component, declared
     * here because the slice registers only the three explicit configuration
     * classes), and the marketplace properties the broker endpoint reads
     * (allowed origins) — minimal values, same constructor shape as
     * production.
     */
    @Configuration
    static class WiringDeps {

        @Bean
        ConversationRepository conversationRepository() {
            return org.mockito.Mockito.mock(ConversationRepository.class);
        }

        @Bean
        ConversationSubscriptionAuthorizationManager conversationSubscriptionAuthorizationManager(
                ConversationRepository conversationRepository) {
            return new ConversationSubscriptionAuthorizationManager(conversationRepository);
        }

        @Bean
        MarketplaceProperties marketplaceProperties() {
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
                            null));
        }
    }
}
