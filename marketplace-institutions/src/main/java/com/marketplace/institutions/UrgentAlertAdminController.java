package com.marketplace.institutions;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.UUID;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the administrative
 * delegation and alert surface, on the
 * {@code InstitutionAdminController} house shape verbatim — the
 * class-level {@code @PreAuthorize("hasRole('ADMIN')")} gate (من يفوض؟
 * نفس بوابة admin الحالية — an official body cannot self-declare its own
 * authority), the delegation review queue (the optional state axis, the
 * complete drain order), the verdict's ONLY mover (confirm/reject), and
 * the alert's own commands (publish on a VERIFIED source, withdraw —
 * JT-10's honesty leg whose signal rides every surface).
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class UrgentAlertAdminController {

    private final UrgentAlertService urgentAlertService;

    public UrgentAlertAdminController(UrgentAlertService urgentAlertService) {
        this.urgentAlertService = urgentAlertService;
    }

    @GetMapping("/urgent-alert-sources")
    @Operation(summary = "Read the delegation review queue (admin)",
            description = "The delegation registry by verification state — the optional state axis "
                    + "filters UNVERIFIED/PENDING/VERIFIED/REJECTED; absent = the whole registry. "
                    + "PENDING is the reviewable queue on its complete drain order (the state's "
                    + "own clock: updatedAt ASC, id ASC — oldest pending claim first). "
                    + "Deterministic pagination; read-only.")
    public ResponseEntity<PagedResponse<UrgentAlertSourceResponse>> queue(
            @Parameter(description = "Optional verification state filter — UNVERIFIED, PENDING, "
                    + "VERIFIED or REJECTED")
            @RequestParam(required = false) InstitutionVerificationState state,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                urgentAlertService.reviewQueue(state, pageable)
                        .map(UrgentAlertSourceResponse::from)));
    }

    @PostMapping("/urgent-alert-sources")
    @Operation(summary = "Register a delegated urgent-alert source",
            description = "The administrative delegation's entry: born UNVERIFIED on the honest "
                    + "registry (the authority lands only through the review — confirm/reject). "
                    + "The type vocabulary: MUNICIPALITY, CIVIL_DEFENSE, UTILITIES, "
                    + "HEALTH_AUTHORITY, EDUCATION_AUTHORITY, OTHER_DELEGATED.")
    public ResponseEntity<UrgentAlertSourceResponse> createSource(
            @Valid @RequestBody UrgentAlertSourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UrgentAlertSourceResponse.from(urgentAlertService.createSource(request)));
    }

    @PostMapping("/urgent-alert-sources/{sourceId}/verification/request")
    @Operation(summary = "Request the delegation review",
            description = "UNVERIFIED → PENDING only — the review queue's own gate. A REJECTED "
                    + "source cannot self-reverse (the recovery lever is the administrator's "
                    + "confirm); an unknown source is a 404.")
    public ResponseEntity<UrgentAlertSourceResponse> requestVerification(@PathVariable UUID sourceId) {
        return ResponseEntity.ok(UrgentAlertSourceResponse.from(
                urgentAlertService.requestSourceVerification(sourceId)));
    }

    @PostMapping("/urgent-alert-sources/{sourceId}/verification/confirm")
    @Operation(summary = "Confirm the delegation (the verdict's mover — approve)",
            description = "The delegation lands: a PENDING source becomes VERIFIED (its alerts "
                    + "become eligible for every surface — AC-20-01's deterministic eligibility) "
                    + "and a REJECTED one is RE-ADMITTED (the recovery lever). Any other source "
                    + "state answers 409 with the machine's own transition words.")
    public ResponseEntity<UrgentAlertSourceResponse> confirmVerification(@PathVariable UUID sourceId) {
        return ResponseEntity.ok(UrgentAlertSourceResponse.from(
                urgentAlertService.reviewSource(sourceId, true)));
    }

    @PostMapping("/urgent-alert-sources/{sourceId}/verification/reject")
    @Operation(summary = "Reject the delegation",
            description = "REJECT refuses a PENDING claim (the row stays — the honest registry — "
                    + "the authority never lands, and the source's alerts never serve). Any other "
                    + "source state answers 409.")
    public ResponseEntity<UrgentAlertSourceResponse> rejectVerification(@PathVariable UUID sourceId) {
        return ResponseEntity.ok(UrgentAlertSourceResponse.from(
                urgentAlertService.reviewSource(sourceId, false)));
    }

    @PostMapping("/urgent-alerts")
    @Operation(summary = "Publish an urgent alert from a VERIFIED source",
            description = "JT-10's three facts in one write: مصدر مفوض (the source must be "
                    + "VERIFIED — otherwise 409, the deterministic eligibility AC-20-01), نطاق "
                    + "(the locationId resolves through the geo port — 404 unknown — and must be "
                    + "a level-3 neighborhood node — 400 otherwise), سريان (validFrom required; "
                    + "validUntil optional and strictly after validFrom — 400 otherwise; absent "
                    + "means open-ended, withdrawal is then the only off-switch). The publication "
                    + "event rides the same transaction (AFTER_COMMIT consumers — the Modulith "
                    + "discipline). CMP-46: the level is rendered as text on every surface, never "
                    + "a popularity signal.")
    public ResponseEntity<UrgentAlertResponse> publish(
            @Valid @RequestBody UrgentAlertPublishRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UrgentAlertResponse.from(urgentAlertService.publishAlert(request)));
    }

    @PostMapping("/urgent-alerts/{alertId}/withdraw")
    @Operation(summary = "Withdraw an urgent alert (the honesty leg)",
            description = "JT-10: «تصحيح/سحب ينعكس على كل الأسطح» — the flags move once (a second "
                    + "withdrawal answers 409), the row and its audit trail stay, and the "
                    + "withdrawal event rides the same transaction: every surface stops serving "
                    + "the alert the moment this commits (the UrgentAlertsPort answers withdrawn "
                    + "alerts with silence; the notifications ledger keeps the delivery facts). "
                    + "Unknown alert is a 404.")
    public ResponseEntity<UrgentAlertResponse> withdraw(@PathVariable UUID alertId) {
        return ResponseEntity.ok(UrgentAlertResponse.from(urgentAlertService.withdrawAlert(alertId)));
    }
}
