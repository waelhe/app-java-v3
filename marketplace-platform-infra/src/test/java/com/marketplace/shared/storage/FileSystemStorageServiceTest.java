package com.marketplace.shared.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class FileSystemStorageServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private StorageFileRepository repository;

    private FileSystemStorageService storageService;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties(tempDir.toString(), "10MB");
        storageService = new FileSystemStorageService(props, repository);
    }

    @Test
    void store_savesFileAndReturnsMetadata() throws IOException {
        UUID userId = UUID.randomUUID();
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("test.txt");
        when(file.getContentType()).thenReturn("text/plain");
        when(file.getSize()).thenReturn(123L);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));

        StorageService.StoredFile result = storageService.store(file, userId);

        assertNotNull(result.id());
        assertEquals("test.txt", result.originalName());
        assertEquals("text/plain", result.contentType());
        assertEquals(123L, result.sizeBytes());
        assertEquals(userId, result.uploadedBy());
        verify(repository).save(any(StorageFile.class));
    }

    @Test
    void store_throwsForEmptyFile() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        assertThrows(StorageException.class, () -> storageService.store(file, UUID.randomUUID()));
    }

    @Test
    void store_throwsForNullFilename() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(null);

        assertThrows(StorageException.class, () -> storageService.store(file, UUID.randomUUID()));
    }

    @Test
    void load_returnsResourceForExistingFile() throws IOException {
        UUID userId = UUID.randomUUID();
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("test.txt");
        when(file.getContentType()).thenReturn("text/plain");
        when(file.getSize()).thenReturn(5L);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));

        StorageService.StoredFile stored = storageService.store(file, userId);

        Resource resource = storageService.load(stored.storedPath());
        assertTrue(resource.exists());
        assertTrue(resource.isReadable());
    }

    @Test
    void load_throwsForNonExistentFile() {
        assertThrows(StorageException.class, () -> storageService.load("nonexistent.txt"));
    }

    @Test
    void delete_removesFileFromFilesystem() throws IOException {
        UUID userId = UUID.randomUUID();
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("delete-me.txt");
        when(file.getContentType()).thenReturn("text/plain");
        when(file.getSize()).thenReturn(5L);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));

        StorageService.StoredFile stored = storageService.store(file, userId);

        storageService.delete(stored.storedPath());

        assertThrows(StorageException.class, () -> storageService.load(stored.storedPath()));
    }

    @Test
    void getFile_returnsMetadataFromRepository() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        StorageFile entity = StorageFile.create(fileId, "test.txt", "path", "text/plain", 123L, userId);
        when(repository.findById(fileId)).thenReturn(Optional.of(entity));

        StorageService.StoredFile result = storageService.getFile(fileId);

        assertNotNull(result);
        assertEquals(fileId, result.id());
        assertEquals("test.txt", result.originalName());
        assertEquals("text/plain", result.contentType());
        assertEquals(123L, result.sizeBytes());
        assertEquals(userId, result.uploadedBy());
    }

    @Test
    void getFile_returnsNullForNonExistentId() {
        UUID fileId = UUID.randomUUID();
        when(repository.findById(fileId)).thenReturn(Optional.empty());

        StorageService.StoredFile result = storageService.getFile(fileId);

        assertNull(result);
    }
}
