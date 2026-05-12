package com.marketplace.disputes;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "reports")
public class Report extends BaseEntity {
    @Id private UUID id;
    @Column(name = "reported_by", nullable = false) private UUID reportedBy;
    @Column(name = "target_id", nullable = false) private UUID targetId;
    @Column(name = "reason", nullable = false, length = 500) private String reason;
    protected Report() {}
    public Report(UUID id, UUID reportedBy, UUID targetId, String reason) { this.id = id; this.reportedBy = reportedBy; this.targetId = targetId; this.reason = reason; }
    @Override public UUID getId() { return id; }
}
