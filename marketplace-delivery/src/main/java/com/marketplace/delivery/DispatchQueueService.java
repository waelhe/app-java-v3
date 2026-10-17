package com.marketplace.delivery;

import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The V226 dispatch board (the architecture §4.2: «Dispatch queue/map,
 * failed or delayed jobs, unassigned jobs, at-risk promises»): the
 * per-state counts, the state-filtered queue pages, and the at-risk
 * exception count — the operational reads the dispatcher's workbench is
 * built on. Source state machines stay in their domain owners; THIS is
 * the read surface over them.
 */
@Service
@Transactional(readOnly = true)
public class DispatchQueueService {

    private final DeliveryRequestRepository requests;
    private final DeliveryProperties properties;

    public DispatchQueueService(DeliveryRequestRepository requests,
                                DeliveryProperties properties) {
        this.requests = requests;
        this.properties = properties;
    }

    /** The board's snapshot: per-state counts + the at-risk exception count. */
    @Observed(name = "delivery.dispatch.queues")
    public DispatchQueuesSnapshot snapshot() {
        Map<DeliveryRequestStatus, Long> counts = new LinkedHashMap<>();
        for (DeliveryRequestStatus state : List.of(DeliveryRequestStatus.AWAITING_PAYMENT,
                DeliveryRequestStatus.PAYMENT_FAILED, DeliveryRequestStatus.READY_FOR_DISPATCH,
                DeliveryRequestStatus.ASSIGNED, DeliveryRequestStatus.PICKED_UP,
                DeliveryRequestStatus.IN_TRANSIT)) {
            counts.put(state, 0L);
        }
        for (Object[] row : requests.countActiveByStatus()) {
            counts.put((DeliveryRequestStatus) row[0], (Long) row[1]);
        }
        long atRisk = requests.countAtRisk(properties.atRiskDurationMultiplier(), Instant.now());
        return new DispatchQueuesSnapshot(counts, atRisk);
    }

    /** One state's queue page — the dispatcher's work list. */
    public Page<DeliveryRequestResponse> queue(DeliveryRequestStatus state, Pageable pageable) {
        return requests.findByStatusOrderByCreatedAtAscIdAsc(state, pageable)
                .map(r -> DeliveryRequestResponse.from(r, null));
    }

    /** The board's snapshot shape. */
    public record DispatchQueuesSnapshot(Map<DeliveryRequestStatus, Long> activeByState,
                                         long atRiskPromises) {
    }
}
