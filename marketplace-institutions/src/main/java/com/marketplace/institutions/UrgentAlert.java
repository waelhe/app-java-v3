package com.marketplace.institutions;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): one delegated
 * source's alert, on the {@link Institution}/{@code KnowledgeEntry}
 * house shapes — {@code @Audited} over the full BaseEntity column set
 * from day one.
 *
 * <p><b>The two lifecycle legs (deliberately separate):</b></p>
 * <ul>
 *   <li>{@code isWithdrawn}/{@code withdrawnAt} — the DOMAIN's honesty
 *       leg (JT-10's «تصحيح/سحب ينعكس على كل الأسطح»): the withdrawal
 *       keeps the row AND its audit trail while every surface stops
 *       serving it — the {@code UrgentAlertsPort} adapter answers a
 *       withdrawn alert with silence, and the
 *       {@code UrgentAlertWithdrawnEvent} carries the same decision to
 *       the Modulith consumers (the knowledge withdraw precedent). NOT
 *       the house soft delete — a withdrawn alert is a delivery fact the
 *       notifications ledger keeps, an audit trail the Envers mirror
 *       keeps; only the DISPLAY dies.</li>
 *   <li>{@code isDeleted} — BaseEntity's row-lifecycle leg (the b-2/b-3
 *       retention seams), unchanged semantics.</li>
 * </ul>
 *
 * <p><b>The seams (all measured house conventions):</b></p>
 * <ul>
 *   <li>{@code sourceId} — the sanctioned INTERNAL reference (the V7
 *       {@code messages.conversation_id} / V83 {@code event_rsvps.event_id}
 *       precedent): a plain UUID column in Java, a real FK in SQL (V178)
 *       — the alert and its source are one aggregate inside the module's
 *       own boundary. The source must be VERIFIED before any publish
 *       (the service's 409 — AC-20-01's deterministic eligibility).</li>
 *   <li>{@code locationId} — the geo tree node (level 3, the
 *       neighborhood the alert scopes — gated by the service through
 *       {@code GeoLookupPort} exactly like the registry's own anchor; a
 *       plain UUID column with NO JPA/DB relation across module
 *       boundaries — the V32/V48/V54/V83 discipline).</li>
 *   <li>{@code validFrom}/{@code validUntil} — JT-10's سريان (the
 *       explicit validity window): the alert lives exactly while the
 *       window covers {@code now} — absent {@code validUntil} means
 *       open-ended (withdrawal is then the only off-switch), and the
 *       window's order rule is the V83 time rule's twin (the service's
 *       friendly 400 first, the V178 CHECK the backstop).</li>
 *   <li>{@code level} — {@link UrgentAlertLevel}: CMP-46's نص لا إشارة
 *       شعبية — rendered as text, never a ranking weight.</li>
 * </ul>
 */
@Entity
@Table(name = "urgent_alerts")
@Audited
public class UrgentAlert extends BaseEntity {

    @Id
    private UUID id;

    /** The delegating source's id — same-module aggregate (a real FK in V178, a plain UUID here). */
    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    /** The geo tree node — level 3 (the alert's scope neighborhood), gated by the service. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 20)
    private UrgentAlertLevel level;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** The window's open end — absent means open-ended (withdrawal is the only off-switch). */
    @Column(name = "valid_until")
    private Instant validUntil;

    /** The honesty leg: true means every surface stops serving the alert (JT-10). */
    @Column(name = "is_withdrawn", nullable = false)
    private boolean withdrawn;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    protected UrgentAlert() {
    }

    private UrgentAlert(UUID id, UUID sourceId, UUID locationId, UrgentAlertLevel level,
                        String title, String body, Instant validFrom, Instant validUntil) {
        this.id = id;
        this.sourceId = sourceId;
        this.locationId = locationId;
        this.level = level;
        this.title = title;
        this.body = body;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.withdrawn = false;
    }

    /**
     * The publication factory: born live (the source is VERIFIED — the
     * service gates that BEFORE any write — and the window covers its
     * own start). The level-3 geo gate and the window-order guard live
     * in the service; this factory is the honest insert shape.
     */
    public static UrgentAlert publish(UUID sourceId, UUID locationId, UrgentAlertLevel level,
                                      String title, String body, Instant validFrom, Instant validUntil) {
        return new UrgentAlert(UUID.randomUUID(), sourceId, locationId, level, title, body,
                validFrom, validUntil);
    }

    /**
     * The withdrawal (JT-10's honesty leg): the flags move ONCE — a
     * second withdrawal is the machine's own
     * {@link IllegalStateException} (the caller renders the house 409).
     * The row and its Envers trail stay; only the display dies.
     */
    public void withdraw(Instant at) {
        if (withdrawn) {
            throw new IllegalStateException("Only a live alert can be withdrawn");
        }
        this.withdrawn = true;
        this.withdrawnAt = at;
    }

    /** The window's own predicate: the alert lives while it covers {@code now}. */
    public boolean covers(Instant now) {
        return !withdrawn
                && !validFrom.isAfter(now)
                && (validUntil == null || validUntil.isAfter(now));
    }

    @Override
    public UUID getId() { return id; }
    public UUID getSourceId() { return sourceId; }
    public UUID getLocationId() { return locationId; }
    public UrgentAlertLevel getLevel() { return level; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public boolean isWithdrawn() { return withdrawn; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
}
