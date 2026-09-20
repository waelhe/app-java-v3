package com.marketplace.edge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class EdgeContextTest {

    // Official-docs basis (all three verified 2026-09-20 on the exact versions we run):
    // (1) Spring Security 7.1.1 reference, "OAuth2 Client / Core Interfaces and Classes":
    // "You can initially configure a ClientRegistration by using discovery of an OpenID
    // Connect Provider's Configuration endpoint" — the code "queries, in series,
    // [issuer]/.well-known/openid-configuration ... stopping at the first to return
    // a 200 response." With no live SAS issuer in unit tests that live query fails
    // (measured: Connection refused), so the context cannot boot on issuer-uri here.
    // https://docs.spring.io/spring-security/reference/servlet/oauth2/client/core.html
    // (2) Spring Boot 4.1.1 API, @ConditionalOnMissingBean: "@Conditional that only
    // matches when no beans meeting the specified requirements are already contained
    // in the BeanFactory" — hence a test-scope ClientRegistrationRepository bean
    // backs off OAuth2ClientConfigurations$ClientRegistrationRepositoryConfiguration
    // and its startup discovery entirely.
    // https://docs.spring.io/spring-boot/docs/current/api/org/springframework/boot/autoconfigure/condition/ConditionalOnMissingBean.html
    // (3) Spring Framework 7.0.9 API, @MockitoBean: "an annotation that can be used in
    // test classes to override a bean in the test's ApplicationContext with a Mockito
    // mock" — the official override mechanism (same idiom as L20/L24 @MockitoBean).
    // https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/test/context/bean/override/mockito/MockitoBean.html
    // Full login/relay flows are covered by the S2/S3 ITs (plan Task 6) with stubbed
    // tokens instead of a live issuer.
    @MockitoBean
    ClientRegistrationRepository clientRegistrationRepository;

    @Test
    void contextLoads() {
    }
}
