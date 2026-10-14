package com.marketplace.console;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The manager console's web leg — the officially guided server-rendered
 * experience (spring.io guides: serving-web-content's controller/model/view
 * shape; handling-form-submission's post/redirect/get with the flash
 * contract). The unit pins every lever's own contract: the home view
 * carries the SAME tri-partite catalog and the SAME boards the REST
 * console serves, and EVERY write is ONE service call — the controller
 * never touches the entity (the
 * {@code controllersMustNotDependOnJpaEntities} architecture rule; the
 * entity stays inside the service layer where it belongs).
 *
 * <p>The flash contract is pinned per refusal shape: a blank or oversized
 * field, a duplicate registration (409's polite face), and an unknown key
 * (404's polite face) each answer the error banner on the redirected
 * board — never a bare redirect, never an exception page.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleWebControllerTest {

    @Mock
    private ConsoleService consoleService;

    @Mock
    private ConsolePlanningService planningService;

    @Mock
    private Model model;

    @Mock
    private RedirectAttributes redirect;

    @InjectMocks
    private ConsoleWebController controller;

    // -------------------------------------------------------------------------
    // the home board
    // -------------------------------------------------------------------------

    @Test
    void theHomeViewIsTheConsoleTemplateCarryingTheCatalogAndBoards() {
        when(consoleService.flags()).thenReturn(List.of());
        when(consoleService.configs()).thenReturn(List.of());
        when(consoleService.geographicSettings()).thenReturn(List.of());
        when(consoleService.metrics()).thenReturn(List.of());
        when(consoleService.changeHistory()).thenReturn(List.of());

        String view = controller.console(model);

        assertThat(view).isEqualTo("console/console");
        // the model contract: the catalog + the five boards
        verify(model).addAttribute("sections", ConsoleView.of().sections());
        verify(model).addAttribute("flags", List.of());
        verify(model).addAttribute("configs", List.of());
        verify(model).addAttribute("geoSettings", List.of());
        verify(model).addAttribute("meters", List.of());
        verify(model).addAttribute("changes", List.of());
    }

    @Test
    void theFlipIsOneServiceCallAndRedirects() {
        when(consoleService.flipFlag("some.flag")).thenReturn(java.util.Optional.empty());

        RedirectView view = controller.flipFlag("some.flag", redirect);

        // post/redirect/get — the guide's own pattern (a refresh never re-submits)
        assertThat(view.getUrl()).isEqualTo("/console");
        // the whole flip is the service's own method — the row's state
        // inverted inside the service, never in the controller
        verify(consoleService).flipFlag("some.flag");
        // an absent row is the service's own no-op — the SILENT redirect
        // stands (a forged or stale form gets no banner)
        verify(redirect, never()).addFlashAttribute(any(), any());
    }

    @Test
    void theLandedFlipAnswersTheConfirmationBanner() {
        when(consoleService.flipFlag("some.flag"))
                .thenReturn(java.util.Optional.of(Boolean.FALSE));

        RedirectView view = controller.flipFlag("some.flag", redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        // a flip that LANDS answers the flash contract's Arabic banner —
        // the operator sees the new state, not a silent redirect
        verify(redirect).addFlashAttribute(eq(ConsoleWebController.FLASH_SUCCESS),
                eq("أُطفئ العلم some.flag"));
    }

    // -------------------------------------------------------------------------
    // the flag registration lever
    // -------------------------------------------------------------------------

    @Test
    void theFlagRegistrationIsOneServiceCallWithAConfirmationBanner() {
        RedirectView view = controller.registerFlag(" community.events.enabled ", "وصف", true, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(consoleService).registerFlag("community.events.enabled", "وصف", true);
        verify(redirect).addFlashAttribute(ConsoleWebController.FLASH_SUCCESS,
                "سُجّل العلم community.events.enabled");
    }

    @Test
    void aBlankFlagKeyIsRefusedByTheBannerAndNeverReachesTheService() {
        RedirectView view = controller.registerFlag("   ", "وصف", true, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(redirect).addFlashAttribute(eq(ConsoleWebController.FLASH_ERROR), anyString());
        verifyNoInteractions(consoleService);
    }

    @Test
    void aDuplicateFlagKeyAnswersTheBanner() {
        when(consoleService.registerFlag(anyString(), any(), anyBoolean()))
                .thenThrow(new ConflictException("already registered"));

        RedirectView view = controller.registerFlag("dup.key", null, false, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(redirect).addFlashAttribute(eq(ConsoleWebController.FLASH_ERROR), anyString());
    }

    // -------------------------------------------------------------------------
    // the remote-config levers
    // -------------------------------------------------------------------------

    @Test
    void theConfigRegistrationIsOneServiceCallWithAConfirmationBanner() {
        RedirectView view = controller.registerConfig(" community.posts.max-length ", " 2000 ", "وصف", redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(consoleService).registerConfig("community.posts.max-length", "2000", "وصف");
        verify(redirect).addFlashAttribute(ConsoleWebController.FLASH_SUCCESS,
                "سُجّل الإعداد البعيد community.posts.max-length");
    }

    @Test
    void aBlankConfigValueIsRefusedByTheBannerAndNeverReachesTheService() {
        RedirectView view = controller.registerConfig("some.key", "  ", null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(redirect).addFlashAttribute(eq(ConsoleWebController.FLASH_ERROR), anyString());
        verifyNoInteractions(consoleService);
    }

    @Test
    void theConfigRevisionIsOneServiceCallWithAConfirmationBanner() {
        RedirectView view = controller.reviseConfig("some.key", " 42 ", "وصف جديد", redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(consoleService).reviseConfig("some.key", "42", "وصف جديد");
        verify(redirect).addFlashAttribute(ConsoleWebController.FLASH_SUCCESS, "عُدّل الإعداد البعيد some.key");
    }

    @Test
    void theConfigRevisionOfAnUnknownKeyAnswersTheBanner() {
        when(consoleService.reviseConfig(anyString(), anyString(), any()))
                .thenThrow(new ResourceNotFoundException("Remote config not found"));

        RedirectView view = controller.reviseConfig("ghost.key", "42", null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(redirect).addFlashAttribute(eq(ConsoleWebController.FLASH_ERROR), anyString());
    }

    // -------------------------------------------------------------------------
    // the geographic-setting levers
    // -------------------------------------------------------------------------

    @Test
    void theGeographicRegistrationIsOneServiceCallWithAConfirmationBanner() {
        UUID location = UUID.randomUUID();

        RedirectView view = controller.registerGeographicSetting("community.events.enabled",
                location.toString(), true, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(consoleService).registerGeographicSetting("community.events.enabled", location, true);
        verify(redirect).addFlashAttribute(ConsoleWebController.FLASH_SUCCESS,
                "سُجّل الإعداد الجغرافي community.events.enabled");
    }

    @Test
    void anUnparseableLocationIdIsRefusedByTheBannerAndNeverReachesTheService() {
        RedirectView view = controller.registerGeographicSetting("some.key", "not-a-uuid", true, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(redirect).addFlashAttribute(eq(ConsoleWebController.FLASH_ERROR), anyString());
        verifyNoInteractions(consoleService);
    }

    @Test
    void anUnknownLocationAnswersTheBannerBeforeAnyWrite() {
        when(consoleService.registerGeographicSetting(anyString(), any(), anyBoolean()))
                .thenThrow(new ResourceNotFoundException("no location"));

        RedirectView view = controller.registerGeographicSetting("some.key",
                UUID.randomUUID().toString(), false, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(redirect).addFlashAttribute(eq(ConsoleWebController.FLASH_ERROR), anyString());
    }

    @Test
    void theGeographicFlipIsOneServiceCallAndRedirects() {
        UUID location = UUID.randomUUID();
        when(consoleService.flipGeographicSetting("some.key", location))
                .thenReturn(java.util.Optional.empty());

        RedirectView view = controller.flipGeographicSetting("some.key", location, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(consoleService).flipGeographicSetting("some.key", location);
        // an absent (key, location) pair is the service's own no-op — the
        // SILENT redirect stands
        verify(redirect, never()).addFlashAttribute(any(), any());
    }

    @Test
    void theLandedGeographicFlipAnswersTheConfirmationBanner() {
        UUID location = UUID.randomUUID();
        when(consoleService.flipGeographicSetting("some.key", location))
                .thenReturn(java.util.Optional.of(Boolean.FALSE));

        RedirectView view = controller.flipGeographicSetting("some.key", location, redirect);

        assertThat(view.getUrl()).isEqualTo("/console");
        verify(redirect).addFlashAttribute(eq(ConsoleWebController.FLASH_SUCCESS),
                eq("أُطفئ الإعداد الجغرافي some.key في " + location));
    }

    // -------------------------------------------------------------------------
    // the planning board
    // -------------------------------------------------------------------------

    @Test
    void thePlanningBoardCarriesTheCurrentRevisionAndTheTrail() {
        ConsolePlanningRevision current = ConsolePlanningRevision.append(3, "{\"sections\":[]}", "current");
        ConsolePlanningRevision older = ConsolePlanningRevision.append(1, "{}", "first");
        when(planningService.currentIfPresent()).thenReturn(Optional.of(current));
        when(planningService.revisions()).thenReturn(List.of(current, older));

        String view = controller.planning(model);

        assertThat(view).isEqualTo("console/planning");
        verify(model).addAttribute(eq("current"), any());
        verify(model).addAttribute(eq("revisions"), any());
    }

    @Test
    void thePlanningBoardRendersItsHonestEmptyStateBeforeTheFirstPublish() {
        when(planningService.currentIfPresent()).thenReturn(Optional.empty());
        when(planningService.revisions()).thenReturn(List.of());

        assertThat(controller.planning(model)).isEqualTo("console/planning");
        verify(model).addAttribute("current", null);
        verify(model).addAttribute("revisions", List.of());
    }

    @Test
    void thePlanningPublishIsOneServiceCallWithAConfirmationBannerNamingTheRevision() {
        when(planningService.publish(anyString(), any()))
                .thenReturn(ConsolePlanningRevision.append(4, "{}", "note"));

        RedirectView view = controller.publishPlanning("{\"sections\":[]}", "note", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/planning");
        verify(planningService).publish("{\"sections\":[]}", "note");
        verify(redirect).addFlashAttribute(ConsoleWebController.FLASH_SUCCESS, "نُشرت المراجعة رقم 4");
    }

    @Test
    void anInvalidPlanningPayloadIsRefusedByTheBannerAndNothingIsStored() {
        when(planningService.publish(anyString(), any()))
                .thenThrow(new BadRequestException("The planning payload is not parseable JSON"));

        RedirectView view = controller.publishPlanning("not json", null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console/planning");
        verify(redirect).addFlashAttribute(ConsoleWebController.FLASH_ERROR,
                "The planning payload is not parseable JSON");
    }

    @Test
    void thePlanningRollbackIsOneServiceCallWithAConfirmationBannerNamingBothRevisions() {
        when(planningService.rollback(2, null))
                .thenReturn(ConsolePlanningRevision.append(7, "{}", "Rollback to revision 2"));

        RedirectView view = controller.rollbackPlanning(2, null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console/planning");
        verify(planningService).rollback(2, null);
        verify(redirect).addFlashAttribute(ConsoleWebController.FLASH_SUCCESS,
                "أُعيد نشر المراجعة 2 كمراجعة رقم 7");
    }

    @Test
    void theRollbackOfAnUnknownRevisionAnswersTheBanner() {
        when(planningService.rollback(anyInt(), any()))
                .thenThrow(new ResourceNotFoundException("ConsolePlanningRevision"));

        RedirectView view = controller.rollbackPlanning(99, null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console/planning");
        verify(redirect).addFlashAttribute(eq(ConsoleWebController.FLASH_ERROR), anyString());
    }

    @Test
    void oversizedFieldsAreRefusedByTheBannerAndNeverReachAnyService() {
        String overKey = "k".repeat(201);
        String overValue = "v".repeat(2001);
        String overNote = "n".repeat(501);

        controller.registerFlag(overKey, null, true, redirect);
        controller.registerConfig("key", overValue, null, redirect);
        controller.reviseConfig("key", overValue, null, redirect);
        controller.publishPlanning("{}", overNote, redirect);
        controller.rollbackPlanning(1, overNote, redirect);

        verify(redirect, org.mockito.Mockito.times(5))
                .addFlashAttribute(eq(ConsoleWebController.FLASH_ERROR), anyString());
        verifyNoInteractions(consoleService);
        verify(planningService, never()).publish(anyString(), any());
        verify(planningService, never()).rollback(anyInt(), any());
    }
}
