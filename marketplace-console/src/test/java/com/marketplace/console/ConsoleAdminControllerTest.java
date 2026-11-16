package com.marketplace.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * B-15 (compliance plan C.5 — «فحص لوحة»): the console check — the
 * tri-partite view's structure (the three sections, every listed path a
 * surface the code serves today), plus the REST statuses on the
 * operator's writes and the two reads.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleAdminControllerTest {

    @Mock
    private ConsoleService consoleService;

    @InjectMocks
    private ConsoleAdminController controller;

    @Test
    void theViewCarriesTheTriPartiteStructure() {
        ResponseEntity<ConsoleView> result = controller.view();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<ConsoleView.Section> sections = result.getBody().sections();
        assertThat(sections).extracting(ConsoleView.Section::title)
                .containsExactly("التشغيل", "المحتوى", "النظام");
        // Every surface path is absolute under the API root (the verified
        // controller mappings — the honest catalog).
        sections.forEach(section -> section.surfaces().forEach(surface ->
                assertThat(surface.path()).startsWith("/api/v1/")));
        // The system section carries the console's own four surfaces.
        ConsoleView.Section system = sections.get(2);
        assertThat(system.surfaces()).extracting(ConsoleView.Surface::path)
                .contains("/api/v1/admin/console/flags", "/api/v1/admin/console/config",
                        "/api/v1/admin/console/metrics", "/api/v1/admin/console/audit");
    }

    @Test
    void flagRegistrationAnswers201() {
        var request = new ConsoleAdminController.FlagRequest("some.flag", "d", true);
        when(consoleService.registerFlag("some.flag", "d", true))
                .thenReturn(FeatureFlag.register("some.flag", "d", true));

        ResponseEntity<ConsoleAdminController.FeatureFlagResponse> result =
                controller.registerFlag(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().key()).isEqualTo("some.flag");
        assertThat(result.getBody().enabled()).isTrue();
    }

    @Test
    void theFlipAnswers200() {
        FeatureFlag flag = FeatureFlag.register("some.flag", null, false);
        flag.setEnabled(true);
        when(consoleService.setFlag("some.flag", true)).thenReturn(flag);

        ResponseEntity<ConsoleAdminController.FeatureFlagResponse> result =
                controller.flip("some.flag", new ConsoleAdminController.FlagFlipRequest(true));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().enabled()).isTrue();
    }

    @Test
    void theFlagsBoardAnswers200() {
        when(consoleService.flags()).thenReturn(List.of(
                FeatureFlag.register("a.flag", null, true),
                FeatureFlag.register("b.flag", null, false)));

        ResponseEntity<List<ConsoleAdminController.FeatureFlagResponse>> result = controller.flags();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(2);
    }

    @Test
    void configRegistrationAnswers201() {
        var request = new ConsoleAdminController.ConfigRequest("some.key", "2000", "d");
        when(consoleService.registerConfig("some.key", "2000", "d"))
                .thenReturn(RemoteConfigValue.register("some.key", "2000", "d"));

        ResponseEntity<ConsoleAdminController.RemoteConfigResponse> result =
                controller.registerConfig(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().value()).isEqualTo("2000");
    }

    @Test
    void theConfigRevisionAnswers200() {
        when(consoleService.reviseConfig("some.key", "3000", "d2"))
                .thenReturn(RemoteConfigValue.register("some.key", "3000", "d2"));

        ResponseEntity<ConsoleAdminController.RemoteConfigResponse> result =
                controller.revise("some.key", new ConsoleAdminController.ConfigRequest("some.key", "3000", "d2"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().value()).isEqualTo("3000");
    }

    @Test
    void theMetricsReadAnswers200() {
        when(consoleService.metrics()).thenReturn(List.of(new ConsoleService.MeterSummary(
                "job.create", "COUNTER",
                List.of(new ConsoleService.MeasurementSummary("COUNT", 3.0)))));

        ResponseEntity<List<ConsoleService.MeterSummary>> result = controller.metrics();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).name()).isEqualTo("job.create");
    }

    @Test
    void theAuditReadAnswers200() {
        when(consoleService.changeHistory()).thenReturn(List.of());

        ResponseEntity<List<ConsoleService.ChangeHistoryEntry>> result = controller.audit();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------------------------------------
    // B-18 (compliance plan C.10 — the geographic settings surfaces)
    // -------------------------------------------------------------------------

    @Test
    void geographicRegistrationAnswers201() {
        var request = new ConsoleAdminController.GeographicSettingRequest(
                "community.polls.enabled", java.util.UUID.randomUUID(), true);
        when(consoleService.registerGeographicSetting(request.key(), request.locationId(), request.enabled()))
                .thenReturn(GeographicFeatureSetting.register(request.key(), request.locationId(), true));

        ResponseEntity<ConsoleAdminController.GeographicSettingResponse> result =
                controller.registerGeographicSetting(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().key()).isEqualTo("community.polls.enabled");
        assertThat(result.getBody().enabled()).isTrue();
        assertThat(result.getBody().locationId()).isEqualTo(request.locationId());
    }

    @Test
    void theGeographicFlipAnswers200() {
        java.util.UUID locationId = java.util.UUID.randomUUID();
        GeographicFeatureSetting setting = GeographicFeatureSetting.register("some.key", locationId, false);
        setting.setEnabled(true);
        when(consoleService.setGeographicSetting("some.key", locationId, true)).thenReturn(setting);

        ResponseEntity<ConsoleAdminController.GeographicSettingResponse> result =
                controller.flipGeographicSetting("some.key", locationId,
                        new ConsoleAdminController.FlagFlipRequest(true));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().enabled()).isTrue();
        assertThat(result.getBody().locationId()).isEqualTo(locationId);
    }

    @Test
    void theGeographicBoardAnswers200() {
        when(consoleService.geographicSettings()).thenReturn(List.of(
                GeographicFeatureSetting.register("a.key", java.util.UUID.randomUUID(), true)));

        ResponseEntity<List<ConsoleAdminController.GeographicSettingResponse>> result =
                controller.geographicSettings();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).key()).isEqualTo("a.key");
    }

    @Test
    void theEffectiveGateReadAnswers200WithTheResolvedValue() {
        java.util.UUID locationId = java.util.UUID.randomUUID();
        when(consoleService.isEnabled("some.key", locationId)).thenReturn(true);

        ResponseEntity<ConsoleAdminController.EffectiveGateResponse> result =
                controller.effectiveGate("some.key", locationId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().key()).isEqualTo("some.key");
        assertThat(result.getBody().locationId()).isEqualTo(locationId);
        assertThat(result.getBody().enabled()).isTrue();
    }

    /**
     * The honest catalog (the B-15 discipline — new surfaces join the
     * view in the same unit that lands them): the geographic settings'
     * two surfaces now carry in the النظام section.
     */
    @Test
    void theViewCarriesTheGeographicSurfaces() {
        ResponseEntity<ConsoleView> result = controller.view();

        ConsoleView.Section system = result.getBody().sections().get(2);
        assertThat(system.surfaces()).extracting(ConsoleView.Surface::path)
                .contains("/api/v1/admin/console/geo-settings",
                        "/api/v1/admin/console/geo-settings/effective");
    }
}
