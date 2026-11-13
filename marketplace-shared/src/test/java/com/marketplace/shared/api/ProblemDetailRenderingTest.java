package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.text.MessageFormat;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.AbstractMessageSource;

/**
 * The args-aware {@code ProblemDetailRendering.resolve} fallback contract
 * (the 2026-11-13 full-review finding on the D4 type-mismatch leg).
 *
 * <p><b>The measured hazard:</b> the English floor embeds the RAW rejected
 * value ({@code handleTypeMismatch}'s own string concat). Handing that
 * floor to {@code MessageSource.getMessage} as a NON-null default makes
 * {@code AbstractMessageSource} render it through {@code MessageFormat}
 * when arguments are present (Framework 7.0.10 — the build's pinned
 * version — {@code AbstractMessageSource.getMessage:145}). A rejected
 * value such as {@code '{0,number}'} collapses the apostrophe quoting and
 * turns the rejected parameter's NAME into an active {@code number}-styled
 * element whose String argument throws
 * {@code IllegalArgumentException} — the handler 500s instead of answering
 * VAL-001. The {@code null}-default request (the fix) removes the floor
 * from the formatter's reach by contract: a missing key answers
 * {@code getDefaultMessage(code)} — {@code null} with
 * {@code useCodeAsDefaultMessage} off (the codebase's only configuration,
 * grep-verified; the Boot default) — and the floor merges by hand,
 * byte-identical, never formatted.</p>
 */
class ProblemDetailRenderingTest {

    /** A source with NO bundle entries at all — the missing-key leg. */
    private static MessageSource emptySource() {
        return new AbstractMessageSource() {
            @Override
            protected MessageFormat resolveCode(String code, Locale locale) {
                return null;
            }
        };
    }

    /** A source resolving exactly one entry — the localized leg. */
    private static MessageSource oneEntrySource() {
        return new AbstractMessageSource() {
            @Override
            protected MessageFormat resolveCode(String code, Locale locale) {
                if ("error.VAL-001.type-mismatch-detail".equals(code)) {
                    // the ar bundle's own shape: ''{0}'' renders a literal
                    // apostrophe, {0}/{1}/{2} stay the active arguments
                    return new MessageFormat(
                            "قيمة غير صالحة ''{0}'' للمعامل ''{1}'' — النوع المتوقع ''{2}''", locale);
                }
                return null;
            }
        };
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /**
     * The core regression: a rejected value that collapses MessageFormat
     * quoting must NOT throw and must NOT mangle the English floor — the
     * floor answers byte-identical (never enters the formatter's reach).
     * Under the pre-fix {@code getMessage(code, args, fallback, locale)}
     * this exact call threw {@code IllegalArgumentException} ("Cannot
     * format given Object as a Number").
     */
    @Test
    void missingKey_returnsTheFloorByteIdentical_evenWhenTheValueCollapsesTheQuoting() {
        String value = "'{0,number}";
        String floor = "Invalid value '" + value + "' for parameter 'purpose' — expected TimeUnit";

        assertThatCode(() -> ProblemDetailRendering.resolve(
                emptySource(), "error.VAL-001.type-mismatch-detail", floor,
                "purpose", value, "TimeUnit"))
                .doesNotThrowAnyException();
        assertThat(ProblemDetailRendering.resolve(
                emptySource(), "error.VAL-001.type-mismatch-detail", floor,
                "purpose", value, "TimeUnit")).isEqualTo(floor);
    }

    /** The same contract with a plain floor: byte-identical, no formatting. */
    @Test
    void missingKey_withAPlainFloor_returnsItByteIdentical() {
        String floor = "Invalid value 'BUY' for parameter 'purpose' — expected TimeUnit";

        assertThat(ProblemDetailRendering.resolve(
                emptySource(), "error.VAL-001.type-mismatch-detail", floor,
                "purpose", "BUY", "TimeUnit")).isEqualTo(floor);
    }

    /** The localized leg keeps the official formatting path: the bundle's
     * entry formats with the rejected facts inserted. */
    @Test
    void presentKey_formatsTheLocalizedEntryWithTheArguments() {
        LocaleContextHolder.setLocale(Locale.of("ar"));

        assertThat(ProblemDetailRendering.resolve(
                oneEntrySource(), "error.VAL-001.type-mismatch-detail", "the floor is ignored",
                "purpose", "BUY", "TimeUnit"))
                .isEqualTo("قيمة غير صالحة 'BUY' للمعامل 'purpose' — النوع المتوقع 'TimeUnit'");
    }

    /** The boundary contract the argless resolve shares: no source or no
     * key answers the floor directly. */
    @Test
    void nullSourceOrNullKey_answersTheFloor() {
        String floor = "Invalid value 'BUY' for parameter 'purpose' — expected TimeUnit";

        assertThat(ProblemDetailRendering.resolve(null, "any.key", floor, "a")).isEqualTo(floor);
        assertThat(ProblemDetailRendering.resolve(oneEntrySource(), null, floor, "a")).isEqualTo(floor);
    }
}
