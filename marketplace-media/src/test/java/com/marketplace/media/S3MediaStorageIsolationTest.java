package com.marketplace.media;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * D.4 (compliance plan wave D) — the storage channel's <b>isolation gate</b>
 * («صمود القنوات الخارجية», Spring Cloud CircuitBreaker's Resilience4j engine
 * per the compliance plan's reference ladder; the programmatic decorator form
 * is the one {@link S3MediaStorage} documents). The scenario under proof is
 * the outage the circuit exists for: a storage endpoint that refuses every
 * request. The measured contract:
 *
 * <ol>
 *   <li>The four network operations count their failures into the
 *       {@code mediaStorage} instance's sliding window.</li>
 *   <li>Once the failure rate crosses the threshold, the circuit OPENS and
 *       every subsequent call fails FAST with {@link CallNotPermittedException}
 *       — the client mock sees <b>zero</b> further invocations (the isolation:
 *       a dead endpoint stops receiving doomed connection attempts and the
 *       caller's thread stops hanging on connect timeouts).</li>
 *   <li>The degradation stays honest per operation: verification answers the
 *       existing "not verifiable" false (the anti-forgery contract — an OPEN
 *       circuit must never read as "upload verified"), while the thumbnail
 *       pipeline's read/write propagate the isolation error for the caller's
 *       own honest failure handling.</li>
 * </ol>
 *
 * <p>The breaker is a real instance from a real registry with a
 * test-compressed window (4 calls, 50% threshold, 60s open wait) — no mock of
 * the resilience machinery itself: the gate must measure the actual
 * Resilience4j state machine, not an assumption about it.
 */
@ExtendWith(MockitoExtension.class)
class S3MediaStorageIsolationTest {

    private static final String BUCKET = "media-bucket";
    private static final String KEY = "listings/abc/photo.jpg";

    @Mock
    private S3Presigner presigner;

    @Mock
    private S3Client client;

    /** The test-compressed circuit: 4-call window, 50% threshold, opens after 2+ failures of 4. */
    private CircuitBreaker fastCircuit() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker("mediaStorage");
    }

    private S3MediaStorage storageWith(CircuitBreaker breaker) {
        return new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15), breaker);
    }

    private void givenStorageOutage() {
        // Lenient by design: the outage scenario stubs every network leg once,
        // and each test exercises its own subset (strict stubbing would flag
        // the legs a focused test never reaches).
        lenient().when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.create("connection refused (outage simulation)"));
        lenient().when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(SdkClientException.create("connection refused (outage simulation)"));
        lenient().when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("503 Slow Down (outage simulation)").build());
        lenient().when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("503 Slow Down (outage simulation)").build());
    }

    @Test
    void sustainedOutageOpensTheCircuitAndIsolatesTheChannel() {
        CircuitBreaker breaker = fastCircuit();
        S3MediaStorage storage = storageWith(breaker);
        givenStorageOutage();

        // Window fills: 4 doomed verification attempts — each counts, each
        // degrades to the honest "not verifiable" false.
        for (int i = 0; i < 4; i++) {
            assertThat(storage.verifyUploaded(KEY, "image/jpeg", 1024L))
                    .as("verification attempt %d during the outage degrades honestly", i + 1)
                    .isFalse();
        }
        assertThat(breaker.getState())
                .as("the failure rate crossed the threshold — the circuit is OPEN")
                .isEqualTo(CircuitBreaker.State.OPEN);
        verify(client, times(4)).headObject(any(HeadObjectRequest.class));

        // THE isolation: the 5th call answers instantly WITHOUT the channel.
        assertThat(storage.verifyUploaded(KEY, "image/jpeg", 1024L))
                .as("an OPEN circuit must never read as 'upload verified' — still the honest false")
                .isFalse();
        verifyNoMoreInteractions(client);

        // The thumbnail pipeline's legs propagate the isolation error for the
        // caller's own honest handling — fail fast, never hang.
        assertThatThrownBy(() -> storage.getObject(KEY))
                .as("the pipeline read is isolated from the dead channel")
                .isInstanceOf(CallNotPermittedException.class);
        assertThatThrownBy(() -> storage.putObject(KEY, "image/jpeg", new byte[] { 1 }))
                .as("the pipeline write is isolated from the dead channel")
                .isInstanceOf(CallNotPermittedException.class);
        assertThatThrownBy(() -> storage.deleteObject(KEY))
                .as("the best-effort delete is isolated too — the caller's WARN log fires instantly")
                .isInstanceOf(CallNotPermittedException.class);
        verifyNoMoreInteractions(client);
    }

    @Test
    void openCircuitStateIsRecoverableByDesign() {
        // The wait-duration in open state (60s here, 60s in application.yml's
        // base config) is the official half-open probe path — not exercised
        // wall-clock-wise in a unit test; the pinned fact is that the instance
        // the storage rides reports the OPEN state itself, so the platform's
        // alerting (the resilience4j circuitbreaker metrics feed) can see the
        // channel's health the same way it sees paymentProcessing's.
        CircuitBreaker breaker = fastCircuit();
        S3MediaStorage storage = storageWith(breaker);
        givenStorageOutage();

        for (int i = 0; i < 4; i++) {
            storage.verifyUploaded(KEY, "image/jpeg", 1024L);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("the window's failed calls are visible to the metrics feed")
                .isEqualTo(4);
    }
}
