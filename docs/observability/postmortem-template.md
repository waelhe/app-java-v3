# قالب ما بعد الحادث (Postmortem Template) — بلا لوم بالضرورة

> **المصادر الرسمية الحاكمة** (جُلبت وحُفظت نصياً عند فتح البند 3.3 — طقوس §10):
> Google SRE Book › Ch.15 «Postmortem Culture: Learning from Failure» — النسخة المحفوظة `scripts/doc-verify/sre-postmortem-culture.html` ·
> Google SRE Workbook › Ch.10 «Postmortem Culture» — النسخة المحفوظة `scripts/doc-verify/sre-workbook-postmortem-culture.html`.
>
> هذا الملف هو **القالب الفارغ المعياري** لكل سجلات ما بعد الحادث في المنصة. السجلات الفعلية تُنشأ بنسخ القسم الأخير إلى `docs/observability/postmortems/YYYY-MM-DD-<slug>.md` وتعبئته. الحارس `PostmortemFilesTest` (marketplace-app) يفرض تطابق كل سجل مع أقسام القالب الإلزامية وبنية بنود العمل.

## 1. التعريف الرسمي — ما هو سجل ما بعد الحادث

التعريف الحرفي من الفصل الرسمي:

> «A postmortem is a written record of an incident, its impact, the actions taken to mitigate or resolve it, the root cause(s), and the follow-up actions to prevent the incident from recurring.»

أي خمسة مكوّنات لا يكتمل السجل بأقل منها: **الحادث · الأثر · إجراءات المعالجة/الاحتواء · السبب/الأسباب الجذرية · بنود المتابعة الوقائية**. الهدف الأول المعلن في الفصل نفسه: «ensure that the incident is documented, that all contributing root cause(s) are well understood, and, especially, that effective preventive actions are put in place to reduce the likelihood and/or impact of recurrence».

## 2. متى يُكتب سجل — محفزات الفصل الرسمي

الفصل يحدد المحفزات الموضوعية (ويُعرَّف الشرط **قبل** وقوع الحادث حتى يعلم الجميع متى يلزم السجل):

- توقف أو تدهور مرئي للمستخدم يتجاوز عتبة معلنة (عندنا: عقد SLO-1/2/3 في `docs/observability/slo.md`)
- فقدان بيانات من أي نوع
- تدخّل مهندس النداء (تراجع إصدار، إعادة توجيه ترافيك…)
- زمن حل يتجاوز عتبة معلنة
- فشل مراقبة (وهذا يعني عادة اكتشافاً يدوياً للحادث)
- طلب أي صاحب مصلحة — بلا استثناء

في هذه المنصة، تشتغل قاعدة الإنذار `MarketplaceAggregateHealthDown` (بند 3.2) وقناة المراقب (watchdog) ككاشفات مؤتمتة؛ أي إنذار critical من `marketplace-alerts.yml` يستوجب سجلاً بهذا القالب.

## 3. قاعدة «بلا لوم» — الشرط الثقافي الصريح

الفصل الرسمي يحملها نصاً:

> «For a postmortem to be truly blameless, it must focus on identifying the contributing causes of the incident without indicting any individual or team for bad or inappropriate behavior. A blamelessly written postmortem assumes that everyone involved in an incident had good intentions and did the right thing with the information they had.»

والقاعدة العملية المشتقة منها في نفس الفصل: «You can't "fix" people, but you can fix systems and processes to better support people making the right choices». لذلك كل جملة في السجل تُكتب عن **النظام والعملية والمعلومة المتاحة لحظتها** — لا عن الأشخاص. سجل يحمّل فرداً أو فريقاً مسؤولية سلوكية يُعاد كتابته قبل اعتماده.

## 4. بنية السجل الإلزامية (القالب)

كل سجل تحت `docs/observability/postmortems/` يحمل هذه الأقسام الثمانية بترتيبها — الحارس `PostmortemFilesTest` يفرضها:

| # | القسم | ما يحمله (من الفصل الرسمي) |
|---|---|---|
| 1 | **الملخص (Summary)** | فقرة واحدة: ما حدث، لمن، كم استمر، الحالة الحالية |
| 2 | **الأثر (Impact)** | المقاس بالأرقام: المستخدمون/الطلبات/الإيراد المفترض/النوافذ الزمنية — تقييم أثر كامل (بند قائمة الإقفال الرسمية) |
| 3 | **الخط الزمني (Timeline)** | كل نقطة بتوقيت UTC ومصدر قياسها (سجل/إنذار/قياس حي) — من أول أثر حتى الإغلاق |
| 4 | **السبب الجذري (Root Cause(s))** | الأسباب المساهمة على مستوى النظام — «sufficiently detailed root-cause analysis to drive action item planning» (قائمة الإقفال) |
| 5 | **المعالجة والاحتواء (Mitigation & Resolution)** | ما نُفّذ فعلاً لإيقاف النزيف ثم للإصلاح، وبأي أداة/PR |
| 6 | **ما سار جيداً وما تعثر** | الاثنان معاً — ما كشف الحادث مبكراً، وما أخّر اكتشافه/حله |
| 7 | **الدروس (Lessons Learned)** | ما يتعمم على المنصة كلها لا على هذا الحادث وحده |
| 8 | **بنود العمل (Action Items)** | جدول: البند · المسؤول · الأولوية · الحالة (مفتوح/منجز) · المرجع (PR/issue/ملف) |

## 5. بنود العمل — قاعدة التتبع حتى الإقفال

الـWorkbook الرسمي صريح في أن كتابة السجل بلا إقفال بنوده دَين يُنتج حوادث متكررة: «If you reward engineers for writing postmortems, but not for closing the associated action items, you risk an unvirtuous cycle of unclosed postmortems»، و«we can monitor the closure of action items from each postmortem. With this level of tracking, we can ensure that action items don't slip through the cracks». لذلك في هذه المنصة:

- كل بند عمل يحمل **مسؤولاً وحالة** — يُحدَّثان في نفس ملف السجل حتى الإقفال.
- قائمة الإقفال الرسمية (أمثلتها الحرفية من الـWorkbook): «Perform a complete assessment of incident impact» · «Conduct sufficiently detailed root-cause analysis to drive action item planning» · «Ensure action items are vetted and approved by the technical leads of the service» · «Share the postmortem with the wider organization».
- لا يُغلق السجل (حالته تصير «معتمد») إلا وبنوده كلها منجزة أو مصرَّح بدينها ونقطة إغلاقها.

---

## 6. القالب الفارغ (انسخ هذا القسم إلى `postmortems/` وعبّئه)

```markdown
# <عنوان الحادث>

| الحقل | القيمة |
|---|---|
| التاريخ (UTC) | YYYY-MM-DD |
| الحالة | مسودة / قيد المراجعة / معتمد |
| الخطورة | critical / warning |
| المحفز الرسمي | (من قائمة §2) |
| مصدر الاكتشاف | إنذار / مراقب / قياس / مستخدم |
| كاتب السجل | (اسم/جلسة) |

## 1. الملخص (Summary)

(فقرة واحدة: ما حدث، لمن، كم استمر، الحالة الحالية.)

## 2. الأثر (Impact)

| البعد | القيمة المقاسة | مصدر القياس |
|---|---|---|
| (مستخدمون/طلبات/إيراد/نوافذ زمنية) | (رقم أو «غير قابل للقياس مع (السبب)») | (إنذار/سجل/رابط) |

## 3. الخط الزمني (Timeline) — UTC

| الوقت | الحدث | المصدر |
|---|---|---|
| YYYY-MM-DD HH:MM | (أول أثر/أول اكتشاف/كل قرار) | (سجل/إنذار/قياس) |

## 4. السبب الجذري (Root Cause(s))

(الأسباب المساهمة على مستوى النظام — بلا لوم: المعلومة المتاحة، البنية، العملية.)

## 5. المعالجة والاحتواء (Mitigation & Resolution)

(ما أوقف الأثر، وما أصلح الجذر، وبأي PR/أداة — بالروابط.)

## 6. ما سار جيداً وما تعثر

- **سار جيداً:** (…)
- **تعثر:** (…)

## 7. الدروس (Lessons Learned)

(ما يتعمم على المنصة.)

## 8. بنود العمل (Action Items)

| البند | المسؤول | الأولوية | الحالة | المرجع |
|---|---|---|---|---|
| (إجراء وقائي) | (مالك) | (P1/P2/P3) | مفتوح/منجز | (PR/issue/ملف) |
```
