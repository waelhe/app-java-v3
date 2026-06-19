package com.marketplace.shared.storage;

/**
 * Service interface for file storage operations.
 *
 * <p>Provides upload, download, and delete operations for user-uploaded files.
 * The implementation ({@link FileSystemStorageService}) uses the local filesystem,
 * but the interface allows swapping to S3, Azure Blob, etc. without changing callers.
 *
 * @see <a href="https://docs.spring.io/spring-boot/how-to/spring-mvc.html">Spring Boot -- Handling Multipart File Uploads</a>
 */
public interface StorageService {

    /**
     * Stores a multipart file and returns the stored file metadata.
     *
     * @param file the multipart file to store
     * @param userId the ID of the uploading user
     * @return metadata about the stored file
     */
    StoredFile store(org.springframework.web.multipart.MultipartFile file, java.util.UUID userId);

    /**
     * Loads a stored file as a Spring Resource for download.
     *
     * @param storedPath the path where the file was stored
     * @return the file as a Resource
     */
    org.springframework.core.io.Resource load(String storedPath);

    /**
     * Deletes a stored file from the filesystem.
     *
     * @param storedPath the path where the file was stored
     */
    void delete(String storedPath);

    /**
     * Retrieves metadata about a stored file by its ID.
     *
     * @param id the file ID
     * @return the stored file metadata, or null if not found
     */
    StoredFile getFile(java.util.UUID id);

    /**
     * Metadata about a stored file.
     *
     * @param id the file ID
     * @param originalName the original filename from the upload
     * @param storedPath the path where the file is stored on the filesystem
     * @param contentType the MIME content type
     * @param sizeBytes the file size in bytes
     * @param uploadedBy the ID of the user who uploaded the file
     */
    record StoredFile(
        java.util.UUID id,
        String originalName,
        String storedPath,
        String contentType,
        long sizeBytes,
        java.util.UUID uploadedBy
    ) {
    }
}
