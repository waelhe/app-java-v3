package com.marketplace.console;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 9 (plan D-11, ADR-0005): the planning record's surface — the
 * operator's publish/rollback (the ADMIN class gate, the
 * {@code ConsoleAdminController} shape verbatim) and the clients' public
 * read of the current planning (the renderer's known vocabulary — the
 * API exposes data, never code).
 */
@RestController
@RequestMapping(version = "1.0")
public class ConsolePlanningController {

    private final ConsolePlanningService planningService;

    public ConsolePlanningController(ConsolePlanningService planningService) {
        this.planningService = planningService;
    }

    /** The clients' read — the current planning revision's payload. */
    @GetMapping(ApiConstants.API_V1 + "/console/planning")
    @Operation(summary = "Read the current planning (public)",
            description = "The highest valid revision's payload — the sections the client "
                    + "renders from its own known component vocabulary (the API exposes "
                    + "data, never code).")
    public ResponseEntity<PlanningRevisionResponse> current() {
        return ResponseEntity.ok(PlanningRevisionResponse.of(planningService.current()));
    }

    @PostMapping(ApiConstants.ADMIN + "/console/planning")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Publish a new planning revision (ADMIN)",
            description = "The payload is validated against the SUPPORTED component "
                    + "vocabulary and the catalog's category dictionary BEFORE any write — "
                    + "an invalid payload is refused (400) and nothing is stored.")
    public ResponseEntity<PlanningRevisionResponse> publish(
            @Valid @RequestBody PublishPlanningRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(PlanningRevisionResponse.of(
                planningService.publish(request.payload(), request.note())));
    }

    @PostMapping(ApiConstants.ADMIN + "/console/planning/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Roll back to a previous revision (ADMIN)",
            description = "The target payload is REPUBLISHED as a new revision — the "
                    + "append-only trail never rewrites, every change keeps its author.")
    public ResponseEntity<PlanningRevisionResponse> rollback(
            @Valid @RequestBody RollbackPlanningRequest request) {
        return ResponseEntity.ok(PlanningRevisionResponse.of(
                planningService.rollback(request.toVersion(), request.note())));
    }

    public record PublishPlanningRequest(
            @NotBlank String payload,
            @Size(max = 500) String note) {
    }

    public record RollbackPlanningRequest(
            @NotBlank Integer toVersion,
            @Size(max = 500) String note) {
    }

    public record PlanningRevisionResponse(int revisionNo, String payload, String note) {
        static PlanningRevisionResponse of(ConsolePlanningRevision revision) {
            return new PlanningRevisionResponse(revision.getRevisionNo(),
                    revision.getPayload(), revision.getNote());
        }
    }
}
