package com.marketplace.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The manager console's web leg — the officially guided server-rendered
 * experience (spring.io guides: serving-web-content's controller/model/view
 * shape; handling-form-submission's post/redirect/get). The unit pins the
 * two controller halves: the home view carries the SAME tri-partite
 * catalog and the SAME four boards the REST console serves, and the flip
 * writes through the SAME service method with the row's own state — no
 * parallel write path, and a key with no row never writes.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleWebControllerTest {

    @Mock
    private ConsoleService consoleService;

    @Mock
    private Model model;

    @InjectMocks
    private ConsoleWebController controller;

    @Test
    void theHomeViewIsTheConsoleTemplateCarryingTheCatalogAndBoards() {
        when(consoleService.flags()).thenReturn(List.of());
        when(consoleService.configs()).thenReturn(List.of());
        when(consoleService.metrics()).thenReturn(List.of());
        when(consoleService.changeHistory()).thenReturn(List.of());

        String view = controller.console(model);

        assertThat(view).isEqualTo("console/console");
        // the model contract: the catalog + the four boards
        verify(model).addAttribute("sections", ConsoleView.of().sections());
        verify(model).addAttribute("flags", List.of());
        verify(model).addAttribute("configs", List.of());
        verify(model).addAttribute("meters", List.of());
        verify(model).addAttribute("changes", List.of());
    }

    @Test
    void theFlipWritesTheRowStateThroughTheServiceAndRedirects() {
        FeatureFlag flag = FeatureFlag.register("some.flag", "d", true);
        when(consoleService.flags()).thenReturn(List.of(flag));

        RedirectView redirect = controller.flipFlag("some.flag");

        // post/redirect/get — the guide's own pattern (a refresh never re-submits)
        assertThat(redirect.getUrl()).isEqualTo("/console");
        // the row's own state, inverted — not the policy-default read
        verify(consoleService).setFlag("some.flag", false);
    }

    @Test
    void aKeyWithNoRowNeverWrites() {
        when(consoleService.flags()).thenReturn(List.of());

        RedirectView redirect = controller.flipFlag("unknown.flag");

        assertThat(redirect.getUrl()).isEqualTo("/console");
        verify(consoleService, never()).setFlag(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
