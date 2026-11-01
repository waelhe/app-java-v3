# مقترح الوكيل 3 إلى A0 — إعادة التشغيل التلقائي لبوبات CI/Integration Test على PRs الموجة

**المرجع**: توجيه المتابعة على PR #698 (2026-10-22 17:58) والقاعدة الحاكمة: أي تشغيل يدوي
(`workflow_dispatch`) يقوم مقام الإدارة التلقائية يجب إزالته وتفعيل الإدارة التلقائية.
ملفات workflows من الملفات التي لا يملكها وكيل منفرد (بند 5 من بروتوكول التنفيذ المستقل)،
لذا يُقدَّم هذا المقترح ولا يُنفَّذ ذاتيًا في فرع الوكيل.

## الفجوة المقاسة

مرشّح `pull_request.branches` في `.github/workflows/ci.yml`
و`.github/workflows/integration-test.yml` هو حاليًا:

```yaml
branches: [ main, feat/saleor-commerce-product-keyset-pagination, 'feat/saleor-*' ]
```

الوثائق الرسمية لـ GitHub (Events that trigger workflows) صريحة في أن مرشّح
`branches` لحدث `pull_request` يطابق **فرع القاعدة** للـ PR. كل PRs الموجة
(#696، #697، #698، #700) تستهدف `integration/saleor-full-commerce-20261021` —
دُمج منها #696 و#697 في القاعدة يوم 2026-10-22، والمفتوحتان الآن #698 و#700، وأي
PR متابعة يستهدف القاعدة نفسها حتى هبوط الموجة إلى main يدخل تحت الحكم نفسه — وهو
فرع لا يطابق أي نمط في القائمة، لذلك **لا تُطلق CI ولا Integration Test تلقائيًا على أي
دفع إلى هذه الـ PRs**.

النتيجة المقاسة خلال الموجة: كانت البوبات تُشغَّل يدويًا عبر `workflow_dispatch` على
رؤوس متفرقة (مثل run 39818344671 الملغى وrun 39822419751 الفاشل على `880f1c4`)،
بينما الرؤوس المدفوعة فعليًا — `2d6baac` و`df73938` — لم يعلُها أي فحص CI/Integration
Test إطلاقًا (المتوفر عليهما حوكمة الجرد فقط). هذا بالضبط النوع الذي يمنعه توجيه
المالك: «شغّل CI والاختبارات المناسبة على الرأس النهائي الحالي»، مع بقاء الاعتماد على
اليدوي بديلًا عن التلقائي.

## التعديل المقترح (في كلا الملفين، سطر واحد لكل ملف)

استبدال سطر المرشّح بـ — **اسم الفرع الدقيق لا glob**، لأن الموجة تستهدف فرع
integration واحدًا بعينه وشرط التقاعد مرتبط به هو:

```yaml
branches: [ main, feat/saleor-commerce-product-keyset-pagination, 'feat/saleor-*', integration/saleor-full-commerce-20261021 ]
```

مع تعليق يوثّق الموجة وشرط التقاعد، بمنهجية الملف نفسه (إدخالات الموجات السابقة
تقاعدت مع موجاتها):

```yaml
    # Saleor full-commerce campaign wave (2026-10-21): every PR of this
    # wave (#696/#697 merged into the base already; #698/#700 still open)
    # targets exactly one integration base,
    # integration/saleor-full-commerce-20261021 — listed by its exact
    # name on purpose, no glob, so the gates gate every push to those PRs
    # through the pull_request event itself: automatic management, never
    # a manual workflow_dispatch standing in for the trigger the base
    # list forgot. Retire this exact entry when the wave merges to main,
    # exactly as the stacked-wave entries above retired with theirs.
```

## لماذا هذا التعديل هو التصميم الصحيح

1. **إضافي فقط**: لا يمس سلوك أي PR يستهدف `main` أو `feat/saleor-*`، ولا أحداث
   `push`. الإدخال الجديد فرع integration الوحيد للموجة **بالاسم الدقيق — لا glob**،
   فلا يلتقط أي فرع integration آخر مستقبليًا إلا بقرار A0 صريح.
2. **يستعيد القصد الموثق للملف نفسه**: تعليق الملف يقول «the four gates run on
   pull_request for the wave's integrative PRs» — القصد أن تبوب الموجة عبر
   `pull_request`، والقائمة ببساطة لم تُحدَّث عندما تغيّرت قاعدة الموجة إلى فرع
   integration.
3. **ينهي الاعتماد على `workflow_dispatch`** كآلية تشغيل للبوبات على هذه الموجة —
   التشغيل اليدوي كان سبب الفجوة المقاسة أعلاه.
4. **انضباط التزامن موجود أصلًا**: مجموعة `concurrency` في الملفين تلغي الجولات
   المتجاوزة لنفس الحدث على نفس المرجع، فلا خطر طوفان تشغيلات.
5. **سطرا تعليق «Temporary stacked Saleor PR target pattern»** في الملفين يشترطان
   التقاعد بعد تسوية #677–#681 — وقد سُوّيت فعلًا (مغلقة منذ 2026-10-20)، فيبقى
   تقاعد نمط `feat/saleor-*` قرار A0 في truth-sync لاحق، ولم يمسه هذا المقترح.

## الأثر المتوقع بعد التطبيق

كل دفع إلى أي PR يستهدف `integration/saleor-full-commerce-20261021` يشغّل تلقائيًا
سيرتي CI وIntegration Test عبر حدث `pull_request`. وبحكم الوثائق الرسمية لـ GitHub
(Events that trigger workflows) تعمل الوظائف على **مرجع الدمج (merge ref) الذي ينشئه
GitHub للـ PR** — `refs/pull/N/merge`، أي دمج رأس الـ PR في قاعدته — وليس على رأس
الـ PR نفسه؛ هذا هو السياق الذي تُقرأ منه نتائج البوابات. ملف CI يُعدّ 15 وظيفة
(البوابات + البناء + اختبارات الوحدات + شظايا التطبيق — هذا عدد مُعدّ في الإعداد لا
ادّعاء اكتمال في جولة بعينها)، وملف Integration Test يُعدّ الإقلاع الحقيقي وسيناريو
الدخان المركب؛ أما ما اكتمل فعلًا في أي جولة فحقيقة تُقرأ من الجولة نفسها لا من
الإعداد. وقرارات الدمج تُقرأ من نتائج المرجع الفعلي لا من تشغيلات يدوية متفرقة.

## ما نفّذه الوكيل 3 داخل نطاقه في هذه الجولة

- إصلاح علة كامنة في `OrderGraphQlIntegrationTest` (نطاق الوكيل 3 بشهادة كوميت
  الإصلاح a5094d7): الـ seed كان يُدخل صفوف orders بـ `consumer_id` غير موجود في
  `users` فيخالف قيد `fk_orders_users` (V113) — اختبار لم يمرّ أخضر في CI قط لأنه
  وُلد على مفاعل أحمر. الآن الـ seed ينشئ مستهلكًا حقيقيًا (نمط UserGraphQl/ProductMedia
  القائم) والحذف يتبع الترتيب الآمن للقيد. أثبتت الجولة النهائية على الرأس e543014
  إصلاحها: كل اختباراتها الـ23 التي كانت تعطب على قاعدة التكامل النقية d10a901 أصبحت
  خضراء، وصفر اختبار جديد فشل (المقارنة الحرفية موثقة على الـ PR).
- مواءمة ساق anonymous مع قرار A0 الأمني المهبوط (b082816dd): العقد المعاد فتحه هو
  HTTP 200 + مغلف خطأ GraphQL مصنف AUTHN-001 (كما يثبته `GraphQlSecurityIntegrationTest`)،
  لا 401 problem+json القديم.

الرأس الحامل لهذا المقترح يُسلَّم مع بوبات مُشغَّلة عليه عبر `workflow_dispatch`
(الآلية المتاحة حاليًا وبأمر المالك الصريح في توجيه المتابعة، وهي تعمل على رأس الفرع
المحدد نفسه لا على مرجع الدمج)، إلى أن يهبط هذا التعديل من A0 على فرع التكامل فتصبح
البوبات تلقائية للدفعات التالية.
