package com.marketplace.institutions.spi;

import com.marketplace.institutions.UrgentAlertService;
import com.marketplace.shared.api.UrgentAlertsPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the institutions
 * module's implementation of the {@link UrgentAlertsPort} cross-module
 * contract — the discovery rails and the notifications consumers resolve
 * a neighborhood's active urgent alerts through this seam, never through
 * a module dependency (the {@code PostLookupAdapter} house pattern: the
 * interface lives in shared-api, the data owner implements it, the
 * consumer injects it).
 *
 * <p><b>The port's contract is the service's eligibility engine verbatim
 * (one seam, one truth):</b> {@link UrgentAlertService#activeAlerts}
 * answers only alerts that are NOT withdrawn, whose validity window
 * covers {@code now}, and whose source is VERIFIED — the deterministic
 * eligibility AC-20-01 names (a trusted source is a display
 * precondition). A withdrawn alert answers silence HERE — the withdrawal
 * reflects on every surface that reads the port (JT-10), never by a
 * consumer's own filter; an unverified source's alerts never leak either.
 * The card's {@code level} and {@code sourceType} ride as TEXT (CMP-46:
 * the urgency level is rendered, never weighted).</p>
 */
@Component
@Transactional(readOnly = true)
public class UrgentAlertsAdapter implements UrgentAlertsPort {

    private final UrgentAlertService urgentAlertService;

    public UrgentAlertsAdapter(UrgentAlertService urgentAlertService) {
        this.urgentAlertService = urgentAlertService;
    }

    @Override
    public List<UrgentAlertCard> findActive(UUID locationId, Instant now) {
        return urgentAlertService.activeAlerts(locationId, now).stream()
                .map(active -> {
                    var alert = active.alert();
                    var source = active.source();
                    return new UrgentAlertCard(
                            alert.getId(),
                            source.getId(),
                            source.getName(),
                            source.getSourceType().name(),
                            alert.getLevel().name(),
                            alert.getTitle(),
                            alert.getBody(),
                            alert.getLocationId(),
                            alert.getValidFrom(),
                            alert.getValidUntil(),
                            alert.getUpdatedAt());
                })
                .toList();
    }
}
