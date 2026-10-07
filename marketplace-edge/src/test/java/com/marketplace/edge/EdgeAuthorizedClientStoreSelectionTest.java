package com.marketplace.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production wiring for the TokenRelay token store, no HTTP and no server:
 * the {@code OAuth2AuthorizedClientService} bean must be the Redis-backed
 * implementation (the official remedy for the in-memory default — Gateway
 * Server MVC TokenRelay reference), and the repository must arrive through
 * Boot's own automatic management — OAuth2ClientWebSecurityAutoConfiguration
 * is armed by the service bean's very presence (class-level
 * ConditionalOnBean, measured on the 4.1.1 bytecode) — proving the shared
 * store rides the framework's wiring untouched, not a hand-declared copy.
 */
@SpringBootTest
@ActiveProfiles("test")
class EdgeAuthorizedClientStoreSelectionTest {

    @MockitoBean
    ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    ApplicationContext context;

    @Test
    void authorizedClientServiceIsRedisBacked() {
        assertThat(context.getBean(OAuth2AuthorizedClientService.class))
                .as("TokenRelay token store must be the shared Redis implementation, never the in-memory default")
                .isInstanceOf(RedisOAuth2AuthorizedClientService.class);
    }

    @Test
    void authorizedClientRepositoryIsProvidedAutomaticallyByBoot() {
        assertThat(context.getBean(OAuth2AuthorizedClientRepository.class))
                .as("the repository must be Boot's own auto-configured servlet type, armed by the service bean alone")
                .isInstanceOf(AuthenticatedPrincipalOAuth2AuthorizedClientRepository.class);
    }
}
