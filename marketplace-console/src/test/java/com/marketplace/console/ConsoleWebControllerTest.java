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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The manager console's web leg — the officially guided server-rendered
 * experience (spring.io guides: serving-web-content's controller/model/view
 * shape; handling-form-submission's post/redirect/get). The unit pins the
 * two controller halves: the home view carries the SAME tri-partite catalog
 * and the SAME four boards the REST console serves, and the flip is ONE
 * service call — the controller never touches the entity (the
 * {@code controllersMustNotDependOnJpaEntities} architecture rule; the
 * entity stays inside the service layer where it belongs).
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
    void theFlipIsOneServiceCallAndRedirects() {
        RedirectView redirect = controller.flipFlag("some.flag");

        // post/redirect/get — the guide's own pattern (a refresh never re-submits)
        assertThat(redirect.getUrl()).isEqualTo("/console");
        // the whole flip is the service's own method — the row's state
        // inverted inside the service, never in the controller
        verify(consoleService).flipFlag("some.flag");
    }
}
