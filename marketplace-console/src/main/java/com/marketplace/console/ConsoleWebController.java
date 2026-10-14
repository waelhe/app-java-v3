package com.marketplace.console;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.view.RedirectView;

import com.marketplace.console.ConsoleAdminController.FeatureFlagResponse;
import com.marketplace.console.ConsoleAdminController.RemoteConfigResponse;

/**
 * The manager console's web leg — the officially guided server-rendered
 * experience (the spring.io guides' own stack, verbatim in shape):
 * gs/serving-web-content's {@code @Controller} + {@code Model} + a
 * Thymeleaf view; gs/handling-form-submission's form POST with the
 * redirect-after-POST pattern; gs/securing-web's authorization rule
 * ({@code hasRole('ADMIN')} — declared on the default form-login chain in
 * SecurityConfig, the same vocabulary every /api/v1/admin surface
 * carries).
 *
 * <p><b>What this page IS:</b> the operator's one screen — the console's
 * own tri-partite catalog (تشغيل/محتوى/نظام, the {@link ConsoleView}
 * inventory of the EXISTING admin surfaces), the live feature-flags board
 * and remote-config board, the metrics summary from the {@code
 * MeterRegistry}, and the change history from the rows' own Data JPA
 * auditing fields — the same four reads the REST console serves, rendered
 * server-side so the manager needs no token tooling, no curl, no Swagger.
 *
 * <p><b>The one write:</b> the flag flip — the operator's most routine
 * lever (enable/disable a feature at incident time). The form POSTs to
 * this controller, which calls the SAME {@link ConsoleService} methods
 * the REST surface calls ({@code setFlag} — the auditing fields record
 * who and when); no parallel write path exists. CSRF rides the chain's
 * documented default (Thymeleaf's form integration carries the token);
 * the class-level {@code @PreAuthorize} mirrors the chain rule for
 * defense in depth — the method-security shape the REST console itself
 * uses.
 *
 * <p><b>What deliberately stays OUT of this page:</b> every destructive
 * administrative action (pseudonymize, purge, role changes) keeps its
 * REST-first review path; the console renders the boards and the flip —
 * the read-and-switch surface of daily operation. The catalog's links
 * document WHERE each deeper action lives (the existing endpoints), not
 * duplicate forms for them.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class ConsoleWebController {

    private final ConsoleService consoleService;

    public ConsoleWebController(ConsoleService consoleService) {
        this.consoleService = consoleService;
    }

    /**
     * The console home — the tri-partite catalog plus the four live boards
     * (flags, config, metrics, change history). Model attribute names are
     * the template's own contract.
     */
    @GetMapping("/console")
    public String console(Model model) {
        model.addAttribute("sections", ConsoleView.of().sections());
        model.addAttribute("flags", consoleService.flags().stream().map(FeatureFlagResponse::from).toList());
        model.addAttribute("configs", consoleService.configs().stream().map(RemoteConfigResponse::from).toList());
        model.addAttribute("meters", consoleService.metrics());
        model.addAttribute("changes", consoleService.changeHistory());
        return "console/console";
    }

    /**
     * The flag flip — handling-form-submission's POST shape: a form action
     * bound to this path, the service call, then the redirect (the guide's
     * own post/redirect/get so a refresh never re-submits). The flip reads
     * the ROW's own state (not {@code isEnabled}'s policy default — the
     * two are different reads by design); a key with no row — a forged or
     * stale form — simply redirects back without any write.
     */
    @PostMapping("/console/flags/{key}/flip")
    public RedirectView flipFlag(@PathVariable String key) {
        consoleService.flags().stream()
                .filter(flag -> flag.getKey().equals(key))
                .findFirst()
                .ifPresent(flag -> consoleService.setFlag(key, !flag.isEnabled()));
        return new RedirectView("/console", true, false);
    }
}
