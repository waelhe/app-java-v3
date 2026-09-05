package com.marketplace.shared.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.SoftDelete;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Base entity with optimistic locking, auditing, and soft delete support.
 * Uses Hibernate 7 {@code @SoftDelete} — no manual field or filter needed.
 * Hibernate automatically adds WHERE is_deleted = false to all queries,
 * and converts DELETE statements to UPDATE is_deleted = true.
 *
 * <p><b>Serializable (Java Object Serialization Specification, chapter 4):</b>
 * the Redis cache stores entity values with Spring Boot's default
 * {@code JdkSerializationRedisSerializer} (spring-boot-cache 4.1.1 —
 * {@code RedisCacheConfiguration} value serialization defaults to the JDK
 * serializer). Seven @Cacheable sites cache entities extending this class
 * (reviews, bookings, users ×2, conversations, paymentIntents, providers);
 * a class that does not implement {@link Serializable} makes every cold-cache
 * PUT throw {@code NotSerializableException} — which propagates through the
 * cache interceptor and fails the whole request with HTTP 500. Live proof:
 * {@code ColdCacheRedisSerializationIntegrationTest} (would have caught it —
 * CI never exercises this path because the test profile forces
 * {@code spring.cache.type=simple}).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@SoftDelete(columnName = "is_deleted")
public abstract class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 200)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedBy
    @Column(name = "updated_by", length = 200)
    private String updatedBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public abstract UUID getId();

    public Long getVersion() { return version; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}