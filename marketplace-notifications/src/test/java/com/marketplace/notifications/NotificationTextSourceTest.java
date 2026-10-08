package com.marketplace.notifications;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-11 (compliance plan B.6 — i18n عربية للإشعارات والرسائل): the locale
 * gate. The production {@link NotificationTextSource} resolves the real
 * bundles (notifications-text.properties / notifications-text_ar.properties
 * on this module's own classpath) at BOTH locales — the official reference
 * is the Spring Boot internationalization feature page
 * (reference/features/internationalization.html): the {@code MessageSource}
 * abstraction with locale-suffixed bundles.
 *
 * <p>The two proofs, mirroring the house's own i18n floor discipline
 * ({@code GlobalExceptionHandlerI18nTest}):</p>
 * <ol>
 *   <li><b>English floor is byte-identical to the pre-B-11 hardcoded
 *   literals</b> — resolution at {@link Locale#ENGLISH} (and at any locale
 *   with no Arabic language tag) returns exactly the previous strings, so
 *   existing consumers see the previous behavior.</li>
 *   <li><b>Arabic localizes every composed surface</b> — the platform's
 *   standard locale ({@code PLATFORM_LOCALE}) renders the Arabic text for
 *   every notification/email key, with {@link java.text.MessageFormat}
 *   argument rendering (the reference's own parameterization).</li>
 * </ol>
 */
class NotificationTextSourceTest {

    private final NotificationTextSource text = new NotificationTextSource();

    /** The English floor — every key byte-identical to the pre-B-11 literals. */
    @Test
    void englishLocale_isByteIdenticalToThePreB11Literals() {
        Locale english = Locale.ENGLISH;
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThat(text.compose("notification.BOOKING_CREATED.consumer", english, id))
                .isEqualTo("Booking created: " + id);
        assertThat(text.compose("notification.BOOKING_CREATED.provider", english, id))
                .isEqualTo("New booking request: " + id);
        assertThat(text.compose("notification.PAYMENT_STATE.booking", english, "COMPLETED", id))
                .isEqualTo("Payment COMPLETED for booking " + id);
        assertThat(text.compose("notification.PAYMENT_STATE.ad", english, "FAILED", id))
                .isEqualTo("Payment FAILED for ad campaign " + id);
        assertThat(text.compose("notification.LEAD_RECEIVED", english, id))
                .isEqualTo("New lead for your listing: " + id);
        assertThat(text.compose("notification.SAVED_SEARCH_MATCH.singular", english, id))
                .isEqualTo("New listing matching your saved search: " + id);
        assertThat(text.compose("notification.SAVED_SEARCH_MATCH.plural", english, 3, id))
                .isEqualTo("New listing matching 3 of your saved searches: " + id);
        assertThat(text.compose("notification.POST_COMMENTED", english, id))
                .isEqualTo("New comment on your post: " + id);
        assertThat(text.compose("notification.NEW_LISTING_IN_NEIGHBORHOOD", english, id))
                .isEqualTo("New listing in your neighborhood: " + id);
        assertThat(text.compose("notification.CONTENT_MODERATED", english, "post", id))
                .isEqualTo("Your post was moderated: " + id);
        assertThat(text.compose("notification.POST_REACTED", english, id))
                .isEqualTo("New thank on your post: " + id);
        assertThat(text.compose("notification.FOLLOWED_PROVIDER_NEW_LISTING", english, id))
                .isEqualTo("New listing from a provider you follow: " + id);
        assertThat(text.compose("notification.MESSAGE_RECEIVED", english, id))
                .isEqualTo("New message in your conversation: " + id);

        assertThat(text.compose("email.BOOKING_CREATED.consumer.subject", english))
                .isEqualTo("Booking Created");
        assertThat(text.compose("email.BOOKING_CREATED.provider.subject", english))
                .isEqualTo("New Booking Request");
        assertThat(text.compose("email.BOOKING_CREATED.consumer.body", english, id))
                .isEqualTo("Your booking " + id + " has been created.");
        assertThat(text.compose("email.BOOKING_CREATED.provider.body", english, id))
                .isEqualTo("New booking request " + id + " for your service.");
        assertThat(text.compose("email.PAYMENT_STATE.subject", english, "COMPLETED"))
                .isEqualTo("Payment COMPLETED");
        assertThat(text.compose("email.LEAD_RECEIVED.subject", english))
                .isEqualTo("New Lead");
        assertThat(text.compose("email.SAVED_SEARCH_MATCH.subject", english))
                .isEqualTo("Saved Search Match");
        assertThat(text.compose("email.POST_COMMENTED.subject", english))
                .isEqualTo("New Comment");
        assertThat(text.compose("email.NEW_LISTING_IN_NEIGHBORHOOD.subject", english))
                .isEqualTo("New Listing in Your Neighborhood");
        assertThat(text.compose("email.CONTENT_MODERATED.subject", english))
                .isEqualTo("Your Content Was Moderated");
        assertThat(text.compose("email.POST_REACTED.subject", english))
                .isEqualTo("New Thank");
        assertThat(text.compose("email.FOLLOWED_PROVIDER_NEW_LISTING.subject", english))
                .isEqualTo("New Listing From a Provider You Follow");
        assertThat(text.compose("email.MESSAGE_RECEIVED.subject", english))
                .isEqualTo("New Message");
    }

    /**
     * The Arabic leg (the platform's user-facing standard): every key
     * renders its Arabic entry with the SAME argument positions — the
     * journey's leg 5 (compliance plan §6: عربية/i18n) proven key by key.
     */
    @Test
    void platformLocale_rendersArabicForEveryComposedSurface() {
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(text.compose("notification.BOOKING_CREATED.consumer", platform, id))
                .isEqualTo("تم إنشاء الحجز: " + id);
        assertThat(text.compose("notification.BOOKING_CREATED.provider", platform, id))
                .isEqualTo("طلب حجز جديد: " + id);
        assertThat(text.compose("notification.PAYMENT_STATE.booking", platform, "مكتمل", id))
                .isEqualTo("الدفع مكتمل للحجز " + id);
        assertThat(text.compose("notification.PAYMENT_STATE.ad", platform, "فشل", id))
                .isEqualTo("الدفع فشل لحملة الإعلان " + id);
        assertThat(text.compose("notification.LEAD_RECEIVED", platform, id))
                .isEqualTo("عميل جديد لقائمتك: " + id);
        assertThat(text.compose("notification.SAVED_SEARCH_MATCH.singular", platform, id))
                .isEqualTo("قائمة جديدة تطابق بحثك المحفوظ: " + id);
        assertThat(text.compose("notification.SAVED_SEARCH_MATCH.plural", platform, 3, id))
                .isEqualTo("قائمة جديدة تطابق 3 من بحوثك المحفوظة: " + id);
        assertThat(text.compose("notification.POST_COMMENTED", platform, id))
                .isEqualTo("تعليق جديد على منشورك: " + id);
        assertThat(text.compose("notification.NEW_LISTING_IN_NEIGHBORHOOD", platform, id))
                .isEqualTo("قائمة جديدة في حيّك: " + id);
        assertThat(text.compose("notification.CONTENT_MODERATED", platform, "منشور", id))
                .isEqualTo("تمت مراجعة منشور الخاص بك: " + id);
        assertThat(text.compose("notification.POST_REACTED", platform, id))
                .isEqualTo("شكر جديد على منشورك: " + id);
        assertThat(text.compose("notification.FOLLOWED_PROVIDER_NEW_LISTING", platform, id))
                .isEqualTo("قائمة جديدة من مزوّد تتابعه: " + id);
        assertThat(text.compose("notification.MESSAGE_RECEIVED", platform, id))
                .isEqualTo("رسالة جديدة في محادثتك: " + id);

        assertThat(text.compose("email.BOOKING_CREATED.consumer.subject", platform))
                .isEqualTo("تم إنشاء الحجز");
        assertThat(text.compose("email.BOOKING_CREATED.provider.subject", platform))
                .isEqualTo("طلب حجز جديد");
        assertThat(text.compose("email.BOOKING_CREATED.consumer.body", platform, id))
                .isEqualTo("تم إنشاء حجزك " + id + ".");
        assertThat(text.compose("email.BOOKING_CREATED.provider.body", platform, id))
                .isEqualTo("طلب حجز جديد " + id + " لخدمتك.");
        assertThat(text.compose("email.PAYMENT_STATE.subject", platform, "مكتمل"))
                .isEqualTo("الدفع مكتمل");
        assertThat(text.compose("email.LEAD_RECEIVED.subject", platform))
                .isEqualTo("عميل جديد");
        assertThat(text.compose("email.SAVED_SEARCH_MATCH.subject", platform))
                .isEqualTo("تطابق بحث محفوظ");
        assertThat(text.compose("email.POST_COMMENTED.subject", platform))
                .isEqualTo("تعليق جديد");
        assertThat(text.compose("email.NEW_LISTING_IN_NEIGHBORHOOD.subject", platform))
                .isEqualTo("قائمة جديدة في حيّك");
        assertThat(text.compose("email.CONTENT_MODERATED.subject", platform))
                .isEqualTo("تمت مراجعة محتواك");
        assertThat(text.compose("email.POST_REACTED.subject", platform))
                .isEqualTo("شكر جديد");
        assertThat(text.compose("email.FOLLOWED_PROVIDER_NEW_LISTING.subject", platform))
                .isEqualTo("قائمة جديدة من مزوّد تتابعه");
        assertThat(text.compose("email.MESSAGE_RECEIVED.subject", platform))
                .isEqualTo("رسالة جديدة");
    }

    /** The platform standard IS the Arabic locale (the composition locale). */
    @Test
    void platformLocale_isArabic() {
        assertThat(NotificationTextSource.PLATFORM_LOCALE.getLanguage()).isEqualTo("ar");
    }

    /**
     * The community and payments vocabularies render their words at both
     * locales — the measured state set (INITIATED/COMPLETED/FAILED literals
     * plus the PaymentIntentStatus names) and the two ContentModeratedEvent
     * target types.
     */
    @Test
    void domainVocabularies_renderBothLocales() {
        Locale english = Locale.ENGLISH;
        Locale platform = NotificationTextSource.PLATFORM_LOCALE;

        // The English floor: byte-identical to the pre-B-11
        // targetType.toLowerCase() and the raw state concatenation.
        assertThat(text.targetTypeWord("POST", english)).isEqualTo("post");
        assertThat(text.targetTypeWord("COMMENT", english)).isEqualTo("comment");
        List.of("INITIATED", "PENDING", "PROCESSING", "COMPLETED",
                        "SUCCEEDED", "FAILED", "REFUNDED", "PARTIALLY_REFUNDED")
                .forEach(state -> assertThat(text.paymentStateWord(state, english)).isEqualTo(state));

        // The Arabic rendering.
        assertThat(text.targetTypeWord("POST", platform)).isEqualTo("منشور");
        assertThat(text.targetTypeWord("COMMENT", platform)).isEqualTo("تعليق");
        assertThat(text.paymentStateWord("COMPLETED", platform)).isEqualTo("مكتمل");
        assertThat(text.paymentStateWord("FAILED", platform)).isEqualTo("فشل");
        assertThat(text.paymentStateWord("PARTIALLY_REFUNDED", platform)).isEqualTo("مُسترد جزئياً");
    }

    /**
     * The honest degradation (the pre-B-11 concatenation's own behavior):
     * an unknown target type or a future payments state rides through as
     * the raw value instead of failing the notification — measured against
     * both locales.
     */
    @Test
    void unknownVocabularyValues_rideThroughRawAtBothLocales() {
        assertThat(text.targetTypeWord("POLL", Locale.ENGLISH)).isEqualTo("POLL");
        assertThat(text.targetTypeWord("POLL", NotificationTextSource.PLATFORM_LOCALE)).isEqualTo("POLL");
        assertThat(text.paymentStateWord("CAPTURED", Locale.ENGLISH)).isEqualTo("CAPTURED");
        assertThat(text.paymentStateWord("CAPTURED", NotificationTextSource.PLATFORM_LOCALE)).isEqualTo("CAPTURED");
    }

    /**
     * The house determinism discipline (application.yml's own i18n floor):
     * a locale with no Arabic language tag resolves the DEFAULT bundle —
     * never the JVM default's candidate chain (fallbackToSystemLocale is
     * off), so an {@code ar_XX} variant resolves the Arabic bundle and any
     * other language the English floor.
     */
    @Test
    void localeResolution_isDeterministic() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000003");

        // A regional Arabic variant still resolves the Arabic bundle.
        assertThat(text.compose("notification.MESSAGE_RECEIVED", Locale.of("ar", "SA"), id))
                .startsWith("رسالة جديدة في محادثتك: ");

        // A non-Arabic language resolves the default (English) bundle.
        assertThat(text.compose("notification.MESSAGE_RECEIVED", Locale.of("fr"), id))
                .isEqualTo("New message in your conversation: " + id);
        // The ROOT locale resolves the default bundle.
        assertThat(text.compose("notification.MESSAGE_RECEIVED", Locale.ROOT, id))
                .isEqualTo("New message in your conversation: " + id);
    }
}
