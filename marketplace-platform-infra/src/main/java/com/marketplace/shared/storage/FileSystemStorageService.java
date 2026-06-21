package com.marketplace.shared.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Filesystem-based implementation of {@link StorageService}.
 *
 * <p>Stores files on the local filesystem under the configured upload directory.
 * The upload directory is created on startup if it does not exist.
 *
 * <p>Reference: Spring Boot How-to -- Handling Multipart File Uploads:
 * "When you want to receive multipart encoded file data as a @RequestParam-annotated
 * parameter of type MultipartFile in a Spring MVC controller handler method."
 * https://docs.spring.io/spring-boot/how-to/spring-mvc.html
 */
@Service
public class FileSystemStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(FileSystemStorageService.class);

    private final Path rootLocation;
    private final StorageFileRepository repository;

    public FileSystemStorageService(StorageProperties properties, StorageFileRepository repository) {
        this.rootLocation = Paths.get(properties.uploadDir());
        this.repository = repository;
        init();
    }

    private void init() {
        try {
            Files.createDirectories(rootLocation);
            log.info("Storage upload directory initialized: {}", rootLocation.toAbsolutePath());
        } catch (IOException e) {
            throw new StorageException("Could not initialize storage directory: " + rootLocation, e);
        }
    }

    @Override
    public StoredFile store(MultipartFile file, UUID userId) {
        if (file.isEmpty()) {
            throw new StorageException("Failed to store empty file");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new StorageException("Failed to store file with no original filename");
        }

        UUID fileId = UUID.randomUUID();
        String storedPath = generateStoredPath(fileId, originalName);
        Path destinationFile = rootLocation.resolve(storedPath).normalize().toAbsolutePath();

        // Security: prevent path traversal -- the resolved path must be within rootLocation
        if (!destinationFile.startsWith(rootLocation.toAbsolutePath())) {
            throw new StorageException("Cannot store file outside storage directory");
        }

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("Failed to store file: " + originalName, e);
        }

        StorageFile entity = StorageFile.create(
                fileId,
                originalName,
                storedPath,
                file.getContentType(),
                file.getSize(),
                userId
        );
        repository.save(entity);

        log.info("File stored: id={}, name={}, size={}, user={}", fileId, originalName, file.getSize(), userId);

        return new StoredFile(fileId, originalName, storedPath, file.getContentType(), file.getSize(), userId);
    }

    @Override
    public Resource load(String storedPath) {
        try {
            Path file = rootLocation.resolve(storedPath).normalize().toAbsolutePath();
            if (!file.startsWith(rootLocation.toAbsolutePath())) {
                throw new StorageException("Cannot load file outside storage directory");
            }
            Resource resource = new UrlResource(file.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new StorageException("File not found or not readable: " + storedPath);
            }
            return resource;
        } catch (MalformedURLException e) {
            throw new StorageException("Could not load file: " + storedPath, e);
        }
    }

    @Override
    public void delete(String storedPath) {
        try {
            Path file = rootLocation.resolve(storedPath).normalize().toAbsolutePath();
            if (!file.startsWith(rootLocation.toAbsolutePath())) {
                throw new StorageException("Cannot delete file outside storage directory");
            }
            Files.deleteIfExists(file);
            log.info("File deleted from filesystem: {}", storedPath);
        } catch (IOException e) {
            throw new StorageException("Could not delete file: " + storedPath, e);
        }
    }

    @Override
    public StoredFile getFile(UUID id) {
        return repository.findById(id)
                .map(entity -> new StoredFile(
                        entity.getId(),
                        entity.getOriginalName(),
                        entity.getStoredPath(),
                        entity.getContentType(),
                        entity.getSizeBytes(),
                        entity.getUploadedBy()
                ))
                .orElse(null);
    }

    /**
     * Generates a unique stored path for a file to avoid filename collisions.
     * Uses the file ID as a subdirectory prefix (first 2 chars) + UUID + original extension.
     */
    private String generateStoredPath(UUID fileId, String originalName) {
        String extension = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = originalName.substring(dotIndex);
        }
        return fileId.toString() + extension;
    }
}
