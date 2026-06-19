package com.marketplace.shared.storage;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA entity for stored files.
 *
 * <p>Extends {@link BaseEntity} for optimistic locking, auditing, and soft delete
 * (via Hibernate 7 {@code @SoftDelete} on BaseEntity).
 *
 * @see com.marketplace.shared.jpa.BaseEntity
 */
@Entity
@Table(name = "storage_files")
public class StorageFile extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(name = "stored_path", nullable = false, length = 1024)
    private String storedPath;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    protected StorageFile() {
    }

    private StorageFile(UUID id, String originalName, String storedPath, String contentType, long sizeBytes, UUID uploadedBy) {
        this.id = id;
        this.originalName = originalName;
        this.storedPath = storedPath;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
    }

    public static StorageFile create(UUID id, String originalName, String storedPath, String contentType, long sizeBytes, UUID uploadedBy) {
        return new StorageFile(id, originalName, storedPath, contentType, sizeBytes, uploadedBy);
    }

    @Override
    public UUID getId() { return id; }

    public String getOriginalName() { return originalName; }

    public String getStoredPath() { return storedPath; }

    public String getContentType() { return contentType; }

    public long getSizeBytes() { return sizeBytes; }

    public UUID getUploadedBy() { return uploadedBy; }
}
