package com.marketplace.provider;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

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

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ProviderPublicPageService providerPublicPageService;

    @InjectMocks
    private ProviderController controller;

    @Test
    void create_returnsProvider() {
        var request = new ProviderRequest("John", "Bio", null, null, null);
        Authentication authentication = mock(Authentication.class);
        UUID userId = UUID.randomUUID();
        ProviderProfile profile = ProviderProfile.create("John", "Bio", userId);
        ProviderResponse response = new ProviderResponse(UUID.randomUUID(), "John", "Bio",
                ProviderStatus.PENDING, ProviderActorType.INDIVIDUAL, null, null, null, null, null);

        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(providerService.create("John", "Bio", userId, null, null, null)).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.create(request, authentication);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("John", result.getBody().displayName());
    }

    @Test
    void create_passesPersonaFields() {
        var request = new ProviderRequest("John", "Bio", ProviderActorType.AGENCY, "Qudsia Prime", "BR-1");
        Authentication authentication = mock(Authentication.class);
        UUID userId = UUID.randomUUID();
        ProviderProfile profile = ProviderProfile.create("John", "Bio", userId,
                ProviderActorType.AGENCY, "Qudsia Prime", "BR-1");
        ProviderResponse response = new ProviderResponse(UUID.randomUUID(), "John", "Bio",
                ProviderStatus.PENDING, ProviderActorType.AGENCY, "Qudsia Prime", "BR-1", null, null, null);

        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(providerService.create("John", "Bio", userId, ProviderActorType.AGENCY, "Qudsia Prime", "BR-1"))
                .thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.create(request, authentication);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(ProviderActorType.AGENCY, result.getBody().actorType());
        assertEquals("Qudsia Prime", result.getBody().agencyName());
        assertEquals("BR-1", result.getBody().licenseNumber());
    }

    @Test
    void getById_returnsProvider() {
        UUID id = UUID.randomUUID();
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID());
        ProviderResponse response = new ProviderResponse(id, "John", "Bio",
                ProviderStatus.PENDING, ProviderActorType.INDIVIDUAL, null, null, null, null, null);

        when(providerService.getById(id)).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.getById(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(id, result.getBody().id());
    }

    @Test
    void update_returnsUpdated() {
        UUID id = UUID.randomUUID();
        var request = new ProviderRequest("Jane", "Updated", ProviderActorType.INDEPENDENT_BROKER, null, null);
        Authentication authentication = mock(Authentication.class);
        ProviderProfile profile = ProviderProfile.create("Jane", "Updated", UUID.randomUUID());
        ProviderResponse response = new ProviderResponse(id, "Jane", "Updated",
                ProviderStatus.PENDING, ProviderActorType.INDEPENDENT_BROKER, null, null, null, null, null);

        when(providerService.update(eq(id), eq("Jane"), eq("Updated"), eq(ProviderActorType.INDEPENDENT_BROKER),
                isNull(), isNull(), any(Authentication.class))).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.update(id, request, authentication);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("Jane", result.getBody().displayName());
    }

    @Test
    void getPublicPage_delegatesToPublicPageService() {
        UUID id = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        var page = new ProviderPublicPageResponse(id, "John", "Bio", ProviderStatus.VERIFIED,
                ProviderActorType.INDEPENDENT_BROKER, "Qudsia Prime", "BR-1", null, 4.5, 12L, null);

        when(providerPublicPageService.getPublicPage(id, pageable)).thenReturn(page);

        ResponseEntity<ProviderPublicPageResponse> result = controller.getPublicPage(id, pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(id, result.getBody().id());
        assertEquals(ProviderStatus.VERIFIED, result.getBody().status());
        assertEquals(4.5, result.getBody().ratingAverage());
        assertEquals(12L, result.getBody().reviewCount());
    }

    @Test
    void verify_returnsVerified() {
        UUID id = UUID.randomUUID();
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID());
        profile.verify();
        ProviderResponse response = new ProviderResponse(id, "John", "Bio",
                ProviderStatus.VERIFIED, ProviderActorType.INDIVIDUAL, null, null, null, null, null);

        when(providerService.verify(id)).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.verify(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(ProviderStatus.VERIFIED, result.getBody().status());
    }

    @Test
    void suspend_returnsSuspended() {
        UUID id = UUID.randomUUID();
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID());
        profile.suspend();
        ProviderResponse response = new ProviderResponse(id, "John", "Bio",
                ProviderStatus.SUSPENDED, ProviderActorType.INDIVIDUAL, null, null, null, null, null);

        when(providerService.suspend(id)).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        ResponseEntity<ProviderResponse> result = controller.suspend(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(ProviderStatus.SUSPENDED, result.getBody().status());
    }
}
