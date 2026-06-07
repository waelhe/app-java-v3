package com.marketplace.provider.spi;

import com.marketplace.provider.ProviderProfile;
import com.marketplace.provider.ProviderRepository;
import com.marketplace.shared.api.ProviderSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderLookupAdapterTest {

    @Mock
    private ProviderRepository repository;

    @InjectMocks
    private ProviderLookupAdapter adapter;

    @Test
    void findById_returnsSummary() {
        UUID userId = UUID.randomUUID();
        ProviderProfile profile = ProviderProfile.create("John", "Bio", userId);
        UUID id = profile.getId();
        when(repository.findById(id)).thenReturn(Optional.of(profile));

        Optional<ProviderSummary> result = adapter.findById(id);

        assertTrue(result.isPresent());
        assertEquals(id, result.get().id());
        assertEquals("John", result.get().displayName());
        assertEquals("PENDING", result.get().status());
        assertEquals(userId, result.get().userId());
    }

    @Test
    void findById_returnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        Optional<ProviderSummary> result = adapter.findById(id);

        assertTrue(result.isEmpty());
    }
}
