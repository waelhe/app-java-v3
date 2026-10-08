package com.marketplace.jobs;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * B-12 (compliance plan C.2 — the jobs module): the employment vertical's
 * REST surface, on the {@code LeadsController}/{@code ReviewsController}
 * house shapes — {@code ApiConstants.API_V1} root, the house's Spring
 * Data web pagination, the {@code /me} seam for the caller's own lists,
 * PATCH for the one-way moves, and the OpenAPI vocabulary for the
 * springdoc-generated mobile contract.
 *
 * <p><b>The journey's eight doors (the module DoD as a journey):</b>
 * post ({@code POST /jobs}) → the board ({@code GET /jobs}) → the detail
 * ({@code GET /jobs/{id}}) → my posts ({@code GET /jobs/me}) → close
 * ({@code PATCH /jobs/{id}}) → apply
 * ({@code POST /jobs/{id}/applications}) → the inbox
 * ({@code GET /jobs/{id}/applications}) + my applications
 * ({@code GET /jobs/me/applications}) → decide
 * ({@code PATCH /jobs/{id}/applications/{applicationId}}) and withdraw
 * ({@code DELETE /jobs/{id}/applications/{applicationId}}).</p>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class JobsController {

    private final JobsService jobsService;

    public JobsController(JobsService jobsService) {
        this.jobsService = jobsService;
    }

    @PostMapping("/jobs")
    @Operation(summary = "Post a job opening",
            description = "The calling employer posts a job — born ACTIVE on the public board. "
                    + "The salary block is all-or-nothing: both bounds and the currency together, or none.")
    public ResponseEntity<JobResponse> create(
            @Valid @RequestBody JobRequest request, Authentication authentication) {
        JobResponse response = JobResponse.from(jobsService.create(request, authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/jobs")
    @Operation(summary = "The public jobs board",
            description = "Paginated board — ACTIVE by default, newest-posted first with the "
                    + "deterministic order. Optional filters: status, city, employment type, "
                    + "workplace type.")
    public ResponseEntity<PagedResponse<JobResponse>> board(
            @Parameter(description = "ACTIVE by default; CLOSED reachable for the honest history.")
            @RequestParam(required = false) JobStatus status,
            @Parameter(description = "Exact city match (e.g. الرياض).")
            @RequestParam(required = false) String city,
            @Parameter(description = "FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP or VOLUNTEER.")
            @RequestParam(required = false) EmploymentType employmentType,
            @Parameter(description = "ONSITE, REMOTE or HYBRID.")
            @RequestParam(required = false) WorkplaceType workplaceType,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                jobsService.searchBoard(status, city, employmentType, workplaceType, pageable)
                        .map(JobResponse::from)));
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "Read one job opening",
            description = "Any live job by id — CLOSED included (the row is a fact); unknown is 404.")
    public ResponseEntity<JobResponse> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(JobResponse.from(jobsService.getJob(id)));
    }

    @GetMapping("/jobs/me")
    @Operation(summary = "List my posted jobs",
            description = "The calling employer's own posts — every status, newest first.")
    public ResponseEntity<PagedResponse<JobResponse>> myJobs(
            Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                jobsService.myJobs(authentication, pageable).map(JobResponse::from)));
    }

    @PatchMapping("/jobs/{id}")
    @Operation(summary = "Close a job opening",
            description = "The one-way move: ACTIVE→CLOSED — the board stops returning it, the "
                    + "detail stays honest, applications stop. A foreign job is a 404.")
    public ResponseEntity<JobResponse> close(
            @PathVariable UUID id,
            @Valid @RequestBody JobTransitionRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(JobResponse.from(jobsService.close(id, authentication)));
    }

    @PostMapping("/jobs/{id}/applications")
    @Operation(summary = "Apply to a job opening",
            description = "The calling seeker's application lands in the employer's inbox — 201. "
                    + "Guarded: not the employer's own job (400), the job ACTIVE (409), the "
                    + "deadline not passed (409), one live application per job (409).")
    public ResponseEntity<JobApplicationResponse> apply(
            @PathVariable UUID id,
            @Valid @RequestBody JobApplicationRequest request,
            Authentication authentication) {
        JobApplicationResponse response =
                JobApplicationResponse.from(jobsService.apply(id, request, authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/jobs/{id}/applications")
    @Operation(summary = "The employer's application inbox for one job",
            description = "Paginated — newest first, optional status filter. A foreign job is a 404.")
    public ResponseEntity<PagedResponse<JobApplicationResponse>> jobInbox(
            @PathVariable UUID id,
            @Parameter(description = "Filter by status — omitted returns all.")
            @RequestParam(required = false) ApplicationStatus status,
            Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                jobsService.jobInbox(id, authentication, status, pageable)
                        .map(JobApplicationResponse::from)));
    }

    @GetMapping("/jobs/me/applications")
    @Operation(summary = "List my applications",
            description = "The calling seeker's own applications across every job — newest first, "
                    + "optional status filter.")
    public ResponseEntity<PagedResponse<JobApplicationResponse>> myApplications(
            @Parameter(description = "Filter by status — omitted returns all.")
            @RequestParam(required = false) ApplicationStatus status,
            Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                jobsService.myApplications(authentication, status, pageable)
                        .map(JobApplicationResponse::from)));
    }

    @PatchMapping("/jobs/{id}/applications/{applicationId}")
    @Operation(summary = "Move an application (the employer's decision)",
            description = "One-way moves only: NEW→REVIEWED/REJECTED, REVIEWED→ACCEPTED/REJECTED — "
                    + "both decisions terminal. A foreign pair is a 404; an illegal move is a 400.")
    public ResponseEntity<JobApplicationResponse> moveApplication(
            @PathVariable UUID id,
            @PathVariable UUID applicationId,
            @Valid @RequestBody ApplicationTransitionRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(JobApplicationResponse.from(
                jobsService.moveApplication(id, applicationId, request.status(), authentication)));
    }

    @DeleteMapping("/jobs/{id}/applications/{applicationId}")
    @Operation(summary = "Withdraw an application (the seeker's own)",
            description = "The soft delete — the row keeps its audit trail, the reads stop "
                    + "returning it, and a fresh application can follow. A foreign pair is a 404.")
    public ResponseEntity<Void> withdraw(
            @PathVariable UUID id,
            @PathVariable UUID applicationId,
            Authentication authentication) {
        jobsService.withdraw(id, applicationId, authentication);
        return ResponseEntity.noContent().build();
    }

}
