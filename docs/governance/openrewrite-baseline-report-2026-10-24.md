# تقرير OpenRewrite — نتائج التشغيل السابق (24 أكتوبر 2026)

> يصف هذا التقرير التشغيل قبل فصل جرد الاعتماديات عن توصيات تعديل المصدر.

## حالة التشغيل

- المستودع: `waelhe/app-java-v3`
- PR: [#720](https://github.com/waelhe/app-java-v3/pull/720)
- Commit المفحوص: `e1383d26782e726e986a980334775bc26c109a21`
- التشغيل: [GitHub Actions 40053346950](https://github.com/waelhe/app-java-v3/actions/runs/40053346950)
- النتيجة: نجحت المصادقة و`rewrite:discover` و`rewrite:dryRun` والتحقق من عدم تعديل الملفات المتتبعة.
- artifact: `openrewrite-baseline-9e3448a6127203fabf8b6d012e6ed54d66fccca2` — 405,191 بايت مضغوطًا.

## النتائج المقاسة

| القياس | النتيجة |
|---|---:|
| حجم `rewrite.patch` | 2,357,543 بايت |
| ملفات ذات نتائج/اقتراحات | 1,000 |
| ملفات Java | 969 |
| ملفات Maven XML | 31 |
| صفوف `SourcesFileResults` | 1,092، وقد تتداخل بين الوصفات |
| صفوف جرد الاعتماديات | 5,294 |
| الاعتماديات المباشرة / الانتقالية | 305 / 4,989 |
| فحص Maven Central | HTTP 200 |
| فحص Code Genome | HTTP 200 |

### النتائج حسب الوصفة

| الوصفة | صفوف النتائج |
|---|---:|
| `org.openrewrite.java.format.AutoFormat` | 900 |
| `org.openrewrite.java.RemoveUnusedImports` | 122 |
| `org.openrewrite.maven.BestPractices` | 35 |
| `org.openrewrite.maven.SortDependencies` | 31 |
| `org.openrewrite.maven.RemoveRedundantDependencyVersions` | 3 |
| `org.openrewrite.maven.OrderPomElements` | 1 |

الأعداد متداخلة وليست ملفات مستقلة قابلة للجمع. أظهر الـ patch أن AutoFormat يغيّر إزاحة continuation في سجلات Java من 8 مسافات إلى بداية السطر. لم تُطبق تغييرات patch، لكن النتيجة الخضراء تعني اكتمال التشغيل فقط، لا خلو المشروع من الاقتراحات، لأن `failOnDryRunResults=false`.

كذلك لم يكن `DependencyList.validateResolvable=true` مفعّلًا، لذا لا يصح اعتبار عمود `resolutionFailure` الفارغ دليلًا بأن كل اعتماد نُزّل أو حُلّ بنجاح.

## مطابقة الوثائق الرسمية

- إصدار Maven plugin الرسمي المدرج: `rewrite-maven-plugin:6.50.0`.
- جدول OpenRewrite يذكر `rewrite-recipe-bom:3.41.0)، مع إصدارات Apache 2.0 لحزم Java dependencies وJackson وNetty وOpenAPI وDocker.
- تنص الوثائق على أن Maven لا يدير إصدارات plugin أو plugin dependencies عبر BOM؛ تُثبّت تلك الإصدارات يدويًا وتُطابق مع BOM الرسمي.
- `rewrite-spring:6.41.0) مصنف Moderne Source Available، ولذلك لم يُضمّن في مجموعة التنزيل المجاني.

## الإصلاحات

1. فصل جرد الاعتماديات عن توصيات التعديل.
2. تفعيل `scope=TestRuntime`, `includeTransitive=true`, `validateResolvable=true`.
3. نقل AutoFormat وRemoveUnusedImports وMaven BestPractices إلى وضع يدوي.
4. توسيع الكتالوج بالحزم Apache ذات الصلة ومواءمة الإصدارات وفق BOM.
5. إظهار ملخص Markdown في GitHub Actions وداخل artifact.

## المراجع الرسمية

- [جدول الإصدارات والتراخيص](https://docs.openrewrite.org/reference/latest-versions-of-every-openrewrite-module)
- [Quickstart والمصادقة وقيود BOM](https://docs.openrewrite.org/running-recipes/getting-started)
- [Maven plugin وdryRun](https://docs.openrewrite.org/reference/rewrite-maven-plugin)
- [DependencyList وvalidateResolvable](https://docs.openrewrite.org/recipes/java/dependencies/dependencylist)
- [Maven BestPractices](https://docs.openrewrite.org/recipes/maven/bestpractices)
- [OpenRewrite licensing](https://docs.openrewrite.org/licensing/openrewrite-licensing)
