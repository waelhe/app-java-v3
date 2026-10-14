package com.marketplace.console;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import com.marketplace.console.ConsoleAdminController.FeatureFlagResponse;
import com.marketplace.console.ConsoleAdminController.GeographicSettingResponse;
import com.marketplace.console.ConsoleAdminController.RemoteConfigResponse;
import com.marketplace.console.ConsolePlanningController.PlanningBoardRow;
import com.marketplace.console.ConsolePlanningController.PlanningRevisionResponse;

import java.util.List;
import java.util.UUID;

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
 * <p><b>The write surface — every board a lever:</b> the flag flip (the
 * operator's most routine lever at incident time) plus the registrations
 * and revisions the REST console serves, each ONE service call behind a
 * form POST that redirects (handling-form-submission's own
 * post/redirect/get — a refresh never re-submits). The controller never
 * touches an entity (the {@code controllersMustNotDependOnJpaEntities}
 * architecture rule — the HTTP boundary speaks the same DTO records the
 * REST console speaks); CSRF rides the chain's documented default
 * (Thymeleaf's form integration carries the token); the class-level
 * {@code @PreAuthorize} mirrors the chain rule for defense in depth.
 *
 * <p><b>Form errors and confirmations — the flash contract:</b> a
 * rejected or accepted write answers a ONE-TIME flash message on the
 * redirected board (Spring MVC's own {@link RedirectAttributes} flash
 * map — the documented "post-redirect-get with attributes" channel), so
 * the operator always sees WHAT happened instead of a bare redirect:
 * validation refusals (blank/oversized fields — the same limits the REST
 * DTOs carry), duplicate registrations (409's polite face), and unknown
 * keys (404's polite face) become an Arabic banner on the board; a
 * successful write becomes a confirmation banner naming the key. The
 * attribute names ({@code consoleFlashError}/{@code consoleFlashSuccess})
 * are the template's own contract.
 *
 * <p><b>The planning board</b> ({@code GET /console/planning}) is the
 * ADR-0005 record's own operator screen: the current revision's payload,
 * the append-only trail (newest first), the publish form (the payload
 * validated at the service's gate BEFORE any write — an invalid shape is
 * refused with the banner, nothing stored), and the rollback form (the
 * target payload REPUBLISHED as a new revision — the trail never
 * rewrites).
 *
 * <p><b>What deliberately stays OUT of these pages:</b> every destructive
 * administrative action (pseudonymize, purge, role changes) keeps its
 * REST-first review path; the console renders the boards and the levers
 * documented above — the read-and-switch surface of daily operation. The
 * catalog's links document WHERE each deeper action lives (the existing
 * endpoints), not duplicate forms for them.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class ConsoleWebController {

    /** The flash banner's error attribute — the template's own contract. */
    static final String FLASH_ERROR = "consoleFlashError";

    /** The flash banner's confirmation attribute — the template's own contract. */
    static final String FLASH_SUCCESS = "consoleFlashSuccess";

    /** The REST DTOs' own field caps, carried by the web forms' guards. */
    private static final int KEY_MAX = 200;
    private static final int VALUE_MAX = 2000;
    private static final int NOTE_MAX = 500;

    private final ConsoleService consoleService;
    private final ConsolePlanningService planningService;

    public ConsoleWebController(ConsoleService consoleService,
                                ConsolePlanningService planningService) {
        this.consoleService = consoleService;
        this.planningService = planningService;
    }

    /**
     * The console home — the tri-partite catalog plus the live boards
     * (flags, config, geo settings, metrics, change history). Model
     * attribute names are the template's own contract.
     */
    @GetMapping("/console")
    public String console(Model model) {
        model.addAttribute("sections", ConsoleView.of().sections());
        model.addAttribute("flags", consoleService.flags().stream().map(FeatureFlagResponse::from).toList());
        model.addAttribute("configs", consoleService.configs().stream().map(RemoteConfigResponse::from).toList());
        model.addAttribute("geoSettings", consoleService.geographicSettings().stream()
                .map(GeographicSettingResponse::from).toList());
        model.addAttribute("meters", consoleService.metrics());
        model.addAttribute("changes", consoleService.changeHistory());
        return "console/console";
    }

    /**
     * The flag flip — handling-form-submission's POST shape: a form action
     * bound to this path, the service call, then the redirect (the guide's
     * own post/redirect/get so a refresh never re-submits). The whole flip
     * is ONE service call ({@link ConsoleService#flipFlag} — the row's own
     * state inverted, the auditing fields recording who and when); the
     * controller never touches the entity (the HTTP boundary speaks DTO
     * records only — the architecture rule the REST console itself
     * follows), and a key with no row is a service-level no-op.
     */
    @PostMapping("/console/flags/{key}/flip")
    public RedirectView flipFlag(@PathVariable String key) {
        consoleService.flipFlag(key);
        return consoleHome();
    }

    /**
     * The flag registration — the REST surface's own write through the
     * web form: blank or oversized keys are refused by the banner (the
     * REST DTO's own limits), a duplicate live key answers the banner
     * (409's polite face), and the accepted row lands on the redirected
     * board with its confirmation banner.
     */
    @PostMapping("/console/flags")
    public RedirectView registerFlag(@RequestParam String key,
                                     @RequestParam(required = false) String description,
                                     @RequestParam(required = false, defaultValue = "false") boolean enabled,
                                     RedirectAttributes redirect) {
        String trimmed = key == null ? "" : key.trim();
        if (!StringUtils.hasText(trimmed)) {
            return refused(redirect, "مفتاح العلم لا يمكن أن يكون فارغًا");
        }
        if (trimmed.length() > KEY_MAX || oversized(description)) {
            return refused(redirect, "المفتاح حتى 200 حرف والوصف حتى 2000 — القيم المرسلة أطول");
        }
        try {
            consoleService.registerFlag(trimmed, description, enabled);
            redirect.addFlashAttribute(FLASH_SUCCESS, "سُجّل العلم " + trimmed);
        } catch (ConflictException ex) {
            return refused(redirect, "العلم مسجّل من قبل: " + trimmed);
        }
        return consoleHome();
    }

    /**
     * The config registration — the REST surface's own write through the
     * web form: the value is the raw string the consumer parses, the
     * limits are the REST DTO's own.
     */
    @PostMapping("/console/config")
    public RedirectView registerConfig(@RequestParam String key,
                                       @RequestParam String value,
                                       @RequestParam(required = false) String description,
                                       RedirectAttributes redirect) {
        String trimmedKey = key == null ? "" : key.trim();
        String trimmedValue = value == null ? "" : value.trim();
        if (!StringUtils.hasText(trimmedKey) || !StringUtils.hasText(trimmedValue)) {
            return refused(redirect, "المفتاح والقيمة لا يمكن أن يكونا فارغين");
        }
        if (trimmedKey.length() > KEY_MAX || trimmedValue.length() > VALUE_MAX || oversized(description)) {
            return refused(redirect, "المفتاح حتى 200 حرف والقيمة والوصف حتى 2000 — القيم المرسلة أطول");
        }
        try {
            consoleService.registerConfig(trimmedKey, trimmedValue, description);
            redirect.addFlashAttribute(FLASH_SUCCESS, "سُجّل الإعداد البعيد " + trimmedKey);
        } catch (ConflictException ex) {
            return refused(redirect, "الإعداد البعيد مسجّل من قبل: " + trimmedKey);
        }
        return consoleHome();
    }

    /**
     * The config revision — the operator's value edit on an existing row,
     * ONE service call ({@link ConsoleService#reviseConfig}); an unknown
     * key answers the banner (404's polite face — a forged or stale form).
     */
    @PostMapping("/console/config/{key}/revise")
    public RedirectView reviseConfig(@PathVariable String key,
                                     @RequestParam String value,
                                     @RequestParam(required = false) String description,
                                     RedirectAttributes redirect) {
        String trimmed = value == null ? "" : value.trim();
        if (!StringUtils.hasText(trimmed)) {
            return refused(redirect, "قيمة الإعداد لا يمكن أن تكون فارغة");
        }
        if (trimmed.length() > VALUE_MAX || oversized(description)) {
            return refused(redirect, "القيمة والوصف حتى 2000 حرف — القيم المرسلة أطول");
        }
        try {
            consoleService.reviseConfig(key, trimmed, description);
            redirect.addFlashAttribute(FLASH_SUCCESS, "عُدّل الإعداد البعيد " + key);
        } catch (ResourceNotFoundException ex) {
            return refused(redirect, "لا إعداد بعيدًا بهذا المفتاح: " + key);
        }
        return consoleHome();
    }

    /**
     * The geographic registration — the REST surface's own write through
     * the web form: the location resolves through the geo port FIRST (an
     * unknown location answers the banner before any write — the L31
     * discipline verbatim), a duplicate live pair answers the banner.
     */
    @PostMapping("/console/geo-settings")
    public RedirectView registerGeographicSetting(@RequestParam String key,
                                                  @RequestParam String locationId,
                                                  @RequestParam(required = false, defaultValue = "false") boolean enabled,
                                                  RedirectAttributes redirect) {
        String trimmed = key == null ? "" : key.trim();
        UUID location = parseLocation(locationId);
        if (!StringUtils.hasText(trimmed)) {
            return refused(redirect, "مفتاح الميزة لا يمكن أن يكون فارغًا");
        }
        if (trimmed.length() > KEY_MAX) {
            return refused(redirect, "المفتاح حتى 200 حرف — القيمة المرسلة أطول");
        }
        if (location == null) {
            return refused(redirect, "معرّف الموقع ليس UUID صالحًا");
        }
        try {
            consoleService.registerGeographicSetting(trimmed, location, enabled);
            redirect.addFlashAttribute(FLASH_SUCCESS, "سُجّل الإعداد الجغرافي " + trimmed);
        } catch (ConflictException ex) {
            return refused(redirect, "الإعداد الجغرافي مسجّل من قبل: " + trimmed);
        } catch (ResourceNotFoundException ex) {
            return refused(redirect, "لا موقع بهذا المعرّف: " + locationId);
        }
        return consoleHome();
    }

    /**
     * The geographic flip — {@link #flipFlag(String)}'s own shape at one
     * scope: the row's state inverted in ONE service call; an absent (key,
     * location) pair is a no-op redirect (a forged or stale form — the
     * flag flip's own contract).
     */
    @PostMapping("/console/geo-settings/{key}/{locationId}/flip")
    public RedirectView flipGeographicSetting(@PathVariable String key,
                                              @PathVariable UUID locationId) {
        consoleService.flipGeographicSetting(key, locationId);
        return consoleHome();
    }

    /**
     * The planning board — ADR-0005's own operator screen: the current
     * revision's payload, the append-only trail (newest first, the rows'
     * own auditing fields), and the publish/rollback forms' own view.
     */
    @GetMapping("/console/planning")
    public String planning(Model model) {
        model.addAttribute("current", planningService.currentIfPresent()
                .map(PlanningRevisionResponse::of).orElse(null));
        List<PlanningBoardRow> revisions = planningService.revisions().stream()
                .map(PlanningBoardRow::of).toList();
        model.addAttribute("revisions", revisions);
        return "console/planning";
    }

    /**
     * The planning publish — the payload validated at the service's gate
     * BEFORE any write; an unparseable or invalid shape is refused with
     * the banner and NOTHING is stored (the closed-payload discipline).
     * The revision's own number reaches the banner through the DTO's
     * mapping factory — the controller never calls the entity (the
     * {@code controllersMustNotDependOnJpaEntities} rule the REST console
     * itself follows).
     */
    @PostMapping("/console/planning/publish")
    public RedirectView publishPlanning(@RequestParam String payload,
                                        @RequestParam(required = false) String note,
                                        RedirectAttributes redirect) {
        String trimmed = payload == null ? "" : payload.trim();
        if (!StringUtils.hasText(trimmed)) {
            return refusedPlanning(redirect, "حمولة التخطيط لا يمكن أن تكون فارغة");
        }
        if (oversizedNote(note)) {
            return refusedPlanning(redirect, "الملاحظة حتى 500 حرف — القيمة المرسلة أطول");
        }
        try {
            PlanningRevisionResponse published =
                    PlanningRevisionResponse.of(planningService.publish(trimmed, note));
            redirect.addFlashAttribute(FLASH_SUCCESS,
                    "نُشرت المراجعة رقم " + published.revisionNo());
        } catch (com.marketplace.shared.api.BadRequestException ex) {
            return refusedPlanning(redirect, ex.getMessage());
        }
        return planningBoard();
    }

    /**
     * The planning rollback — the target payload REPUBLISHED as a new
     * revision (the append-only trail never rewrites); an unknown target
     * answers the banner. The new revision's number rides the DTO's
     * mapping factory — the controller never calls the entity.
     */
    @PostMapping("/console/planning/rollback/{revisionNo}")
    public RedirectView rollbackPlanning(@PathVariable int revisionNo,
                                         @RequestParam(required = false) String note,
                                         RedirectAttributes redirect) {
        if (oversizedNote(note)) {
            return refusedPlanning(redirect, "الملاحظة حتى 500 حرف — القيمة المرسلة أطول");
        }
        try {
            PlanningRevisionResponse rolled =
                    PlanningRevisionResponse.of(planningService.rollback(revisionNo, note));
            redirect.addFlashAttribute(FLASH_SUCCESS,
                    "أُعيد نشر المراجعة " + revisionNo + " كمراجعة رقم " + rolled.revisionNo());
        } catch (ResourceNotFoundException ex) {
            return refusedPlanning(redirect, "لا مراجعة بهذا الرقم: " + revisionNo);
        }
        return planningBoard();
    }

    // -------------------------------------------------------------------------
    // the PRG helpers — the shapes every write shares
    // -------------------------------------------------------------------------

    private RedirectView consoleHome() {
        return new RedirectView("/console", true, false);
    }

    private RedirectView planningBoard() {
        return new RedirectView("/console/planning", true, false);
    }

    /** The refusal shape: the banner on the redirected HOME board. */
    private RedirectView refused(RedirectAttributes redirect, String message) {
        redirect.addFlashAttribute(FLASH_ERROR, message);
        return consoleHome();
    }

    /** The refusal shape: the banner on the redirected PLANNING board. */
    private RedirectView refusedPlanning(RedirectAttributes redirect, String message) {
        redirect.addFlashAttribute(FLASH_ERROR, message);
        return planningBoard();
    }

    private static boolean oversized(String field) {
        return field != null && field.length() > VALUE_MAX;
    }

    private static boolean oversizedNote(String note) {
        return note != null && note.length() > NOTE_MAX;
    }

    private static UUID parseLocation(String raw) {
        try {
            return UUID.fromString(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
