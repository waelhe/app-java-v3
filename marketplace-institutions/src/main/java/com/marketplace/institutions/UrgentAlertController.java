package com.marketplace.institutions;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.UrgentAlertsPort;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the alert's
 * public read surface, on the {@code InstitutionController} house shape
 * — and deliberately THROUGH THE PORT's own semantics: the read injects
 * the {@link UrgentAlertsPort} (the module's own
 * {@code spi/UrgentAlertsAdapter} bean) instead of touching the engine's
 * entities, so what this surface serves is exactly what every other
 * consumer surface (the discovery rail, the notifications consumers)
 * serves — one seam, one truth (JT-10: the withdrawal reflects on every
 * surface through the same seam; AC-20-01: a VERIFIED source is a
 * display precondition, and a withdrawn or expired alert answers
 * silence).
 *
 * <p>Every endpoint sits behind the resource-server chain's
 * {@code anyRequest().authenticated()} — no security-config change, the
 * same zero-config line every layer rides; the {@code CurrentUserProvider}
 * /me seam resolves the caller (the neighborhoods' own discipline), and
 * {@code locationId} is REQUIRED — the scope is the caller's chosen
 * level-3 neighborhood, never an implicit one.</p>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class UrgentAlertController {

    private final UrgentAlertsPort urgentAlertsPort;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public UrgentAlertController(UrgentAlertsPort urgentAlertsPort,
                                 CurrentUserProvider currentUserProvider,
                                 Clock clock) {
        this.urgentAlertsPort = urgentAlertsPort;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    @GetMapping("/urgent-alerts")
    @Operation(summary = "Read a neighborhood's active urgent alerts",
            description = "The official alerts whose validity window covers now and that were "
                    + "not withdrawn, from VERIFIED delegated sources only (MUNICIPALITY, "
                    + "CIVIL_DEFENSE, UTILITIES, HEALTH_AUTHORITY, EDUCATION_AUTHORITY, "
                    + "OTHER_DELEGATED) — the same seam every surface reads (AC-20-01: a trusted "
                    + "source is a display precondition; JT-10: a withdrawn alert answers "
                    + "silence). Freshest first (validFrom DESC), the level rendered as text "
                    + "(CMP-46: نص لا إشارة شعبية). locationId is required — the alert's scope "
                    + "neighborhood (a level-3 node); unknown or out-of-scope nodes simply "
                    + "answer an empty list.")
    public List<UrgentAlertResponse> active(
            @Parameter(description = "The scope neighborhood — a level-3 geo tree node id "
                    + "(required).", required = true)
            @RequestParam UUID locationId,
            Authentication authentication) {
        currentUserProvider.getCurrentUserId(authentication);
        return urgentAlertsPort.findActive(locationId, clock.instant()).stream()
                .map(UrgentAlertResponse::from)
                .toList();
    }
}
