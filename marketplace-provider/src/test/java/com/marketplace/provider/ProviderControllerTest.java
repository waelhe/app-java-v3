package com.marketplace.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderControllerTest {

    @Mock
    private ProviderService providerService;

    @Mock
    private ProviderMapper providerMapper;

    @InjectMocks
    private ProviderController controller;

    @Test
    void create_returnsProvider() {
        var request = new ProviderRequest("John", "Bio");
        ProviderProfile profile = ProviderProfile.create("John", "Bio");
        ProviderResponse response = new ProviderResponse(UUID.randomUUID(), "John", "Bio", ProviderStatus.PENDING, null, null);

        when(providerService.create("John", "Bio")).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.create(request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("John", result.getBody().displayName());
    }

    @Test
    void getById_returnsProvider() {
        UUID id = UUID.randomUUID();
        ProviderProfile profile = ProviderProfile.create("John", "Bio");
        ProviderResponse response = new ProviderResponse(id, "John", "Bio", ProviderStatus.PENDING, null, null);

        when(providerService.getById(id)).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.getById(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(id, result.getBody().id());
    }

    @Test
    void update_returnsUpdated() {
        UUID id = UUID.randomUUID();
        var request = new ProviderRequest("Jane", "Updated");
        ProviderProfile profile = ProviderProfile.create("Jane", "Updated");
        ProviderResponse response = new ProviderResponse(id, "Jane", "Updated", ProviderStatus.PENDING, null, null);

        when(providerService.update(id, "Jane", "Updated")).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.update(id, request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("Jane", result.getBody().displayName());
    }

    @Test
    void verify_returnsVerified() {
        UUID id = UUID.randomUUID();
        ProviderProfile profile = ProviderProfile.create("John", "Bio");
        profile.verify();
        ProviderResponse response = new ProviderResponse(id, "John", "Bio", ProviderStatus.VERIFIED, null, null);

        when(providerService.verify(id)).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.verify(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(ProviderStatus.VERIFIED, result.getBody().status());
    }

    @Test
    void suspend_returnsSuspended() {
        UUID id = UUID.randomUUID();
        ProviderProfile profile = ProviderProfile.create("John", "Bio");
        profile.suspend();
        ProviderResponse response = new ProviderResponse(id, "John", "Bio", ProviderStatus.SUSPENDED, null, null);

        when(providerService.suspend(id)).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.suspend(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(ProviderStatus.SUSPENDED, result.getBody().status());
    }
}
