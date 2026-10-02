package com.marketplace.admin;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.SystemSettingChangedEvent;
import com.marketplace.shared.api.SystemSettingKeys;
import com.marketplace.shared.api.SystemSettingTypeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W0: the setting adapter's own contract — refusal shapes, the typed read port,
 * and the «nothing changed = nothing happens» rule that keeps the audit trail
 * and the cache honest ({@link SystemSetting#replaceValue} decides).
 */
class SystemSettingsServiceTest {

    private static final String KEY = "reviews.mode";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SystemSettingRepository repository;
    private SystemSettingReader reader;
    private org.springframework.context.ApplicationEventPublisher events;
    private SystemSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(SystemSettingRepository.class);
        reader = mock(SystemSettingReader.class);
        events = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new SystemSettingsService(repository, reader, events);
        when(repository.saveAndFlush(any(SystemSetting.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static JsonNode json(String raw) {
        return JSON.readTree(raw);
    }

    private static SystemSetting stored(String rawJson) {
        return SystemSetting.create(UUID.randomUUID(), KEY, json(rawJson), "gate");
    }

    // -- The read port --------------------------------------------------------

    @Test
    void getString_readsTheStoredJsonString() {
        when(reader.find(KEY)).thenReturn(Optional.of(stored("\"VERIFIED_ONLY\"")));

        assertThat(service.getString(KEY)).contains("VERIFIED_ONLY");
        assertThat(service.getStringOrDefault(KEY, "HYBRID")).isEqualTo("VERIFIED_ONLY");
    }

    @Test
    void getIntAndGetBoolean_readTheirNativeJsonTypes() {
        when(reader.find("reviews.mode")).thenReturn(Optional.of(stored("25")));

        assertThat(service.getInt("reviews.mode")).hasValue(25);

        when(reader.find("reviews.mode")).thenReturn(Optional.of(stored("true")));
        assertThat(service.getBoolean("reviews.mode")).contains(true);
    }

    @Test
    void anAbsentSettingIsEmptyAndYieldsToTheCallersOwnDefault() {
        when(reader.find(KEY)).thenReturn(Optional.empty());

        assertThat(service.getString(KEY)).isEmpty();
        assertThat(service.getStringOrDefault(KEY, "VERIFIED_ONLY")).isEqualTo("VERIFIED_ONLY");
        assertThat(service.getInt(KEY)).isEmpty();
        assertThat(service.getBoolean(KEY)).isEmpty();
    }

    @Test
    void aValueOfTheWrongJsonTypeIsLoudRatherThanDefaulted() {
        when(reader.find(KEY)).thenReturn(Optional.of(stored("25")));

        assertThatThrownBy(() -> service.getString(KEY))
                .isInstanceOf(SystemSettingTypeException.class)
                .hasMessageContaining("must hold a JSON string");
    }

    // -- The administrative write path ---------------------------------------

    @Test
    void create_refusesToOverwriteAnExistingKey() {
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(stored("\"VERIFIED_ONLY\"")));

        assertThatThrownBy(() -> service.create(KEY, json("\"OPEN\""), "gate", "admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
        verify(repository, never()).saveAndFlush(any(SystemSetting.class));
        verifyNoInteractions(events);
    }

    @Test
    void create_evictsTheKeyItJustIntroduced() {
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.empty());

        SystemSetting created = service.create(KEY, json("\"VERIFIED_ONLY\""), "gate", "admin");

        assertThat(created.getRawJson()).isEqualTo("\"VERIFIED_ONLY\"");
        ArgumentCaptor<CacheInvalidationRequested> eviction =
                ArgumentCaptor.forClass(CacheInvalidationRequested.class);
        verify(events).publishEvent(eviction.capture());
        assertThat(eviction.getValue().cacheNames()).containsExactly(SystemSettingReader.CACHE_NAME);
        assertThat(eviction.getValue().key()).isEqualTo(KEY);
    }

    @Test
    void update_missingKey_throwsNotFound() {
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(KEY, json("\"OPEN\""), null, "admin"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_withTheSameValueIsANoOpAllTheWayDown() {
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(stored("\"VERIFIED_ONLY\"")));

        service.update(KEY, json("\"VERIFIED_ONLY\""), null, "admin");

        verify(repository, never()).saveAndFlush(any(SystemSetting.class));
        verifyNoInteractions(events);
    }

    @Test
    void update_publishesTheEvictionAndTheChangeEvent() {
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(stored("\"VERIFIED_ONLY\"")));

        service.update(KEY, json("\"OPEN\""), null, "admin");

        ArgumentCaptor<CacheInvalidationRequested> eviction =
                ArgumentCaptor.forClass(CacheInvalidationRequested.class);
        verify(events).publishEvent(eviction.capture());
        assertThat(eviction.getValue().key()).isEqualTo(KEY);

        ArgumentCaptor<SystemSettingChangedEvent> change =
                ArgumentCaptor.forClass(SystemSettingChangedEvent.class);
        verify(events).publishEvent(change.capture());
        assertThat(change.getValue().key()).isEqualTo(KEY);
        assertThat(change.getValue().oldValue()).isEqualTo("VERIFIED_ONLY");
        assertThat(change.getValue().newValue()).isEqualTo("OPEN");
        assertThat(change.getValue().actor()).isEqualTo("admin");
    }

    @Test
    void update_keepsTheValueInItsNativeJsonType() {
        String probe = "telemetry.sample" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        when(repository.findBySettingKey(probe)).thenReturn(Optional.of(stored("\"VERIFIED_ONLY\"")));

        SystemSetting updated = service.update(probe, json("5"), "cap", "admin");

        assertThat(updated.getRawJson()).isEqualTo("5");
        assertThat(updated.getDescription()).isEqualTo("cap");
        assertThat(updated.intValue()).isEqualTo(5);
    }

    // -- W1: the write-time vocabulary gate (§4.1/§4.5) --------------------

    @Test
    void update_modeWithAnUnknownValueIsRefused_loudlyAndWithoutAWrite() {
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(stored("\"VERIFIED_ONLY\"")));

        assertThatThrownBy(() -> service.update(KEY, json("\"EVERYTHING\""), null, "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("EVERYTHING");
        verify(repository, never()).saveAndFlush(any(SystemSetting.class));
        verifyNoInteractions(events);
    }

    @Test
    void update_modeWithANonStringIsRefused() {
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(stored("\"VERIFIED_ONLY\"")));

        assertThatThrownBy(() -> service.update(KEY, json("5"), null, "admin"))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).saveAndFlush(any(SystemSetting.class));
    }

    @Test
    void update_unknownKeysStayOpen() {
        String probe = "telemetry.sample" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        when(repository.findBySettingKey(probe)).thenReturn(Optional.of(stored("\"whatever\"")));

        service.update(probe, json("\"still-whatever\""), null, "admin");

        verify(repository).saveAndFlush(any(SystemSetting.class));
    }

    @Test
    void create_dailyCapWithANonPositiveIntegerIsRefused() {
        String cap = SystemSettingKeys.REVIEWS_ORGANIC_DAILY_CAP;
        when(repository.findBySettingKey(cap)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(cap, json("0"), "cap", "admin"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(cap, json("\"5\""), "cap", "admin"))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).saveAndFlush(any(SystemSetting.class));
    }

    @Test
    void create_dailyCapWithAPositiveIntegerIsAccepted() {
        String cap = SystemSettingKeys.REVIEWS_ORGANIC_DAILY_CAP;
        when(repository.findBySettingKey(cap)).thenReturn(Optional.empty());

        SystemSetting created = service.create(cap, json("5"), "cap", "admin");

        assertThat(created.getRawJson()).isEqualTo("5");
        verify(repository).saveAndFlush(any(SystemSetting.class));
    }
}
