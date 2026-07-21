package com.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.authorization.EnableMultiFactorAuthentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;

/**
 * Enables multi-factor authentication (MFA) requiring both password and
 * one-time token (OTT) factors.
 *
 * <p>Reference: Spring Security 7.1 — Multi-Factor Authentication:
 * "@EnableMultiFactorAuthentication makes it easy to enable multifactor
 * authentication."
 * https://docs.spring.io/spring-security/reference/servlet/authentication/mfa.html
 */
@EnableMultiFactorAuthentication(authorities = {
        FactorGrantedAuthority.PASSWORD_AUTHORITY,
        FactorGrantedAuthority.OTT_AUTHORITY
})
@SpringBootApplication
@Modulithic
@EnableConfigurationProperties(MarketplaceProperties.class)
public class MarketplaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceApplication.class, args);
    }
}
