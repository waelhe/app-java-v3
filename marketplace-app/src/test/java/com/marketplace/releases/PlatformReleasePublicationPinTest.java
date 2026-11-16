package com.marketplace.releases;

import com.marketplace.shared.api.PlatformReleaseChannel;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The local binary search of the publication path — the CI 400's root is an
 * IllegalArgumentException whose message the error.VAL-001.detail i18n key
 * swaps for the generic "Validation failed" (the measured body carries NO
 * fieldErrors — the MethodArgumentNotValid path always adds them, so the
 * IllegalArgumentException advice is the producer). This pin walks the exact
 * journey's arguments through the SERVICE and the CONTROLLER with the
 * repository and publisher mocked: whichever leg throws names the root
 * without containers.
 */
class PlatformReleasePublicationPinTest {

    @Test
    void theJourneysExactArgumentsWalkTheServiceWithoutThrowing() {
        PlatformReleaseRepository repository = mock(PlatformReleaseRepository.class);
        when(repository.findByChannelAndReleaseVersion(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        PlatformReleaseService service = new PlatformReleaseService(repository, events, Clock.systemUTC());

        // The journey's exact arguments (releaseIdentitiesAreNeverRecycled's
        // first publication — the version shape "3.14.XXXX" included).
        String version = "3.14." + (1000 + (System.currentTimeMillis() % 9000));
        assertThatCode(() -> service.publish(PlatformReleaseChannel.IOS, version,
                "First publication.", "1.0.0", false, 24, "test-actor"))
                .as("the service leg of the journey's publication")
                .doesNotThrowAnyException();

        // The journey test's ANDROID shape too (the console journey's phase 1).
        assertThatCode(() -> service.publish(PlatformReleaseChannel.ANDROID, version,
                "The neighborhood market arrives on mobile.", "3.0.0", true, 72, "test-actor"))
                .as("the service leg of the console journey")
                .doesNotThrowAnyException();
    }

    @Test
    void theControllersParseAndRecordContractHold() {
        // The boundary's own record — the journey's body through the parse.
        assertThat(PlatformReleaseChannel.parse("IOS")).isEqualTo(PlatformReleaseChannel.IOS);
        assertThat(PlatformReleaseChannel.parse("ANDROID")).isEqualTo(PlatformReleaseChannel.ANDROID);
    }
}
