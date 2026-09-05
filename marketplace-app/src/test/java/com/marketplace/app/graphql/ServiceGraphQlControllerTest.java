package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceGraphQlControllerTest {

    @Mock
    private CatalogService catalogService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ServiceMapper serviceMapper;

    @InjectMocks
    private ServiceGraphQlController controller;

    private static ProviderListingView view(UUID id, String title, String description, Long priceCents, String status) {
        return new ProviderListingView(id, title, description, "general", priceCents, "SAR",
                UUID.randomUUID(), status, null, null);
    }

    @Test
    void shouldReturnServiceById() {
        UUID id = UUID.randomUUID();
        ProviderListingView listing = view(id, "Test", "Desc", 5000L, "ACTIVE");
        when(catalogService.getActiveById(id)).thenReturn(listing);
        when(serviceMapper.toResponse(listing)).thenReturn(
                new ServiceResponse(id, "Test", "Desc", 50.0, "SAR", "ACTIVE"));

        ServiceResponse response = controller.service(id);

        assertThat(response.name()).isEqualTo("Test");
    }

    @Test
    void shouldReturnAllServices() {
        ProviderListingView l1 = view(UUID.randomUUID(), "Svc1", "Desc1", 1000L, "ACTIVE");
        ProviderListingView l2 = view(UUID.randomUUID(), "Svc2", "Desc2", 2000L, "ACTIVE");
        when(catalogService.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(l1, l2)));
        when(serviceMapper.toResponse(l1)).thenReturn(
                new ServiceResponse(UUID.randomUUID(), "Svc1", "Desc1", 10.0, "SAR", "ACTIVE"));
        when(serviceMapper.toResponse(l2)).thenReturn(
                new ServiceResponse(UUID.randomUUID(), "Svc2", "Desc2", 20.0, "SAR", "ACTIVE"));

        List<ServiceResponse> services = controller.services();

        assertThat(services).hasSize(2);
    }

    @Test
    void shouldCreateServiceWhenAdmin() {
        UUID providerId = UUID.randomUUID();
        var auth = new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN");
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        ProviderListingView listing = view(UUID.randomUUID(), "New Svc", "Desc", 3000L, "ACTIVE");
        when(catalogService.create(eq(providerId), eq("New Svc"), eq("Desc"), eq("cat"), eq(3000L), isNull()))
                .thenReturn(listing);
        when(serviceMapper.toResponse(listing)).thenReturn(
                new ServiceResponse(UUID.randomUUID(), "New Svc", "Desc", 30.0, "SAR", "ACTIVE"));

        var input = new ServiceInput("New Svc", "Desc", "cat", 3000L);
        ServiceResponse response = controller.createService(input, auth);

        assertThat(response.name()).isEqualTo("New Svc");
    }
}