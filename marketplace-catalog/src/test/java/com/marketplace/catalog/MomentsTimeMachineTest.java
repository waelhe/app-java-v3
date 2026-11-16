package com.marketplace.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.moments.support.TimeMachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-12 (C.6): the Moments migration's deterministic gate — the
 * {@code TimeMachine} selected by {@code spring.modulith.moments.
 * enable-time-machine=true} (the official Moments test mode) shifts time
 * and, by the framework's own contract, fires ALL intermediate
 * passage-of-time events: every crossed hour boundary an
 * {@code HourHasPassed}, every crossed midnight a {@code DayHasPassed}.
 *
 * <p>The wiring under proof is the real one: the auto-configured
 * {@link TimeMachine} bean, the fixed {@link Clock} (the same injectable
 * production clock discipline — the {@code ClockConfig} bean shape), and
 * the three migrated listeners with their executors and the campaign
 * repository mocked at the port line. Every assertion is a function of the
 * shift alone — zero wall-clock reads participate: the count of hourly
 * ticks, the exactly-once daily ticks, and the billing horizon derived
 * purely from the event's own payload (the day after the day that passed).
 *
 * <p>The .imports resource next to this class
 * ({@code META-INF/spring/com.marketplace.catalog.MomentsTimeMachineTest.imports})
 * is the documented {@code @ImportAutoConfiguration} file mechanism that
 * pulls {@code MomentsAutoConfiguration} into this sliced context — the
 * same file-per-test-class channel Boot's own sliced tests use.
 */
@SpringBootTest(classes = MomentsTimeMachineTest.Wiring.class,
        properties = "spring.modulith.moments.enable-time-machine=true")
@ImportAutoConfiguration
class MomentsTimeMachineTest {

    /** A mid-morning Thursday — clear of every hour, day, week, month edge. */
    private static final Instant FIXED_NOW = Instant.parse("2026-01-15T10:30:00Z");

    @Configuration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }

        @Bean
        ListingExpiryBatchExecutor expiryExecutor() {
            return mock(ListingExpiryBatchExecutor.class);
        }

        @Bean
        ListingRankingBatchExecutor rankingExecutor() {
            return mock(ListingRankingBatchExecutor.class);
        }

        @Bean
        AdBillingBatchExecutor billingExecutor() {
            return mock(AdBillingBatchExecutor.class);
        }

        @Bean
        AdCampaignRepository campaignRepository() {
            return mock(AdCampaignRepository.class);
        }

        @Bean
        ListingExpiryJob listingExpiryJob(ListingExpiryBatchExecutor executor) {
            return new ListingExpiryJob(executor);
        }

        @Bean
        ListingRankingJob listingRankingJob(ListingRankingBatchExecutor executor) {
            return new ListingRankingJob(executor);
        }

        @Bean
        AdBillingJob adBillingJob(AdCampaignRepository repository, AdBillingBatchExecutor executor) {
            return new AdBillingJob(repository, executor);
        }
    }

    @Autowired
    TimeMachine timeMachine;

    @Autowired
    ListingExpiryBatchExecutor expiryExecutor;

    @Autowired
    ListingRankingBatchExecutor rankingExecutor;

    @Autowired
    AdBillingBatchExecutor billingExecutor;

    @Autowired
    AdCampaignRepository campaignRepository;

    @BeforeEach
    void resetTheMachineAndTheMocks() {
        // The context (and its beans) is cached across the test methods —
        // every method starts from the SAME fixed instant and clean mocks,
        // so each assertion is a function of its own shift alone.
        timeMachine.reset();
        Mockito.reset(expiryExecutor, rankingExecutor, billingExecutor, campaignRepository);
    }

    @Test
    void aShiftedDay_firesEveryHourlyTick_theTwoDailyListenersOnceAndTheEventDerivedHorizon() {
        when(expiryExecutor.pauseOneBatch()).thenReturn(0);
        when(rankingExecutor.rankOneBatch(any(), anyInt())).thenReturn(null);
        when(campaignRepository.findBillableBefore(any())).thenReturn(List.of());

        timeMachine.shiftBy(Duration.ofDays(1));

        // 24 crossed hour boundaries -> 24 expiry ticks, each draining on a short batch
        verify(expiryExecutor, times(24)).pauseOneBatch();
        // the single crossed midnight -> the ranking listener once, one full drain
        verify(rankingExecutor, times(1)).rankOneBatch(null, ListingRankingJob.BATCH_SIZE);
        // the billing horizon is the event's own payload: January 15 passed -> January 16 exclusive
        verify(campaignRepository, times(1)).findBillableBefore(LocalDate.of(2026, 1, 16));
        verify(billingExecutor, never()).settleOneCampaign(any(), any());
    }

    @Test
    void aShiftedHour_firesOnlyTheHourlyListener() {
        when(expiryExecutor.pauseOneBatch()).thenReturn(0);

        timeMachine.shiftBy(Duration.ofHours(1));

        verify(expiryExecutor, times(1)).pauseOneBatch();
        verify(rankingExecutor, never()).rankOneBatch(any(), anyInt());
        verify(campaignRepository, never()).findBillableBefore(any());
        verify(billingExecutor, never()).settleOneCampaign(any(), any());
    }

    @Test
    void theTimeMachinePropertySelectedTheTimeMachineBean() {
        // spring.modulith.moments.enable-time-machine=true is the official
        // switch: the Moments bean IS the TimeMachine in this context.
        assertThat(timeMachine).isNotNull();
        assertThat(timeMachine.now()).isEqualTo(FIXED_NOW.atZone(ZoneOffset.UTC).toLocalDateTime());
    }
}
