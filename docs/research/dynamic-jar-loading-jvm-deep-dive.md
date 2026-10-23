# تحميل JAR ديناميكيًا داخل JVM — تدقيق معمّق موثق

**تاريخ التحقق:** 2026-10-23  
**الحالة:** بحث هندسي وأدلة مصدرية؛ لا يمثل اعتمادًا نهائيًا ولا يغيّر التشغيل  
**المستودع المقاس:** waelhe/app-java-v3، main عند ea0031a97db4452748a47582d3fed2c4d3ec1d52  
**الملفات الحاكمة:** AGENTS.md، SYSTEM.md، docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md، docs/governance/feature-flags.md  
**حدود هذا البحث:** وثائق ومصادر ومستودعات وقضايا عامة؛ لم تُضف أي مكتبة ولم يُبنَ نموذج أولي ولم يُجرَ قياس وقت تشغيل.

## 1. تعريف المسألة بدقة

«تحميل JAR داخل JVM» ليس مطلبًا واحدًا؛ يجب التفريق بين:

1. **تحميل صنف عند التشغيل:** استخدام ClassLoader أو ServiceLoader لاكتشاف صنف/تنفيذ واجهة. هذه قدرة منخفضة المستوى.
2. **إضافة ديناميكية حقيقية:** تثبيت حزمة ذات إصدار وتبعيات، حل التبعيات، تحميل الأصناف في مساحة أسماء منفصلة، تشغيلها وإيقافها وتحديثها وإزالتها أثناء عمل التطبيق.
3. **تكامل Spring ديناميكي:** إضافة وإزالة Beans وControllers وListeners وScheduled Tasks وRepositories أثناء حياة ApplicationContext.
4. **عزل أمني:** منع الشيفرة المضافة من قراءة أسرار المضيف أو قواعد بياناته أو الملفات أو الشبكة دون تفويض.
5. **نشر مستقل:** تشغيل امتداد خارج JVM يتكامل عبر عقد شبكة ويملك دورة حياة مستقلة.

نجاح البند 2 لا يثبت تلقائيًا نجاح البند 3، ولا يحقق البند 4. هذا الفصل هو أساس القرار.

## 2. ما تقدمه Java نفسها وما لا تقدمه

### 2.1 ClassLoader وURLClassLoader

توثيق Java SE 25 يحدد أن URLClassLoader يستطيع تحميل أصناف وموارد من مسارات تشير إلى JARs ومجلدات. لكن دالة close() لها ضمان أضيق بكثير: تمنع المحمّل من تحميل أصناف وموارد جديدة من مساراته، وتغلق ملفات JAR التي فتحها؛ الأصناف والموارد المحمّلة سابقًا تظل قابلة للوصول. لا تعني close() أن الأصناف قد أزيلت من JVM.  
المصدر: [Java SE 25 — URLClassLoader](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/URLClassLoader.html).

### 2.2 إفراغ الأصناف من الذاكرة غير فوري ولا مضمون بموعد

تنص مواصفة لغة Java، الفصل 12.7، على أن الصنف يمكن إزالته فقط عندما يمكن استعادة مُحمّل الصنف الذي عرّفه بواسطة جامع القمامة؛ كما أن توقيت إلغاء التحميل ليس ضمانًا تعتمد عليه دلالات البرنامج.  
المصدر: [Java Language Specification — Unloading of Classes and Interfaces](https://docs.oracle.com/javase/specs/jls/se25/html/jls-12.html#jls-12.7).

لذلك ينبغي الفصل بين هذه النتائج المختلفة:

- **Disabled:** يمنع النظام استدعاء الامتداد.
- **Stopped:** استدعى مدير الإضافات دورة التوقف.
- **Unloaded:** أزال مدير الإضافات ارتباطه التشغيلي ومحمل الإضافة من سجلاته.
- **ClassLoader reclaimed / classes unloaded:** لم تعد هناك مراجع تمنع جمع المحمّل؛ توقيت الجمع ليس فوريًا ولا مضمونًا.

لا يصح عرض «حذف الإضافة» في لوحة الإدارة وكأنه يضمن إزالة جميع الأصناف من الذاكرة فورًا.

### 2.3 ModuleLayer ليست نظام إضافات جاهزًا

يوفر JPMS واجهة ModuleLayer لتكوين طبقات وحدات وربطها بمحمل واحد أو عدة محمّلات. لكنه لا يوفر بحد ذاته مخزن تثبيت دائمًا، أو مدير إصدارات إضافات، أو واجهة إدارية، أو عقد دورة حياة، أو نظام تحديث/رجوع، أو حد أمني للشيفرة.  
المصدر: [Java SE 25 — ModuleLayer](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ModuleLayer.html).

الاستنتاج: بناء مدير إضافات فوق ClassLoader أو ModuleLayer يعني أن على التطبيق امتلاك المزيد من الأجزاء التشغيلية بنفسه. هذه الأدوات بدائية بناء، وليست بديلًا مباشرًا عن PF4J أو OSGi.

## 3. PF4J: ما الذي يقدمه ناضجًا؟

PF4J هو أقوى مرشح خفيف وجدته لمطلب إضافات Java محملة من JAR داخل عملية واحدة. يصف المشروع نفسه بأنه إطار إضافات صغير مع اعتماديات قليلة، ويقدم PluginManager ونقاط امتداد وPluginClassLoader منفصلًا لكل إضافة ودورة حياة start/stop/delete.  
المصادر: [مستودع PF4J](https://github.com/pf4j/pf4j)، [الوثائق الرسمية: Plugins](https://pf4j.org/doc/plugins.html)، [Getting started](https://pf4j.org/doc/getting-started.html)، [Class loading](https://pf4j.org/doc/class-loading.html)، [Plugin lifecycle](https://pf4j.org/doc/plugin-lifecycle.html).

### 3.1 الأدلة على نشاط المكتبة واستخدامها

- صفحة Maven Central تعرض PF4J 3.16.0 منشورًا بتاريخ 2026-09-20؛ هذا هو رقم النسخة الأساسية الظاهر وقت الفحص، وليس تصريحًا بأنه متوافق تلقائيًا مع كل بيئات Spring.  
  المصدر: [Maven Central — PF4J versions](https://central.sonatype.com/artifact/org.pf4j/pf4j/versions).
- سجل الإصدارات يوثق إصلاح مشكلة Zip Path Traversal في الإصدار 3.14.1؛ لذلك فحص الأرشيفات ومسارات الاستخراج والتحديثات الأمنية متطلبات فعلية، لا إضافات شكلية.  
  المصدر: [PF4J releases](https://github.com/pf4j/pf4j/releases).
- يذكر المستودع الرسمي استخدام PF4J في مشاريع مثل Netflix Spinnaker وFacebook Buck وApache Tika وDremio وMuleSoft Anypoint Code Builder وApache BifroMQ وAppsmith وNextflow وReportPortal. ويمكن مراجعة دليل Spinnaker الفعلي، أو استعمال مسار إضافات Buck من المصدر.  
  المصادر: [قائمة المشاريع التي تستخدم PF4J](https://github.com/pf4j/pf4j/blob/master/README.md)، [Spinnaker plugin development/testing guide](https://spinnaker.github.io/guides/developer/plugins/testing/deck-plugin/)، [Facebook Buck — plugin directory reference](https://github.com/facebook/buck/blob/main/programs/buck_repo.py).

هذه أدلة على قيمة PF4J الأساسي في امتدادات Java. **لا تثبت أن تكامل PF4J-Spring مناسب تلقائيًا لـSpring Boot 4، ولا تثبت أن إزالة Beans وموارد Spring ديناميكيًا محسومة.**

### 3.2 تعريف الإضافة وحل التبعيات

تقرأ PF4J بيانات الإضافة من Manifest الخاص بـJAR، ومن حقولها الموثقة: معرف فريد، إصدار، صنف الإضافة الاختياري، إصدار التطبيق الأدنى المطلوب، التبعيات، الوصف، المزود والرخصة. ويمكن تحديد تبعيات إلزامية أو اختيارية وقيود إصدارات لها.  
المصادر: [Plugin descriptor](https://pf4j.org/doc/plugin-descriptor.html)، [Plugins and dependencies](https://pf4j.org/doc/plugins.html).

وهذه نقطة قوة: لا يحتاج النظام إلى تخمين هوية JAR من اسمه. لكن بيانات Manifest تصف الحزمة ولا تثبت سلامة ناشرها، ولا تنفذ فحصًا شاملًا للأمان أو توافق Spring أو توافق قاعدة البيانات.

### 3.3 فصل محمل الأصناف يحمل معه مخاطر توافق

يستخدم PF4J افتراضيًا استراتيجية Parent-Last لتحميل أصناف الإضافة من حزمها قبل الرجوع إلى الأب. يستطيع هذا تقليل تضارب مكتبات الإضافات، لكنه قد ينتج نسختين مختلفتين من اسم الصنف نفسه في محملين مختلفين. في Java لا تتطابق هويتان للصنف فقط لأن الاسم المؤهل متساوٍ؛ المحمّل المُعرِّف جزء من الهوية.

لذلك تظهر احتمالات LinkageError أو ClassCastException حين تضمّن الإضافة نسختها الخاصة من واجهات المضيف أو مكتبات Spring التي يعرّفها المضيف بمحمّل آخر. ويجب أن تكون عقود التبادل بين المضيف والإضافة ذات إصدار ثابت وأن تبقى واجهاتها المشتركة في محمّل المضيف.

## 4. عائق محدد في تكامل PF4J-Spring مع مشروعنا

ينبغي التفريق بين PF4J الأساسي وبين مشروع الربط Spring integration.

### 4.1 حالة الإصدارات والتوافق في تاريخ الفحص

- النسخة المنشورة الظاهرة لـPF4J-Spring في Maven Central هي 0.10.0.  
  المصدر: [Maven Central — PF4J-Spring](https://central.sonatype.com/artifact/org.pf4j/pf4j-spring).
- README الرسمي في مستودع PF4J-Spring يصف المشروع صراحةً بأنه “proof of concept” لتكامل PF4J مع Spring.  
  المصدر: [pf4j/pf4j-spring README](https://github.com/pf4j/pf4j-spring).
- إعدادات فرع المصدر الحالي وقت التدقيق تستخدم PF4J 3.13.0 وSpring Framework 6.2.2 وJava 17، على الرغم من أن PF4J الأساسي تقدم منذ ذلك الحين إلى 3.16.0.
- الأهم: طلب دعم Spring Framework 7، الذي يأتي ضمن Spring Boot 4.x، مفتوح في [pf4j-spring #94](https://github.com/pf4j/pf4j-spring/issues/94)، بتاريخ 2026-06-04. لا تظهر بيانات القضية المفتوحة دليلًا على اكتمال الدعم.

هذا ليس دليلًا على استحالة كتابة تكامل مخصص، لكنه دليل كافٍ على **عدم اعتماد PF4J-Spring مباشرة في مشروع Spring Boot 4.1.1 قبل إثبات توافق واختبارات خاصة**.

### 4.2 تجارب المجتمع تكشف حدودًا عملية محددة

هذه أمثلة من المستودع الرسمي نفسه؛ الحالة المفتوحة للقضية تعني أن النقاش ظاهر كمفتوح وقت الفحص، لا أن كل سيناريو مصاب حتمًا:

- [#94 — Support Spring Framework 7](https://github.com/pf4j/pf4j-spring/issues/94): طلب توافق مباشر مع Spring 7.
- [#84 — Dynamic load/unload using pf4j-spring](https://github.com/pf4j/pf4j-spring/issues/84): عرض مطور تسجيل Controllers من ApplicationContext الإضافة في RequestMappingHandlerMapping للمضيف، ثم محاولة إزالتها مع Beans ومحمل الأصناف. رد المساهم أوضح أن PF4J يتولى إغلاق المحمّل، لكن استمرار Bean بعد إيقاف الإضافة يبدو مشكلة تكامل مع Spring. لا تخلط بين الإزالة من مدير PF4J وإزالة جميع مراجع Spring.
- [#86 — Additional Spring dependencies in plugin](https://github.com/pf4j/pf4j-spring/issues/86): أبلغ مستخدم عن LinkageError بسبب اختلاف كائنات Class الخاصة بـApplicationContext بين PluginClassLoader ومحمّل التطبيق عند تضمين تبعيات Spring داخل الإضافة.
- [#62 — Updating injected List after unloading](https://github.com/pf4j/pf4j-spring/issues/62): يناقش استمرار إتاحة Beans كانت محقونة في قائمة Spring بعد حذف الإضافات.
- [#65 — JPA in plugin](https://github.com/pf4j/pf4j-spring/issues/65): يطلب مثالًا متكاملًا يوضح Controller وJPA داخل إضافة، ويبلغ عن سلوك غير صحيح لاستعلامات بياناته. هذه قضية قديمة ولا تكفي بمفردها للحكم على الإصدارات الحالية، لكنها تُظهر أن JPA داخل الإضافة ليس نتيجة تلقائية لمجرد تهيئة SpringPlugin.
- [#48 — Spring Boot executable JAR as plugin](https://github.com/pf4j/pf4j-spring/issues/48): يناقش أن ملف Spring Boot التنفيذي يحزم الأصناف تحت BOOT-INF/classes والمكتبات كـnested JARs، فلا يتصرف كـJAR إضافة عادي يتوقعه محمّل PF4J.

الاستنتاج العملي: لا تبنوا الحل على «سأجعل كل إضافة حزمة Spring Boot كاملة ثم أضيف Beans وControllers إلى سياق التطبيق». هذا بالضبط المسار الذي تتكرر فيه مسائل المحمّلات، والتبعيات، وتسجيل/إلغاء تسجيل الـBeans وControllers.

## 5. تغليف JAR ليس تفصيلًا ثانويًا

توثيق Spring Boot يوضح أن Java لا تملك طريقة قياسية لتحميل JARs المتداخلة داخل JAR آخر. يستخدم Boot تنسيقًا خاصًا للملف التنفيذي ومحمّل Spring Boot لقراءة هذا الهيكل.  
المصدر: [Spring Boot — Nested JARs](https://docs.spring.io/spring-boot/specification/executable-jar/nested-jars.html)، [Executable JAR restrictions](https://docs.spring.io/spring-boot/specification/executable-jar/restrictions.html).

لذلك إذا اختير PF4J، ينبغي أن تكون حزم الإضافات بصيغة متوافقة مع PF4J (JAR عادي ببيانات Manifest، مع آلية واضحة للتبعيات)، لا افتراض أن أي ملف JAR تنفيذي مبني بواسطة spring-boot-maven-plugin يصلح تلقائيًا كإضافة.

## 6. البديل الأثقل والأكثر اكتمالًا: OSGi / Apache Felix

OSGi ليس مجرد محمّل أصناف؛ إنه معيار وحدات وBundles يعرّف طبقة دورة حياة تشمل التثبيت والبدء والتوقف والتحديث وإلغاء التثبيت والمراقبة، ويحدد حالات الحزمة وواجهات الخدمات والاستيراد/التصدير والتعامل مع العلاقات بين الحزم.  
المصادر: [OSGi Core 8 — Lifecycle Layer](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html)، [OSGi Core 8 — Module Layer/Class Loading](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html).

وتحدد مواصفة OSGi أن التثبيت يجب أن يكون ذريًا ودائمًا، وأن Stop على Bundle يجب أن يحرر الموارد ويوقف الخيوط المرتبطة به، وأن الإطار يزيل تسجيل خدمات ومستمعي الإطار عند توقف الحزمة. كما يتعامل المعيار صراحةً مع التحديث ووجود إصدارات ما زالت مستخدمة من حزم أخرى. هذه متطلبات أوسع من مجرد PluginManager صغير.

يوفر [Apache Felix Framework](https://felix.apache.org/documentation/subprojects/apache-felix-framework/apache-felix-framework-usage-documentation.html) تنفيذًا لهذه المنظومة ويعرض تثبيت Bundles وإدارتها وتخزين حالتها عبر عمليات التشغيل.

لكن OSGi يضيف عالمًا معماريًا كاملًا: صيغة Bundle، وآلية حل تبعيات، وسجل خدمات، وقواعد Package Wiring، وسياسات دورة حياة. تكامله مع Spring Boot 4.1.1 لا يصبح جاهزًا بمجرد تضمين Felix؛ يلزم نموذج إثبات يوضح كيف تتفاعل خدمات OSGi مع وحدات Spring الحالية وSecurity وJPA وDGS وFlyway. أوصي بتقييمه فقط إذا كان لدينا احتياج فعلي لنظام وحدات ديناميكي غني، لا لمجرد تبديل ميزات موجودة.

## 7. الأمن: JVM واحدة تعني نفس حدود الثقة

### 7.1 لا تعتمد على Security Manager

تنص وثائق Oracle الرسمية لـJava SE 25 على أن Security Manager معطّل نهائيًا منذ JDK 24؛ لا يمكن تفعيله عند بدء JVM أو تثبيت مدير مخصص وقت التشغيل. وتوصي الوثيقة باستخدام آليات خارج JVM، مثل الحاويات وقيود نظام التشغيل، حيث تكون هناك حاجة لحدود أمان.  
المصدر: [Oracle Java SE 25 Security Developer’s Guide](https://docs.oracle.com/en/java/javase/25/security/security-developer-guide.pdf).

### 7.2 PluginClassLoader ليس صندوقًا أمنيًا

إذا حُمّلت إضافة غير موثوقة في JVM المنصة، فإنها تعمل بامتيازات عملية المنصة نفسها؛ محمل الأصناف يفصل مساحات أسماء الأصناف ولا يمنع بحد ذاته الوصول إلى الملفات أو الشبكة أو الأسرار أو الاتصالات وقاعدة البيانات التي يستطيع التطبيق الوصول إليها.

لذلك:
- **إضافة داخلية موثوقة وموقعة** قد تكون مرشحة لـin-process PF4J بعد إثبات التوافق.
- **إضافة يرفعها بائع أو طرف ثالث** لا ينبغي تشغيلها في JVM الأساسية اعتمادًا على ClassLoader فقط. الأنسب عملية مستقلة/حاوية بعقد API، هوية خدمة مستقلة، أسرار محدودة، وقيود موارد وشبكة.
- التوقيع والـchecksum والـSBOM وفحص الثغرات تثبت المصدر والتكامل وتساعد على فحص المخاطر؛ لكنها لا تجعل شيفرة الطرف الثالث آمنة تلقائيًا.

## 8. ما الذي يجعل الإيقاف والتحديث موثوقين؟

قبل تشغيل أي إضافة داخل JVM، لا يكفي نجاح start() وstop(). يجب تعريف معنى الإيقاف وامتلاك دورة تنظيف قابلة للاختبار.

عند الإيقاف/الإزالة يجب التحقق من:
- رفض استدعاءات جديدة للإضافة وانتظار العمليات الجارية وفق مهلة محددة.
- تنفيذ stop hook، وإيقاف المهام والخيوط ومجموعات التنفيذ التي أنشأتها الإضافة.
- إلغاء تسجيل listeners وevent subscribers وMBeans وJDBC drivers وأي handlers أو callbacks.
- إزالة Controllers/Routes وBeans من كل مكان سُجلت فيه، وليس من PluginManager فقط.
- إعادة Context ClassLoader للخيوط التي غيّرته، والتخلص من ThreadLocals ومراجع Static وCaches وReflection caches التي يملكها التطبيق.
- تحرير اتصالات الشبكة والملفات وJDBC resources، وإغلاق أي URLClassLoader من خلال مسار مدير الإضافات الصحيح.
- الحفاظ على البيانات وعدم حذفها كأثر جانبي للتعطيل.
- بعد التحديث، عدم إبقاء مراجع إلى أنواع الإصدار القديم أو الجمع بين نسخ متضاربة من الواجهة المشتركة.

لا يستطيع الإطار ضمان تنظيف كود الإضافة الذي لا يلتزم بدورة الحياة، ولا يستطيع بأمان إيقاف Thread تعسفيًّا عالقًا داخل كود غير متعاون. إذا كان مطلب المنصة هو فرض إنهاء قسري موثوق لإضافة متعطلة، فهذا سبب أقوى لاستخدام عملية منفصلة.

## 9. قواعد خاصة ببنية مشروعنا

الحالة المقيسة على main هي تطبيق واحد متعدد وحدات Maven وبـSpring Context واحد، مع Spring Modulith لحدود الوحدات والأحداث، وDGS نموذج GraphQL المعتمد. كما يحتوي المشروع على FeatureGatePort وmarketplace-console لإدارة بعض القدرات الموجودة عند وقت الطلب.

بناءً على ذلك:

1. لا نستخدم تحميل JAR لحاجة يمكن تلبيتها بعلم FeatureGate لميزة موجودة في الإصدار الحالي.
2. لا نسمح لإضافة ديناميكية بتعريف مالك ثانٍ لبيانات الطلبات أو الدفع أو الهوية أو الصلاحيات التي تملكها وحدات المنصة حاليًا.
3. لا نفترض أن إضافة JAR تضيف تلقائيًا جداول JPA أو Flyway migrations إلى مخطط الإنتاج. لا يوجد في هذا البحث دليل على أن إطار الإضافات الحالي يكتشف ترحيلات إضافات جديدة ويشغلها بأمان أثناء عمل المنصة؛ الترحيلات وتعديل مخطط البيانات تحتاج مسار إصدار وقرارًا مستقلًا.
4. لا نعدّل DGS SDL أو OpenAPI أو Routes تلقائيًا لمجرد أن الإضافة تحتوي Controller أو Resolver. عقود العملاء المثبتة واختبارات توافقها تحكم التغييرات، ويجب أن يكون تمديدها مقصودًا وصريحًا.
5. إذا كان المطلوب هو تكامل خارجي ينشره طرف آخر، نفضل عقد API/Webhooks وعملية مستقلة بدل منحه صلاحيات JVM المضيف.

## 10. مقارنة الخيارات وفق الأدلة

| الخيار | نقاط القوة المثبتة | المخاطر/الحدود | الملاءمة المبدئية |
|---|---|---|---|
| ClassLoader / JPMS مباشرة | قدرات Java القياسية لتحميل الأصناف والطبقات | لا مدير دورة حياة/تبعيات/سجل تثبيت؛ تفاصيل التنظيف والتوافق على التطبيق | غير كافٍ وحده |
| PF4J الأساسي 3.16.0 | خفيف؛ PluginManager؛ Manifest؛ تبعيات وإصدارات؛ دورة حياة؛ محمّل مستقل؛ استخدامات حقيقية في أدوات ومنصات Java | ليس حد أمان؛ إفراغ الأصناف يعتمد على انعدام المراجع؛ التوافق مع Spring مسؤولية إضافية | مرشح لـtrusted POJO plugins بعد PoC |
| PF4J-Spring 0.10.0 | تجربة تكامل لتقديم امتدادات PF4J كـSpring Beans | README يصفه PoC؛ طلب Spring 7 مفتوح؛ قضايا موثقة حول التبعيات والـBeans والإزالة | غير معتمد حاليًا مع Boot 4.1.1 |
| OSGi / Apache Felix | معيار lifecycle كامل للحزم، وحل التبعيات، وسجل خدمات، وسياسات تحديث وإزالة | إطار معماري أثقل؛ يتطلب إثبات تكامل منفصل مع Boot/DGS/JPA/Flyway | يُقيّم فقط عند احتياج ديناميكي واسع |
| عملية/حاوية مستقلة بعقد API | حدود عملية ونشر/إيقاف وتعطيل أقوى؛ تناسب إضافات أطراف ثالثة | عبء نشر وتشغيل ومراقبة واتصال بين العمليات | الأنسب لإضافات غير موثوقة أو سوق إضافات عام |

## 11. توصية محددة للمشروع — مشروطة لا نهائية

1. **القدرات الموجودة في الكود:** استمر باستخدام FeatureGatePort والعناصر الحالية في marketplace-console بعد تدقيق أن كل مسارات الميزة تتبع العلم.
2. **إضافات Java من فريق موثوق تحتاج التحميل داخل JVM فعلًا:** إذا أكد مطلب المنتج أن النشر المستقل غير مناسب، اختبر PF4J الأساسي 3.16.0 منفصلًا. لا تضف PF4J-Spring مباشرة ولا تصمم الإضافة كـSpring Boot executable JAR. اجعل الاختبار الأول امتداد POJO عبر SPI مضيف ثابت، دون Controller/JPA/Schema ديناميكي.
3. **إضافة تحتاج Spring Beans/Controllers/JPA أو تغييرات عميقة في المخطط:** لا تعتبر PF4J-Spring جاهزًا. قارن نموذج OSGi بعملية منفصلة، ثم اعتمد الاختيار على تجربة تكامل وقياس لا على عدد ميزات المكتبة.
4. **إضافات يرفعها بائعون أو أطراف خارجية:** لا تنفذ كودها داخل JVM الإنتاجية الأساسية. اعتمد عملية مستقلة أو حاوية مع API محدود الصلاحيات.
5. **لا تضف أي اعتماد إنتاج إلى POM** قبل اجتياز إثبات توافق مع JDK 25 وSpring Boot 4.1.1، وإثبات إدارة دورة الحياة، ومراجعة أمنية للحزم.

## 12. بوابات إثبات مطلوبة قبل أي اعتماد PF4J in-process

يجب أن يكون نموذج الإثبات مشروعًا تجريبيًا منفصلًا أو فرعًا قصير العمر، لا دمجًا مبكرًا في main. ولا يقبل على أنه ناجح إلا إذا أثبت:

- إقلاع المضيف دون أي JAR إضافي، ثم اكتشاف Plugin عادي ببيانات Manifest.
- رفض ID مكرر، وتبعيات مفقودة، وإصدار يتطلب نسخة منصة أعلى، وحزمة تالفة أو غير موثوقة.
- توصيل نقطة امتداد إلى خدمة المضيف مع عدم تكرار أنواع SPI/Spring عبر محملين.
- منع الاستدعاءات الجديدة بعد التعطيل، والتعامل الصريح مع الاستدعاءات المتزامنة.
- تشغيل/إيقاف/إلغاء تثبيت وتحديث متكرر مع التحقق من الملفات المفتوحة والخيوط والمهام والمستمعين والـBeans والمسارات.
- اختبار دورة متعددة (مثل 100 دورة تركيب/تشغيل/إيقاف/إزالة) ومراقبة الاتجاه في Metaspace ومراجع ClassLoader؛ لا يفرض الاختبار توقيت GC محددًا، لأنه غير مضمون.
- إثبات سلوك الفشل أثناء start/stop، والمهل، وإعادة المحاولة، والرجوع إلى نسخة سابقة.
- توافق مثبت مع Spring Boot 4.1.1 إذا كان أي مكوّن من Spring سيعمل داخل الإضافة؛ الاختبار مع PF4J الأساسي وحده لا يثبت تكامل Spring.
- مراجعة أمنية للحزمة والتبعيات وبروتوكول التثبيت والتوقيع ومصدر الحزمة. ويُرفض اختبار «رفع JAR مجهول وتشغيله» كمسار مقبول للإنتاج.

## 13. فهرس الأدلة الأولية

**Java / Oracle**
- [URLClassLoader — Java SE 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/URLClassLoader.html)
- [JLS 12.7 — Unloading of Classes and Interfaces](https://docs.oracle.com/javase/specs/jls/se26/html/jls-12.html#jls-12.7)
- [ModuleLayer — Java SE 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ModuleLayer.html)
- [Java SE 25 Security Developer’s Guide](https://docs.oracle.com/en/java/javase/25/security/security-developer-guide.pdf)

**PF4J**
- [PF4J core source](https://github.com/pf4j/pf4j)
- [Plugin descriptor](https://pf4j.org/doc/plugin-descriptor.html)
- [Class loading](https://pf4j.org/doc/class-loading.html)
- [Lifecycle](https://pf4j.org/doc/plugin-lifecycle.html)
- [PF4J 3.16.0 release](https://central.sonatype.com/artifact/org.pf4j/pf4j/versions)

**PF4J-Spring / تجارب مجتمعية**
- [PF4J-Spring source and README](https://github.com/pf4j/pf4j-spring)
- [#94 Spring Framework 7](https://github.com/pf4j/pf4j-spring/issues/94)
- [#84 dynamic load/unload and cleanup](https://github.com/pf4j/pf4j-spring/issues/84)
- [#86 duplicate Spring types / LinkageError](https://github.com/pf4j/pf4j-spring/issues/86)
- [#62 injected beans after unload](https://github.com/pf4j/pf4j-spring/issues/62)
- [#65 JPA inside plugin](https://github.com/pf4j/pf4j-spring/issues/65)
- [#48 Spring Boot executable JAR layout](https://github.com/pf4j/pf4j-spring/issues/48)

**OSGi / Apache Felix**
- [OSGi Core 8 lifecycle](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html)
- [OSGi Core 8 module and class loading](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html)
- [Apache Felix Framework usage](https://felix.apache.org/documentation/subprojects/apache-felix-framework/apache-felix-framework-usage-documentation.html)

**حالة الدليل:** الروابط أعلاه مصادر مباشرة قابلة لإعادة الفحص في تاريخ البحث. هذا التقييم لا يدعي تشغيل PoC أو اجتياز اختبارات توافق/أمن لم تجرَ بعد.
