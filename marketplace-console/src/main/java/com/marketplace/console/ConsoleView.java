package com.marketplace.console;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * B-15 (compliance plan C.5 — the tri-partite console classification,
 * the vision report's structure the plan adopted): the console's own
 * view — the three sections (تشغيل/محتوى/نظام) with the surfaces each
 * links to. The catalog is a measured inventory of the EXISTING admin
 * surfaces (the identity statement's active limit verbatim: «القدرة
 * غير الموجودة كوداً تبقى تطويراً» — a surface that does not exist in
 * code is NOT listed; every path below is verified against the code's
 * own controller mappings, and new surfaces join this list in the same
 * PR that lands them).
 */
public record ConsoleView(
        @Schema(description = "The console's sections — the tri-partite classification (operations / content / system).")
        List<Section> sections
) {

    /** One console section — its Arabic title and its surfaces. */
    public record Section(
            @Schema(description = "The section's title (Arabic).") String title,
            @Schema(description = "The section's surfaces — the existing admin/administrative endpoints.") List<Surface> surfaces
    ) {
    }

    /** One surface — a link to an EXISTING endpoint (what the code serves today). */
    public record Surface(
            @Schema(description = "The surface's label (Arabic).") String label,
            @Schema(description = "The existing endpoint's path.") String path
    ) {
    }

    /**
     * The measured inventory — every path listed is a surface the CODE
     * serves today (AdminController: /payments, /bookings, /settings,
     * /revisions; ModerationAdminController: /reports;
     * ReviewModerationAdminController: /reviews/moderation;
     * NeighborhoodVerificationAdminController:
     * /neighborhood-memberships; InstitutionAdminController:
     * /institutions; this module's own /console surfaces).
     */
    public static ConsoleView of() {
        return new ConsoleView(List.of(
                new Section("التشغيل", List.of(
                        new Surface("المدفوعات", "/api/v1/admin/payments"),
                        new Surface("الحجوزات", "/api/v1/admin/bookings"),
                        new Surface("توثيق الجوار (طابور المراجعة)", "/api/v1/admin/neighborhood-memberships"),
                        new Surface("توثيق المؤسسات (طابور المراجعة)", "/api/v1/admin/institutions"))),
                new Section("المحتوى", List.of(
                        new Surface("إشراف المراجعات", "/api/v1/admin/reviews/moderation"),
                        new Surface("إشراف المجتمع (البلاغات)", "/api/v1/admin/reports"),
                        new Surface("دليل المعرفة (اللوحة العامة)", "/api/v1/knowledge"),
                        new Surface("سجل المؤسسات (اللوحة العامة)", "/api/v1/institutions"))),
                new Section("النظام", List.of(
                        new Surface("إعدادات النظام", "/api/v1/admin/settings"),
                        new Surface("مراجعات الكيانات", "/api/v1/admin/revisions/entities"),
                        new Surface("أعلام الميزات", "/api/v1/admin/console/flags"),
                        new Surface("الإعداد البعيد", "/api/v1/admin/console/config"),
                        new Surface("إعدادات الميزات الجغرافية (بلد ← مدينة ← حي)", "/api/v1/admin/console/geo-settings"),
                        new Surface("البوابة الفعّالة عند نطاق جغرافي (قراءة)", "/api/v1/admin/console/geo-settings/effective"),
                        new Surface("المقاييس (قراءة)", "/api/v1/admin/console/metrics"),
                        new Surface("سجل التغييرات (قراءة)", "/api/v1/admin/console/audit")))));
    }
}
