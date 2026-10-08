package com.marketplace.notifications;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * B-11 (compliance plan B.6 — i18n عربية للإشعارات والرسائل): the
 * notifications module's own text channel, backed by the framework's
 * {@link MessageSource} abstraction exactly as the official Spring Boot
 * reference (reference/features/internationalization.html) documents it —
 * a {@link ResourceBundleMessageSource} over the module-owned
 * {@code notifications-text} basename with {@code notifications-text_ar}
 * carrying the Arabic rendering and the default bundle carrying the
 * byte-identical English floor.
 *
 * <p><b>Why a module-local instance and not the auto-configured app
 * {@code MessageSource} (measured):</b> the app-level source resolves the
 * {@code messages} basename declared in {@code application.yml} (Track A's
 * hot garden — the pack §4/§5), and the app bundle lives in
 * {@code marketplace-app} resources, which are NOT on this module's
 * classpath — a module-scoped {@code mvn verify} gate could never resolve
 * the keys. The reference documents the underlying
 * {@code ResourceBundleMessageSource} as the mechanism the auto-configuration
 * itself registers; this component holds one PRIVATELY (it is deliberately
 * NOT a {@code MessageSource}-typed bean) so
 * {@code MessageSourceAutoConfiguration} and the Bean Validation message
 * interpolation wiring stay untouched for the whole application — zero
 * cross-garden effect, zero CR.
 *
 * <p><b>Determinism (the house's own discipline, measured in
 * {@code application.yml}'s i18n floor):</b>
 * {@code fallbackToSystemLocale=false} so a JVM whose
 * {@code Locale.getDefault()} is not English cannot drag the system locale
 * into ResourceBundle candidate resolution — every lookup resolves from the
 * explicit {@link Locale} argument alone, never
 * {@code LocaleContextHolder} (event-listener threads carry no request
 * locale; the JVM default is a box-dependent hazard).
 *
 * <p><b>Composition locale:</b> {@link #PLATFORM_LOCALE} — Arabic, the
 * platform's user-facing language (the compliance plan's platform identity
 * §0.1: a community system at country/city/neighborhood level for the
 * Arabic market; B.6 is the journey's Arabic leg). The default bundle keeps
 * the English literals byte-identical to the pre-B-11 strings, so any
 * resolution at another locale returns exactly the previous behavior.
 */
@Component
public class NotificationTextSource {

    /** The platform's user-facing standard (compliance plan §0.1): Arabic. */
    public static final Locale PLATFORM_LOCALE = Locale.of("ar");

    private final MessageSource source;

    public NotificationTextSource() {
        ResourceBundleMessageSource bundleSource = new ResourceBundleMessageSource();
        bundleSource.setBasename("notifications-text");
        bundleSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        bundleSource.setFallbackToSystemLocale(false);
        this.source = bundleSource;
    }

    /**
     * Composes one notification text at the given locale. Arguments follow
     * the {@link java.text.MessageFormat} contract ({@code {0}}, {@code {1}} …)
     * — the official reference's own parameterization mechanism.
     */
    public String compose(String key, Locale locale, Object... args) {
        return source.getMessage(key, args, locale);
    }

    /**
     * The community domain's stored target-type name ("POST"/"COMMENT" —
     * the ContentModeratedEvent vocabulary, the PaymentStateChangedEvent
     * String precedent) rendered as the vocabulary word at the locale:
     * {@code targettype.POST=post} (the English floor is byte-identical to
     * the pre-B-11 {@code targetType.toLowerCase()}). An unknown name rides
     * through as the raw value — the honest degradation, exactly what the
     * pre-B-11 concatenation did.
     */
    public String targetTypeWord(String targetType, Locale locale) {
        return vocabularyWord("targettype." + targetType, targetType, locale);
    }

    /**
     * The payments domain's state name ("COMPLETED", "FAILED", … — the
     * PaymentIntentStatus/PaymentStatus vocabulary) rendered as the state
     * word at the locale. An unknown state rides through as the raw value —
     * a future payments status degrades honestly instead of failing the
     * notification.
     */
    public String paymentStateWord(String state, Locale locale) {
        return vocabularyWord("payment.state." + state, state, locale);
    }

    private String vocabularyWord(String key, String raw, Locale locale) {
        return source.getMessage(key, null, raw, locale);
    }
}
