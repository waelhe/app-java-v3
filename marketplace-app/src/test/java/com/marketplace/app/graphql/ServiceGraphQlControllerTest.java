package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogService;
import com.marketplace.catalog.ProviderListing;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;

import org.instancio.Instancio;
import java.util.List;
import java.util.UUID;

import static org.instancio.Select.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void shouldReturnServiceById() {
        UUID id = Instancio.create(UUID.class);
        ProviderListing listing = Instancio.of(ProviderListing.class)
                .set(field(ProviderListing::getTitle), "Test")
                .set(field(ProviderListing::getDescription), "Desc")
                .set(field(ProviderListing::getCategory), "general")
                .set(field(ProviderListing::getPriceCents), 5000L)
                .create();
        when(catalogService.getById(id)).thenReturn(listing);
        when(serviceMapper.toResponse(listing)).thenReturn(
                new ServiceResponse(id, "Test", "Desc", 50.0, "ACTIVE"));

        ServiceResponse response = controller.service(id);

        assertThat(response.name()).isEqualTo("Test");
    }

    @Test
    void shouldReturnAllServices() {
        ProviderListing l1 = Instancio.of(ProviderListing.class)
                .set(field(ProviderListing::getTitle), "Svc1")
                .set(field(ProviderListing::getDescription), "Desc1")
                .set(field(ProviderListing::getCategory), "cat1")
                .set(field(ProviderListing::getPriceCents), 1000L)
                .create();
        ProviderListing l2 = Instancio.of(ProviderListing.class)
                .set(field(ProviderListing::getTitle), "Svc2")
                .set(field(ProviderListing::getDescription), "Desc2")
                .set(field(ProviderListing::getCategory), "cat2")
                .set(field(ProviderListing::getPriceCents), 2000L)
                .create();
        when(catalogService.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(l1, l2)));
        when(serviceMapper.toResponse(l1)).thenReturn(
                new ServiceResponse(Instancio.create(UUID.class), "Svc1", "Desc1", 10.0, "ACTIVE"));
        when(serviceMapper.toResponse(l2)).thenReturn(
                new ServiceResponse(Instancio.create(UUID.class), "Svc2", "Desc2", 20.0, "ACTIVE"));

        List<ServiceResponse> services = controller.services();

        assertThat(services).hasSize(2);
    }

    @Test
    void shouldCreateServiceWhenAdmin() {
        UUID providerId = Instancio.create(UUID.class);
        var auth = new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN");
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        ProviderListing listing = Instancio.of(ProviderListing.class)
                .set(field(ProviderListing::getTitle), "New Svc")
                .set(field(ProviderListing::getDescription), "Desc")
                .set(field(ProviderListing::getCategory), "cat")
                .set(field(ProviderListing::getPriceCents), 3000L)
                .create();
        when(catalogService.create(eq(providerId), eq("New Svc"), eq("Desc"), eq("cat"), eq(3000L)))
                .thenReturn(listing);
        when(serviceMapper.toResponse(listing)).thenReturn(
                new ServiceResponse(Instancio.create(UUID.class), "New Svc", "Desc", 30.0, "ACTIVE"));

        var input = new ServiceInput("New Svc", "Desc", "cat", 3000L);
        ServiceResponse response = controller.createService(input, auth);

        assertThat(response.name()).isEqualTo("New Svc");
    }
}
