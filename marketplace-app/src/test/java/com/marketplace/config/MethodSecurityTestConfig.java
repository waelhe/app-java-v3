package com.marketplace.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Test configuration that enables method security ({@code @PreAuthorize})
 * for use with {@code @WebMvcTest} slice tests.
 *
 * <p>{@code @WebMvcTest} does not load {@code @EnableMethodSecurity} by
 * default because it only loads the web slice. This config bridges that
 * gap by enabling method security so that {@code @WithMockUser}
 * authentication flows through to the method-level {@code @PreAuthorize}
 * checks.
 *
 * <p>Spring Boot's default security auto-configuration (loaded by
 * {@code @WebMvcTest}) provides a {@code SecurityFilterChain} that allows
 * authenticated users. Combined with {@code @EnableMethodSecurity}, this
 * lets {@code @PreAuthorize} rules be enforced in slice tests.
 *
 * <p>Reference: Spring Security 7.1 — Testing Method Security:
 * "Spring Security hooks into Spring Test support through the
 * WithSecurityContextTestExecutionListener, which ensures that our tests
 * are run with the correct user."
 * https://docs.spring.io/spring-security/reference/servlet/test/method.html
 *
 * <p>Reference: Spring Security 7.1 — Method Security:
 * "If the user does not have the required authority, Spring Security
 * returns a 403 Forbidden HTTP status code."
 * https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityTestConfig {
}
