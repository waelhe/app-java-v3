package com.marketplace.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * B-12 (compliance plan C.2): the REST surface's statuses — the direct
 * controller-invocation house pattern ({@code NotificationControllerTest}):
 * 201 on the two writes, 200 on the reads and moves, 204 on the
 * withdrawal, the paged envelopes riding {@code PagedResponse}.
 */
@ExtendWith(MockitoExtension.class)
class JobsControllerTest {

    @Mock
    private JobsService service;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private JobsController controller;

    private JobListing job() {
        return JobListing.create(UUID.randomUUID(), "مطلوب مصمم", "الوصف",
                EmploymentType.FULL_TIME, WorkplaceType.HYBRID, "الرياض", "حي القضية",
                500_000L, 900_000L, "SAR", Instant.now().plus(Duration.ofDays(30)));
    }

    @Test
    void createAnswers201WithThePostedJob() {
        var request = new JobRequest("مطلوب مصمم", "الوصف",
                EmploymentType.FULL_TIME, WorkplaceType.HYBRID, "الرياض", null,
                null, null, null, null);
        when(service.create(request, authentication)).thenReturn(job());

        ResponseEntity<JobResponse> result = controller.create(request, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().status()).isEqualTo("ACTIVE");
        assertThat(result.getBody().employmentType()).isEqualTo("FULL_TIME");
    }

    @Test
    void boardAnswers200WithThePagedEnvelope() {
        var pageable = PageRequest.of(0, 20);
        when(service.searchBoard(null, "الرياض", null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(job()), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<JobResponse>> result =
                controller.board(null, "الرياض", null, null, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
    }

    @Test
    void detailAnswers200() {
        JobListing job = job();
        when(service.getJob(job.getId())).thenReturn(job);

        ResponseEntity<JobResponse> result = controller.detail(job.getId());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().id()).isEqualTo(job.getId());
        assertThat(result.getBody().title()).isEqualTo("مطلوب مصمم");
        assertThat(result.getBody().city()).isEqualTo("الرياض");
    }

    @Test
    void myJobsAnswers200WithThePagedEnvelope() {
        var pageable = PageRequest.of(0, 20);
        when(service.myJobs(authentication, pageable))
                .thenReturn(new PageImpl<>(List.of(job()), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<JobResponse>> result =
                controller.myJobs(pageable, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
    }

    @Test
    void closeAnswers200WithTheClosedJob() {
        UUID id = UUID.randomUUID();
        JobListing closed = job();
        closed.close();
        when(service.close(id, authentication)).thenReturn(closed);

        ResponseEntity<JobResponse> result =
                controller.close(id, new JobTransitionRequest(JobStatus.CLOSED), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().status()).isEqualTo("CLOSED");
    }

    @Test
    void applyAnswers201() {
        UUID jobId = UUID.randomUUID();
        when(service.apply(jobId, new JobApplicationRequest("رسالة"), authentication))
                .thenReturn(JobApplication.create(jobId, UUID.randomUUID(), "رسالة"));

        ResponseEntity<JobApplicationResponse> result = controller.apply(jobId,
                new JobApplicationRequest("رسالة"), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().status()).isEqualTo("NEW");
    }

    @Test
    void jobInboxAnswers200WithThePagedEnvelope() {
        UUID jobId = UUID.randomUUID();
        var pageable = PageRequest.of(0, 20);
        when(service.jobInbox(jobId, authentication, null, pageable))
                .thenReturn(new PageImpl<>(List.of(
                        JobApplication.create(jobId, UUID.randomUUID(), "رسالة")), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<JobApplicationResponse>> result =
                controller.jobInbox(jobId, null, pageable, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
    }

    @Test
    void myApplicationsAnswers200WithThePagedEnvelope() {
        var pageable = PageRequest.of(0, 20);
        when(service.myApplications(authentication, null, pageable))
                .thenReturn(new PageImpl<>(List.of(
                        JobApplication.create(UUID.randomUUID(), UUID.randomUUID(), "رسالة")), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<JobApplicationResponse>> result =
                controller.myApplications(null, pageable, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
    }

    @Test
    void moveApplicationAnswers200() {
        UUID jobId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        JobApplication application = JobApplication.create(jobId, UUID.randomUUID(), "رسالة");
        application.move(ApplicationStatus.REVIEWED);
        when(service.moveApplication(jobId, applicationId, ApplicationStatus.REVIEWED, authentication))
                .thenReturn(application);

        ResponseEntity<JobApplicationResponse> result = controller.moveApplication(jobId, applicationId,
                new ApplicationTransitionRequest(ApplicationStatus.REVIEWED), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().status()).isEqualTo("REVIEWED");
    }

    @Test
    void withdrawAnswers204() {
        UUID jobId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();

        ResponseEntity<Void> result = controller.withdraw(jobId, applicationId, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
