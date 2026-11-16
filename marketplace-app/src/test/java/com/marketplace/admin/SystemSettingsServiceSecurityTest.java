package com.marketplace.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-07 (official-compliance plan §6 wave A — A.5): the method-security slice
 * of the platform-settings write surface. The service's own javadoc claimed
 * «three guard layers» while the third (the service-level role gate) did not
 * exist — an architecture lie in the N2 sense: the claim was about
 * write-path centralization, and a caller reaching the bean off the HTTP
 * path met no role check at all. This slice measures the now-real layer,
 * negative and positive per rule ({@code LedgerServiceSecurityTest} shape).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { SystemSettingsService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class SystemSettingsServiceSecurityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An unknown key: the write-time vocabulary gate leaves the key space open. */
    private static final String KEY = "it-a07-setting";

    private static JsonNode json(String raw) {
        return JSON.readTree(raw);
    }

    @Autowired
    private SystemSettingsService systemSettingsService;

    @MockitoBean
    private SystemSettingRepository repository;

    @MockitoBean
    private SystemSettingReader reader;

    @MockitoBean
    private ApplicationEventPublisher events;

    @Test
    @WithMockUser(roles = "CONSUMER")
    void create_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> systemSettingsService.create(KEY, json("\"on\""), "gate", "actor"));
        verifyNoInteractions(repository);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void update_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> systemSettingsService.update(KEY, json("\"on\""), "gate", "actor"));
        verifyNoInteractions(repository);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_whenAdmin_thenReachesTheDomain() {
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(SystemSetting.class)))
                .thenAnswer(call -> call.getArgument(0));

        assertThatCode(() -> systemSettingsService.create(KEY, json("\"on\""), "gate", "admin"))
                .doesNotThrowAnyException();

        verify(repository).saveAndFlush(any(SystemSetting.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_whenAdmin_thenReachesTheDomain() {
        SystemSetting stored = SystemSetting.create(UUID.randomUUID(), KEY, json("\"off\""), "gate");
        when(repository.findBySettingKey(KEY)).thenReturn(Optional.of(stored));
        when(repository.saveAndFlush(any(SystemSetting.class)))
                .thenAnswer(call -> call.getArgument(0));

        SystemSetting updated = systemSettingsService.update(KEY, json("\"on\""), "gate", "admin");

        // The domain executed past the security gate: the replace happened
        // and the row is handed to the repository for the flush. (The event
        // publisher seam is not verified here: ApplicationEventPublisher is
        // a resolvable dependency, so the slice context itself serves the
        // constructor — the mock never wins that injection.)
        assertThat(updated.getRawJson()).isEqualTo("\"on\"");
        verify(repository).saveAndFlush(any(SystemSetting.class));
    }

    /** The read port stays un-annotated by design (the disclosed reader). */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void reads_whenNotAdmin_stillServeTheDisclosedPort() {
        when(reader.find(anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> systemSettingsService.getString(KEY)).doesNotThrowAnyException();
        assertThatCode(() -> systemSettingsService.getInt(KEY)).doesNotThrowAnyException();
        assertThatCode(() -> systemSettingsService.getBoolean(KEY)).doesNotThrowAnyException();
    }
}
