package com.marketplace.community;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-19 (compliance plan C.11) — the rules' CRUD contracts (the DoD's
 * «اختبار عقد CRUD القواعد»): the registration (born enabled, the
 * duplicate live pair's 409), the board, the threshold revision and
 * the pause/resume (unknown rule 404, the row's own mutation), and the
 * retirement (the soft delete — the row's audit trail survives, the
 * slot releases). The factory's own threshold floor is the
 * defense-in-depth behind the request validation.
 */
@ExtendWith(MockitoExtension.class)
class ModerationRuleServiceTest {

    @Mock
    private ModerationRuleRepository ruleRepository;

    private ModerationRuleService service() {
        return new ModerationRuleService(ruleRepository);
    }

    @Test
    void registerRule_isBornEnabledAndSaved() {
        when(ruleRepository.findByTargetTypeAndReason(ReportTargetType.POST, ReportReason.SPAM))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ModerationRule saved = service().registerRule(ReportTargetType.POST, ReportReason.SPAM, 3);

        assertThat(saved.getTargetType()).isEqualTo(ReportTargetType.POST);
        assertThat(saved.getReason()).isEqualTo(ReportReason.SPAM);
        assertThat(saved.getThreshold()).isEqualTo(3);
        assertThat(saved.isEnabled()).as("a registered rule is live — the pause is the separate verb").isTrue();
    }

    @Test
    void registerRule_duplicateLivePair_answers409() {
        ModerationRule existing = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 3);
        when(ruleRepository.findByTargetTypeAndReason(ReportTargetType.POST, ReportReason.SPAM))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service().registerRule(ReportTargetType.POST, ReportReason.SPAM, 5))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");

        verify(ruleRepository, never()).save(any());
    }

    @Test
    void theBoard_carriesEveryLiveRuleInTheStableOrder() {
        when(ruleRepository.findAllByOrderByTargetTypeAscReasonAsc()).thenReturn(List.of(
                ModerationRule.register(ReportTargetType.COMMENT, ReportReason.SPAM, 2),
                ModerationRule.register(ReportTargetType.POST, ReportReason.HARASSMENT, 4)));

        List<ModerationRule> board = service().rules();

        assertThat(board).hasSize(2);
        assertThat(board.get(0).getTargetType()).isEqualTo(ReportTargetType.COMMENT);
    }

    @Test
    void reviseThreshold_carriesTheChangeOnTheRow() {
        UUID ruleId = UUID.randomUUID();
        ModerationRule rule = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 3);
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(rule));

        ModerationRule revised = service().setThreshold(ruleId, 5);

        assertThat(revised.getThreshold()).isEqualTo(5);
        assertThat(revised.isEnabled()).isTrue();
    }

    @Test
    void reviseThreshold_unknownRule_answersTheHonest404() {
        UUID ruleId = UUID.randomUUID();
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().setThreshold(ruleId, 5))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Moderation rule");
    }

    @Test
    void theToggle_pausesAndResumesWithoutLosingTheRow() {
        UUID ruleId = UUID.randomUUID();
        ModerationRule rule = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 3);
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(rule));

        assertThat(service().setEnabled(ruleId, false).isEnabled()).isFalse();
        assertThat(service().setEnabled(ruleId, true).isEnabled()).isTrue();
    }

    @Test
    void retire_takesTheSoftDelete_theTrailSurvivesTheSlotReleases() {
        UUID ruleId = UUID.randomUUID();
        ModerationRule rule = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 3);
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(rule));

        service().retireRule(ruleId);

        // The house soft delete: the repository's delete IS the is_deleted
        // flip (the @SoftDelete machinery) — the row stays for the audit
        // trail, the (targetType, reason) slot releases for a fresh
        // registration.
        verify(ruleRepository).delete(rule);
    }

    @Test
    void theFactoryFloor_thresholdBelowOne_isRejected() {
        // The entity's own gate — the defense in depth behind the request
        // validation and the V161 CHECK.
        assertThatThrownBy(() -> ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }
}
