# Project-Specific Rules — Backend Java (V3)

Follows the global AGENTS.md at `~/.config/opencode/AGENTS.md`.
This file adds project-specific conventions.

## 0. الإجبار النظامي (System-Mandatory) — شرط مسبق قبل كل إجراء (جبري)

> **هذا القسم جبري (enforced) بأمر المستخدم المستمر: «اعتمد فهمك للبنية في كل إجراء قادم» و«لا تقل فقط بل اجعله جبرياً». لا يُتخَطَّى.** مقتضاه: أي إجراء (تعديل ملف، أمر bash يغيّر شيئاً، إنشاء/دفع/دمج PR، قرار معماري) **لا يبدأ** حتى يُحمَّل الفهم النظامي ويُسنَد إلى الملف الحاكم — وإلا توقف تلقائياً. (هذا القسم مكرر في `skill protocol-enforcer` §0.)

### 0.1 التسلسل الإجباري قبل أي إجراء (Mandatory load order)
```text
1. SYSTEM.md     — الخريطة المرجعية للآلية (§1-§13) + خريطة التعمق §10
2. PROJECT_MAP.md — الحالة الفعلية (ما دُمج، ما مفتوح، ما معلّق)
3. الملف الحاكم للمهمة الحالية — من §11/خريطة المرجع:
     - auth redesign          → docs/security/auth-system-redesign-plan.md
     - client bootstrap       → docs/security/oauth2-client-bootstrap-spec.md
     - client/hosting plan    → docs/security/client-hosting-strategy-plan.md
     - feature expansion      → docs/feature-expansion-roadmap.md
     - realestate systems     → docs/realestate-systems-plan.md
     - postgis integration     → docs/postgis-integration-plan.md
     - community layer        → docs/neighborhood-community-plan.md
     - community platform execution → docs/community-platform-execution-plan.md (product scope, measured fit-gap, phases and acceptance gates; unified v2 merge of the attachment evaluation + #531 plan + live code measurement; technical authority remains docs/official-compliance-plan.md)
     - community platform product management → docs/community-platform-product-management.md (product-manager workflow: discovery, PRD, prioritization, fit-gap, DoR/DoD, KPIs and role contracts; scope and the central D decision register remain owned by community-platform-execution-plan.md)
     - community platform user journeys → docs/community-platform-user-journeys.md (required companion for JT-01..JT-17, including local-knowledge provenance/freshness/correction and the community-recommendation-to-original-business/service journey; covers end-to-end UX flows, states, permissions, recovery and acceptance IDs; interpret with community-platform-execution-plan.md; technical authority remains docs/official-compliance-plan.md)
     - community platform UX design → docs/community-platform-ux-design.md (design system: semantic tokens, component library CMP-01..41, sitemap + full UJ screen catalog, RTL/Arabic implementation rules, WCAG 2.2 AA, visual trust system, cold-start and first-week programs, per-journey KPIs; the visual/interaction contract for client work)
     - community platform backend execution → docs/community-platform-backend-execution.md (measured 26-module baseline, domain ownership map, code-first API contract with springdoc-generated OpenAPI and compatibility gate, RFC 9457 problem details, trust-facts model, institutions/official messaging, search+AI ladder, events catalog, data rights, wave plan C-0..C-11 mapped to unified plan phases 0-11 with per-wave gates)
     - community platform mobile client → docs/community-platform-mobile-client.md (greenfield client: platform decision D-17 analysis with official-docs criteria, feature-first architecture with generated OpenAPI clients, RTL implementation, widget library = CMP mapping with golden tests, push via existing notification system, versioning per JT-14, wave plan M-0..M-8)
     - community platform delivery handbook → docs/community-platform-delivery-handbook.md (the binding how-we-work file for developers and AI agents: wave=PR model, per-wave DoD and acceptance reports, agent protocol, KPI operations, launch programs, cross-track responsibility matrix, unified decision register D-01..D-28)
     - unified platform       → docs/unified-platform-plan.md
     - إصلاح أخر               → الملف المعني + CODING_STANDARDS.md
4. ثم الشجرة المستهدفة من خريطة §10
```
قاعدة: «الملف الحاكم» = المستند الذي يملك قرارات المهمة. لا يقبَل إجراء بلا إسناد صريح إليه.

### 0.2 قبل أي إجراء، أعلن بنص صريح (لا ضمنياً)
```text
[الملف الحاكم]: <مسار الملف الذي يحكم هذه المهمة>
[النواة المعمارية]: <أي طبقة من SYSTEM.md §1-§9 تمسها (بناء/إقلاع/وحدات/أمن/بيانات/إعدادات)>
[البند من المصفوفة §14.2]: <11 بنود الاتساق أو بوابة A/B/C إن كانت مهمة>
[أثر على الحدود]: <Modulith/SPI/أحداث أم لا>
[دين: لا/نعم - موثق]
```
أي إجراء لا يحمل هذا الإعلان **يُتوقف تلقائياً** قبل التنفيذ.

### 0.3 مبدأ «الباك اند مرساةً» — نطاق الحرية المنضبطة
من خطة العملاء والاستضافة (§1): النواة (Boot/Modulith/SAS/Java) مغلقة على main؛ أي عميل مستقبلي = **إعداد + اختبار** عبر مسار `RegisteredClientRepository.save` (env→DB) + CORS env + اختبار S2/S3 للصف الفعلي — **صفر كود/وحدة/اعتماديات جديدة**. لا عودة عن D6 (مفاتيح prod fail-fast) ولا عن INV-2 (إعدادات صريحة كاملة).

### 0.4 البوابات المفتوحة لا تبدأ بلا كلمة المستخدم
- بوابة B (تقنية العميل ونمطه) → بوابة C (جهة الاستضافة)، بالترتيب (§6 خطة العملاء والاستضافة)، كلٌّ بكلمة صريحة. لا تفترض إجابة.

## Additional Rules

- **CI**: Run `mvn clean verify -pl <module>` before pushing
- **Testing**: All integration tests MUST have `@ActiveProfiles("test")` and `@Testcontainers(disabledWithoutDocker = true)` or `@Container`
- **Flyway**: Any schema change = new V{number} migration file. Never modify existing migrations
- **Envers**: All domain entities MUST have `@Audited`
- **`@ConfigurationProperties` binding**: a nested record section with absent keys binds to `null` (official constructor-binding rule); prime the component with an empty `@DefaultValue` to always bind a non-null defaulted instance (Spring Boot reference — Features › Externalized Configuration › Constructor binding)
- **Protocols enforced**: Planning → Execution → Surgical Editing (see global AGENTS.md)

## 1. بروتوكول تشغيل الوكيل — النسخة الدائمة على GitHub (مضاف 2026-09-28)

- الوكيل الذي يستلم المستودع **بذاكرة صفرية** يبدأ من `docs/agent-protocol.md` — طبقة الإقلاع والاستحواذ التي تربط عائلة ملفات الجذر (هذا الملف / SYSTEM.md / PROJECT_MAP.md / docs/METHODOLOGY.md / CONTRIBUTING.md) في سلسلة واحدة تُبنى من GitHub وحده. ومهارة الإقلاع القابلة للاشتقاق لأي نظام وكلاء في `docs/agent-skill.md`.
- عند أي خلاف بين الملف العالمي المحلي (`~/.config/opencode/AGENTS.md`) أو مهارة `protocol-enforcer` المحلية وهذين التوأمين: **التوأم في المستودع يحكم** وتُرقَّى النسخ المحلية لمطابقته (النسخ المحلية conveniences تُعاد بناؤها منه، لا اعتماديات).
- سياسة اللغة (ملزمة): الإنجليزية لطبقة الآلة (الكود، عناوين الـcommits، الوثائق التقنية الموجهة للـAI)؛ العربية لطبقة المالك (الردود، أجسام الـPRs بالأرقام المقاسة) — ملفات الجذر القائمة تبقى كما هي.
- لا دمج إلى `main` إلا بكلمة صريحة من المالك عبر بوابات الدمج الثلاث المقيسة — الدمج نفسه مُحرّك النشر إلى الإنتاج (auto-deploy).
