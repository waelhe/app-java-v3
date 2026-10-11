package com.marketplace.console;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.JsonJdbcType;

import java.util.UUID;

/**
 * Stage 9 (plan D-11, ADR-0005): one planning revision — an immutable,
 * version-numbered snapshot of the operator's planning payload. The
 * current planning IS the highest revision_no (the append-only design:
 * publish appends, rollback republishes the target payload as a new
 * revision — the trail never rewrites and every change keeps its author).
 * Audited per the AGENTS.md rule (the V24 mirror in V175).
 */
@Entity
@Table(name = "console_planning_revisions")
@Audited
public class ConsolePlanningRevision extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "revision_no", nullable = false, unique = true)
    private int revisionNo;

    /** The validated payload (the validator's closed shape, JSONB at rest). */
    @JdbcType(JsonJdbcType.class)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** The operator's own note (the plan's «موسومة» — the change carries its label). */
    @Column(name = "note", length = 500)
    private String note;

    protected ConsolePlanningRevision() {
        // JPA
    }

    private ConsolePlanningRevision(UUID id, int revisionNo, String payload, String note) {
        this.id = id;
        this.revisionNo = revisionNo;
        this.payload = payload;
        this.note = note;
    }

    public static ConsolePlanningRevision append(int revisionNo, String payload, String note) {
        return new ConsolePlanningRevision(UUID.randomUUID(), revisionNo, payload, note);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public int getRevisionNo() {
        return revisionNo;
    }

    public String getPayload() {
        return payload;
    }

    public String getNote() {
        return note;
    }
}
