package com.marketplace.app.i18n;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.api.GlobalExceptionHandler;
import com.marketplace.shared.api.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * i18n layer proof (roadmap B4): the production MessageSource,
 * auto-configured by Spring Boot from the same {@code spring.messages.*}
 * properties application.yml declares, resolves the real bundles
 * (messages.properties / messages_ar.properties on the app classpath)
 * at the request locale.
 *
 * <p>The runner loads {@link MessageSourceAutoConfiguration} — the exact
 * auto-configuration Boot uses in production — instead of hand-building a
 * {@code ResourceBundleMessageSource}. No production wiring is duplicated
 * here: a future {@code spring.messages.*} change flows into this proof
 * automatically (official Boot testing utility, same as the
 * {@code SearchPropertiesValidationTest} house pattern).</p>
 */
class GlobalExceptionHandlerI18nTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessageSourceAutoConfiguration.class))
            .withPropertyValues(
                    "spring.messages.basename=messages",
                    "spring.messages.encoding=UTF-8",
                    "spring.messages.fallback-to-system-locale=false");

    @SuppressWarnings("unchecked")
    private static GlobalExceptionHandler handlerFor(MessageSource source) {
        ObjectProvider<MessageSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(source);
        return new GlobalExceptionHandler(provider);
    }

    @BeforeEach
    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void englishLocale_isByteIdenticalToThePreB4Literals() {
        runner.run(context -> {
            LocaleContextHolder.setLocale(Locale.ENGLISH);

            var response = handlerFor(context.getBean(MessageSource.class))
                    .handleNoResource(null, request("/api/listings/999"));

            assertThat(response.getTitle()).isEqualTo("Not Found");
            assertThat(response.getDetail()).isEqualTo("Resource not found");
            assertThat(response.getStatus()).isEqualTo(404);
        });
    }

    @Test
    void arabicLocale_localizesTitleDetailAndUserMessage() {
        runner.run(context -> {
            LocaleContextHolder.setLocale(Locale.of("ar"));
            MessageSource source = context.getBean(MessageSource.class);

            var response = handlerFor(source).handleNoResource(null, request("/api/listings/999"));

            assertThat(response.getTitle()).isEqualTo("غير موجود");
            assertThat(response.getDetail()).isEqualTo("المورد غير موجود");
            assertThat(response.getProperties().get("userMessage"))
                    .isEqualTo("المورد المطلوب غير موجود.");
            assertThat(response.getStatus()).isEqualTo(404);
            // machine contract stays authoritative
            assertThat(response.getProperties().get("errorCode")).isEqualTo("NF-001");
            assertThat(response.getType().toString())
                    .isEqualTo("https://marketplace.com/errors/not-found");
        });
    }

    @Test
    void arabicLocale_localizesDomainExceptionTaxonomy() {
        runner.run(context -> {
            LocaleContextHolder.setLocale(Locale.of("ar"));

            var response = handlerFor(context.getBean(MessageSource.class)).handleApiProblemDetail(
                    new ResourceNotFoundException("Listing", "123"), request("/api/listings/123"));

            // dynamic developer-facing detail stays canonical English
            assertThat(response.getDetail()).isEqualTo("Listing not found: 123");
            // taxonomy title + userMessage carry the localized human text
            assertThat(response.getTitle()).isEqualTo("غير موجود");
            assertThat(response.getProperties().get("userMessage"))
                    .isEqualTo("المورد المطلوب غير موجود.");
        });
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
        runner.run(context -> {
            LocaleContextHolder.setLocale(Locale.of("ar"));

            var response = handlerFor(context.getBean(MessageSource.class)).handleAccessDenied(
                    new org.springframework.security.access.AccessDeniedException("x"),
                    request("/api/bookings/1"));

            assertThat(response.getTitle()).isEqualTo("ممنوع الوصول");
            assertThat(response.getDetail()).isEqualTo("الوصول مرفوض");
            assertThat(response.getProperties().get("userMessage"))
                    .isEqualTo("لا يُسمح لك بتنفيذ هذا الإجراء.");
            assertThat(response.getStatus()).isEqualTo(403);
        });
    }

    // ---- D4 i18n leg (CodeRabbit full-review round 2026-11-11): the
    // ---- type-mismatch detail resolves from the dedicated
    // ---- error.VAL-001.type-mismatch-detail bundle key WITH the rejected
    // ---- parameter/value/type inserted — the real production bundles, not
    // ---- the generic "Validation failed" detail. ----

    private static org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
    typeMismatch(String value, Class<?> requiredType, String parameterName) throws NoSuchMethodException {
        org.springframework.core.MethodParameter parameter = new org.springframework.core.MethodParameter(
                Object.class.getDeclaredMethod("toString"), -1);
        return new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                value, requiredType, parameterName, parameter, null);
    }

    @Test
    void arabicLocale_localizesTypeMismatchDetailAndFieldErrorWithTheRejectedFacts() throws NoSuchMethodException {
        runner.run(context -> {
            LocaleContextHolder.setLocale(Locale.of("ar"));

            var response = handlerFor(context.getBean(MessageSource.class)).handleTypeMismatch(
                    typeMismatch("BUY", java.util.concurrent.TimeUnit.class, "purpose"),
                    request("/api/v1/search"));

            // the dedicated sub-key renders at the request locale WITH the
            // rejected parameter/value/type inserted as arguments
            assertThat(response.getDetail())
                    .isEqualTo("قيمة غير صالحة 'BUY' للمعامل 'purpose' — النوع المتوقع 'TimeUnit'");
            assertThat(response.getTitle()).isEqualTo("طلب غير صالح");
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getProperties().get("errorCode")).isEqualTo("VAL-001");
            @SuppressWarnings("unchecked")
            java.util.List<com.marketplace.shared.api.ApiErrorPayload.FieldError> fieldErrors =
                    (java.util.List<com.marketplace.shared.api.ApiErrorPayload.FieldError>)
                            response.getProperties().get("fieldErrors");
            assertThat(fieldErrors).hasSize(1);
            assertThat(fieldErrors.get(0).field()).isEqualTo("purpose");
            // the violations-list message carries the SAME localized text —
            // one resolve, one text, both surfaces (the review finding)
            assertThat(fieldErrors.get(0).message()).isEqualTo(response.getDetail());
        });
    }

    @Test
    void englishLocale_typeMismatchDetailCarriesRejectedValueFacts() throws NoSuchMethodException {
        runner.run(context -> {
            LocaleContextHolder.setLocale(Locale.ENGLISH);

            var response = handlerFor(context.getBean(MessageSource.class)).handleTypeMismatch(
                    typeMismatch("BUY", java.util.concurrent.TimeUnit.class, "purpose"),
                    request("/api/v1/search"));

            assertThat(response.getDetail())
                    .isEqualTo("Invalid value 'BUY' for parameter 'purpose' — expected TimeUnit");
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getProperties().get("errorCode")).isEqualTo("VAL-001");
        });
    }

    @Test
    void everyTaxonomyCode_hasATitleAndUserEntry_inBothBundles() {
        runner.run(context -> {
            MessageSource source = context.getBean(MessageSource.class);
            for (ApiErrorTaxonomy taxonomy : ApiErrorTaxonomy.values()) {
                assertThat(source.getMessage("error." + taxonomy.errorCode() + ".title", null, null, Locale.ENGLISH))
                        .as("en title for %s", taxonomy.errorCode()).isNotBlank();
                assertThat(source.getMessage("error." + taxonomy.errorCode() + ".user", null, null, Locale.ENGLISH))
                        .as("en user for %s", taxonomy.errorCode()).isNotBlank();
                assertThat(source.getMessage("error." + taxonomy.errorCode() + ".title", null, null, Locale.of("ar")))
                        .as("ar title for %s", taxonomy.errorCode()).isNotBlank();
                assertThat(source.getMessage("error." + taxonomy.errorCode() + ".user", null, null, Locale.of("ar")))
                        .as("ar user for %s", taxonomy.errorCode()).isNotBlank();
            }
        });
    }

    private static HttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
