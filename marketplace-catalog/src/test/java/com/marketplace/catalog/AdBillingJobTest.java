package com.marketplace.catalog;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CodeRabbit agent findings row 31 (CWE-190) — the loop's half of the
 * fix: the shared settlement loop must ISOLATE one campaign's failure so
 * it cannot stop the rest of the run. Before the fix, the overflow (or
 * any campaign-scoped failure) propagated out of
 * {@code settleWindowsThrough} on the FIRST broken candidate — every
 * campaign after it in the list stayed unsettled that day, and because
 * the broken campaign stayed eligible with its marker unmoved, the next
 * run died at the same position again: a permanently wedged daily
 * cycle. After the fix the loop settles every remaining candidate and
 * rethrows the first failure only AFTER the batch — isolation first,
 * the loud-failure contract preserved.
 */
class AdBillingJobTest {

    private final AdCampaignRepository campaignRepository = mock(AdCampaignRepository.class);
    private final AdBillingBatchExecutor batchExecutor = mock(AdBillingBatchExecutor.class);
    private final AdBillingJob job = new AdBillingJob(campaignRepository, batchExecutor);

    private static final LocalDate HORIZON = LocalDate.parse("2026-10-04");

    @Test
    void aBrokenCampaignIsIsolated_theRunSettlesTheRest_thenFailsLoudly() {
        // The finding's literal shape: the FIRST candidate breaks (the
        // overflow that used to escape the loop) — the two later
        // campaigns must STILL settle.
        UUID broken = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(campaignRepository.findBillableBefore(HORIZON)).thenReturn(List.of(broken, second, third));
        ArithmeticException overflow = new ArithmeticException("long overflow");
        doThrow(overflow).when(batchExecutor).settleOneCampaign(broken, HORIZON);

        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> job.settleWindowsThrough(HORIZON))
                .isSameAs(overflow);

        // Isolation: EVERY candidate got its settle call — the broken one
        // first (its marker never advanced, so the next run retries it),
        // then the two healthy ones IN ORDER after it.
        InOrder inOrder = inOrder(batchExecutor);
        inOrder.verify(batchExecutor).settleOneCampaign(broken, HORIZON);
        inOrder.verify(batchExecutor).settleOneCampaign(second, HORIZON);
        inOrder.verify(batchExecutor).settleOneCampaign(third, HORIZON);
    }

    @Test
    void theFirstFailureSurfacesAfterTheLoop_evenWhenLaterCampaignsFailToo() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(campaignRepository.findBillableBefore(HORIZON)).thenReturn(List.of(first, second));
        IllegalStateException firstFailure = new IllegalStateException("first campaign bug");
        doThrow(firstFailure).when(batchExecutor).settleOneCampaign(first, HORIZON);
        doThrow(new IllegalStateException("second campaign bug")).when(batchExecutor)
                .settleOneCampaign(second, HORIZON);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> job.settleWindowsThrough(HORIZON))
                .isSameAs(firstFailure);

        // Both candidates were attempted — neither swallowed the other.
        verify(batchExecutor).settleOneCampaign(first, HORIZON);
        verify(batchExecutor).settleOneCampaign(second, HORIZON);
    }

    @Test
    void aNonUniquenessIntegrityFailureIsIsolatedToo_notSwallowedAsAnOverlap() {
        // The pre-fix shape: an integrity violation that is NOT the
        // window-uniqueness backstop aborted the whole loop. Now it is
        // isolated like any campaign failure — logged, set aside, and
        // rethrown after the remaining campaigns settled — never counted
        // as the expected overlap.
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(campaignRepository.findBillableBefore(HORIZON)).thenReturn(List.of(broken, healthy));
        DataIntegrityViolationException notTheBackstop =
                new DataIntegrityViolationException("some OTHER constraint");
        doThrow(notTheBackstop).when(batchExecutor).settleOneCampaign(broken, HORIZON);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> job.settleWindowsThrough(HORIZON))
                .isSameAs(notTheBackstop);

        // The healthy campaign behind the broken one still settled.
        verify(batchExecutor).settleOneCampaign(healthy, HORIZON);
    }

    @Test
    void theWindowUniquenessOverlapStaysTheExpectedQuietSkip() {
        // The W5 r1 contract is unchanged: the deterministic overlap (one
        // window, one charge) is logged and skipped — NO failure rethrow.
        UUID overlap = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(campaignRepository.findBillableBefore(HORIZON)).thenReturn(List.of(overlap, healthy));
        doThrow(new DataIntegrityViolationException(
                "violates unique constraint \"" + AdBillingJob.WINDOW_UNIQUENESS_CONSTRAINT + "\""))
                .when(batchExecutor).settleOneCampaign(overlap, HORIZON);

        job.settleWindowsThrough(HORIZON);

        verify(batchExecutor).settleOneCampaign(overlap, HORIZON);
        verify(batchExecutor).settleOneCampaign(healthy, HORIZON);
    }

    @Test
    void anEmptyCandidateListIsAQuietNoOp() {
        when(campaignRepository.findBillableBefore(HORIZON)).thenReturn(List.of());

        job.settleWindowsThrough(HORIZON);

        verify(batchExecutor, never()).settleOneCampaign(any(UUID.class), eq(HORIZON));
    }

    @Test
    void aHealthyRunThrowsNothing() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(campaignRepository.findBillableBefore(HORIZON)).thenReturn(List.of(first, second));

        job.settleWindowsThrough(HORIZON);

        verify(batchExecutor).settleOneCampaign(first, HORIZON);
        verify(batchExecutor).settleOneCampaign(second, HORIZON);
        assertThat(true).isTrue(); // reached — no exception escaped the loop
    }
}
