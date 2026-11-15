package com.marketplace.shared.email;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.thymeleaf.TemplateEngine;

/**
 * Production email transport backed by Resend's HTTPS Email API.
 *
 * <p>Boot's RestClient auto-configuration is used deliberately; no
 * SMTP socket or provider-specific Java SDK is needed.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration",
        "org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration"
})
@ConditionalOnProperty(prefix = "marketplace.mail", name = "provider", havingValue = "resend")
@EnableConfigurationProperties({MailChannelProperties.class, ResendProperties.class})
public class ResendEmailSenderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender resendEmailSender(
            RestClient.Builder restClientBuilder,
            TemplateEngine templateEngine,
            MailChannelProperties mailProperties,
            ResendProperties resendProperties) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        RestClient restClient = restClientBuilder
                .baseUrl("https://api.resend.com")
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + resendProperties.apiKey())
                .build();

        return new ResendEmailSender(restClient, templateEngine, mailProperties);
    }
}
