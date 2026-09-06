package com.marketplace.app.i18n;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.api.GlobalExceptionHandler;
import com.marketplace.shared.api.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * i18n layer proof (roadmap B4): the real production bundles
 * (messages.properties / messages_ar.properties on the app classpath)
 * resolve through the framework's MessageSource at the request locale.
 */
class GlobalExceptionHandlerI18nTest {

    private final MessageSource messageSource = productionBundle();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(provider(messageSource));

    private static ObjectProvider<MessageSource> provider(MessageSource source) {
        return new ObjectProvider<>() {
            @Override
            public MessageSource getObject() {
                return source;
            }

            @Override
            public MessageSource getIfAvailable() {
                return source;
            }

            @Override
            public MessageSource getIfUnique() {
                return source;
            }
        };
    }

    private static MessageSource productionBundle() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @BeforeEach
    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void englishLocale_isByteIdenticalToThePreB4Literals() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        var response = handler.handleNoResource(null, request("/api/listings/999"));

        assertThat(response.getTitle()).isEqualTo("Not Found");
        assertThat(response.getDetail()).isEqualTo("Resource not found");
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void arabicLocale_localizesTitleDetailAndUserMessage() {
        LocaleContextHolder.setLocale(Locale.of("ar"));

        var response = handler.handleNoResource(null, request("/api/listings/999"));

        assertThat(response.getTitle()).isEqualTo("غير موجود");
        assertThat(response.getDetail()).isEqualTo("المورد غير موجود");
        assertThat(response.getProperties().get("userMessage"))
                .isEqualTo("المورد المطلوب غير موجود.");
        assertThat(response.getStatus()).isEqualTo(404);
        // machine contract stays authoritative
        assertThat(response.getProperties().get("errorCode")).isEqualTo("NF-001");
        assertThat(response.getType().toString())
                .isEqualTo("https://marketplace.com/errors/not-found");
    }

    @Test
    void arabicLocale_localizesDomainExceptionTaxonomy() {
        LocaleContextHolder.setLocale(Locale.of("ar"));

        var response = handler.handleApiProblemDetail(
                new ResourceNotFoundException("Listing", "123"), request("/api/listings/123"));

        // dynamic developer-facing detail stays canonical English
        assertThat(response.getDetail()).isEqualTo("Listing not found: 123");
        // taxonomy title + userMessage carry the localized human text
        assertThat(response.getTitle()).isEqualTo("غير موجود");
        assertThat(response.getProperties().get("userMessage"))
                .isEqualTo("المورد المطلوب غير موجود.");
    }

    @Test
    void unboundMessageSource_fallsBackToEnglishLiterals_exactly() {
        var bare = new GlobalExceptionHandler();

        var response = bare.handleNoResource(null, request("/api/listings/999"));

        assertThat(response.getTitle()).isEqualTo("Not Found");
        assertThat(response.getDetail()).isEqualTo("Resource not found");
        assertThat(response.getProperties()).doesNotContainKey("userMessage");
    }

    @Test
    void accessDenied_carriesArabicTitleDetailAndUserMessage() {
        LocaleContextHolder.setLocale(Locale.of("ar"));

        var response = handler.handleAccessDenied(
                new org.springframework.security.access.AccessDeniedException("x"),
                request("/api/bookings/1"));

        assertThat(response.getTitle()).isEqualTo("ممنوع الوصول");
        assertThat(response.getDetail()).isEqualTo("الوصول مرفوض");
        assertThat(response.getProperties().get("userMessage"))
                .isEqualTo("لا يُسمح لك بتنفيذ هذا الإجراء.");
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void everyTaxonomyCode_hasATitleAndUserEntry_inBothBundles() {
        for (ApiErrorTaxonomy taxonomy : ApiErrorTaxonomy.values()) {
            assertThat(bundle(Locale.ENGLISH, taxonomy.errorCode() + ".title"))
                    .as("en title for %s", taxonomy.errorCode()).isNotBlank();
            assertThat(bundle(Locale.ENGLISH, taxonomy.errorCode() + ".user"))
                    .as("en user for %s", taxonomy.errorCode()).isNotBlank();
            assertThat(bundle(Locale.of("ar"), taxonomy.errorCode() + ".title"))
                    .as("ar title for %s", taxonomy.errorCode()).isNotBlank();
            assertThat(bundle(Locale.of("ar"), taxonomy.errorCode() + ".user"))
                    .as("ar user for %s", taxonomy.errorCode()).isNotBlank();
        }
    }

    private String bundle(Locale locale, String suffix) {
        return messageSource.getMessage("error." + suffix, null, null, locale);
    }

    private static HttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
