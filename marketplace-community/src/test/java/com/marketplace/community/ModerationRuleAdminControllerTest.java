package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-19 (compliance plan C.11) — the rules' administrative surface's
 * delegation contract (the L45/L30 pattern's controller leg): the two
 * type gates (target type / reason) parse BEFORE any service call (the
 * parseStatus/parseAction convention — an invalid value answers the
 * house 400 with the valid vocabulary listed), the four endpoints
 * delegate with the REST statuses, and the class-level ADMIN gate +
 * the chain's own {@code /api/v1/admin/**} rule are pinned by the
 * standing admin-chain security tests (the house's three-layer
 * authorization — the negative 403 lives there).
 */
@ExtendWith(MockitoExtension.class)
class ModerationRuleAdminControllerTest {

    @Mock
    private ModerationRuleService ruleService;

    @InjectMocks
    private ModerationRuleAdminController controller;

    @Test
    void theBoard_answers200WithTheDelegatedRows() {
        when(ruleService.rules()).thenReturn(List.of(
                ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 3)));

        ResponseEntity<List<ModerationRuleView>> response = controller.rules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).targetType()).isEqualTo("POST");
        assertThat(response.getBody().get(0).reason()).isEqualTo("SPAM");
        assertThat(response.getBody().get(0).threshold()).isEqualTo(3);
        assertThat(response.getBody().get(0).enabled()).isTrue();
    }

    @Test
    void register_parsesBothVocabulariesAndAnswers201() {
        ModerationRule saved = ModerationRule.register(ReportTargetType.COMMENT, ReportReason.HARASSMENT, 2);
        when(ruleService.registerRule(ReportTargetType.COMMENT, ReportReason.HARASSMENT, 2))
                .thenReturn(saved);

        ResponseEntity<ModerationRuleView> response = controller.register(
                new ModerationRuleAdminController.RuleRegistrationRequest("COMMENT", "HARASSMENT", 2));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().targetType()).isEqualTo("COMMENT");
        assertThat(response.getBody().reason()).isEqualTo("HARASSMENT");
        verify(ruleService).registerRule(ReportTargetType.COMMENT, ReportReason.HARASSMENT, 2);
    }

    @Test
    void register_badTargetType_answers400ListingTheVocabulary() {
        assertThatThrownBy(() -> controller.register(
                new ModerationRuleAdminController.RuleRegistrationRequest("LISTING", "SPAM", 3)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("POST, COMMENT, REVIEW");

        verify(ruleService, org.mockito.Mockito.never()).registerRule(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void register_badReason_answers400ListingTheVocabulary() {
        assertThatThrownBy(() -> controller.register(
                new ModerationRuleAdminController.RuleRegistrationRequest("POST", "FLOOD", 3)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SPAM, HARASSMENT, INAPPROPRIATE, OTHER");
    }

    @Test
    void reviseThreshold_delegatesAndAnswers200() {
        UUID ruleId = UUID.randomUUID();
        ModerationRule revised = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 5);
        when(ruleService.setThreshold(ruleId, 5)).thenReturn(revised);

        ResponseEntity<ModerationRuleView> response = controller.reviseThreshold(
                ruleId, new ModerationRuleAdminController.ThresholdRevisionRequest(5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().threshold()).isEqualTo(5);
    }

    @Test
    void theEnabledToggle_delegatesAndAnswers200() {
        UUID ruleId = UUID.randomUUID();
        ModerationRule paused = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 3);
        when(ruleService.setEnabled(ruleId, false)).thenReturn(paused);

        ResponseEntity<ModerationRuleView> response = controller.setEnabled(
                ruleId, new ModerationRuleAdminController.EnabledFlipRequest(false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ruleService).setEnabled(ruleId, false);
    }

    @Test
    void retire_delegatesAndAnswers204() {
        UUID ruleId = UUID.randomUUID();

        ResponseEntity<Void> response = controller.retire(ruleId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(ruleService).retireRule(ruleId);
    }
}
