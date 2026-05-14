# Railway Deployment Reference — app-java-v3

> **⚠️ هذا التوثيق للمرجعية فقط — لا يُنفذ شيء بدون نقاش وموافقة ومرجع رسمي**
> 
> تاريخ التوثيق: 2026-05-01
> نقطة الاستعادة: `ed57d15` (2026-04-28 03:06:45)
> حالة الباك اند الحالية: نظيفة — مطابقة لـ `ed57d15`

---

## 1. الخلفية

في 30 أبريل 2026، تم نشر الباك اند على Railway. واجهت عملية النشر سلسلة من المشاكل التشغيلية التي تم حلها بـ 20 كوميت متتالي (من `f4f90f5` إلى `0db439f`). كل هذه الكوميتات تم التراجع عنها في 1 مايو لأنها احتوت تعديلات غير مصرح بها على ملفات أمنية وتكوينية.

---

## 2. المشاكل التي واجهتنا بالترتيب الزمني

### 2.1 البناء فشل — maven-wrapper.jar محجوب

| البند | التفصيل |
|-------|---------|
| **الكوميت** | `f4f90f5` |
| **المشكلة** | `.dockerignore` يحتوي على `.mvn/wrapper/maven-wrapper.jar` مما يمنع الملف من الوصول لصورة Docker |
| **الحل المطبق** | حُذف السطر من `.dockerignore` |
| **ملاحظة** | يجب مراجعة: هل `.dockerignore` يستخدم لـ Docker أم لـ Nixpacks؟ كل بناء يتعامل معه بشكل مختلف |

### 2.2 متغيرات البيئة في application-prod.yml

| البند | التفصيل |
|-------|---------|
| **الكوميت** | `7b283c3` ثم تراجع في `363dde4` |
| **المشكلة** | أول محاولة غيّرت المتغيرات بالكامل لأسماء Railway (DATABASE_URL, PGUSER, PGPASSWORD) وغيّرت Quartz لـ memory و Redis لـ simple |
| **الحل المطبق** | تُراجع بالكامل لأنه ألغى خصائص الإنتاج |
| **الملاحظة الصحيحة** | application-prod.yml يحتاج تعديلات محددة ومعتمدة، لا إعادة كتابة كاملة |

### 2.3 railway.toml — تاريخ من 5 محاولات

| # | الكوميت | المشكلة | ما تم تجربته |
|---|---------|---------|-------------|
| 1 | `35a1ff6` | إنشاء ملف أولي | `[start] cmd = "java -jar marketplace-app/target/*.jar"` — لا يعالج PORT |
| 2 | `965befc` | PORT غير ممرر | `sh -c "java -Dserver.port=$PORT ..."` + healthcheck على `/actuator/health` |
| 3 | `ae860e9` | صيغة خاطئة | استخدم `[deploy] startCommand` بدل `[start] cmd` — Railway لم يتعرف |
| 4 | `1a9058f` | تراجع كامل | رجع للنسخة الأولية بدون PORT |
| 5 | `543c5c1` | healthcheck يفشل | غيّر المسار لـ `/actuator/health/liveness` |
| 6 | `0db439f` | النسخة النهائية | `[start] cmd = "java -jar ... --server.port=$PORT"` |

**الصيغة الصحيحة لـ railway.toml (التي عملت أخيراً):**
```toml
[build]
builder = "NIXPACKS"

[phases.build]
cmd = "chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-app -am"

[deploy]
healthcheckPath = "/actuator/health/liveness"
healthcheckTimeout = 300

[start]
cmd = "java -jar marketplace-app/target/*.jar --server.port=$PORT"
```

### 2.4 Railway PORT — 4 محاولات

| # | الكوميت | الطريقة | النتيجة |
|---|---------|---------|---------|
| 1 | `965befc` | `-Dserver.port=$PORT` داخل railway.toml | فشل — ربما مشكلة اقتباس |
| 2 | `cd30649` | `SERVER_PORT=$PORT` كمتغير بيئة | فشل — لم يُقرأ بشكل صحيح |
| 3 | `05f5999` | `SERVER_PORT` مرة أخرى | فشل أيضاً |
| 4 | `3ad2f2f` | `--server.port` كـ CLI arg | نجح |

**ما نعرفه عن Railway PORT:**
- Railway يوفر متغير `PORT` بقيمة ديناميكية (ليس دائماً 8080)
- Spring Boot يقبل `--server.port` كـ CLI argument وهذا الأضمن
- `SERVER_PORT` كمتغير بيئة يعمله Spring Boot لكنه لم يعمل في سياق railway.toml
- الصيغة النهائية: `--server.port=$PORT` داخل sh -c

### 2.5 Dockerfile — PORT handling

| الكوميت | التغيير | السبب |
|---------|---------|-------|
| `ff012ec` | إضافة `SERVER_PORT=${PORT:-8080}` في ENTRYPOINT + `LOG_FILE=/tmp/application.log` | معالجة PORT + إصلاح logback |
| `9ff2581` | إزالة EXPOSE 8080، إضافة debug logging | محاولة فهم مشكلة PORT |

**Dockerfile النهائي الذي عمل (كوميت `c9376fc`):**
```dockerfile
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError"
ENV LOG_FILE="/tmp/application.log"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "echo \"Starting on PORT=${PORT:-8080}\" && SERVER_PORT=${PORT:-8080} java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### 2.6 Metaspace OOM

| # | الكوميت | القيمة | السبب |
|---|---------|--------|-------|
| 1 | `05c6ded` | `MaxRAMPercentage=60.0 -XX:MaxMetaspaceSize=128m` | تقليل RAM لـ 60% + 128m Metaspace |
| 2 | `c9376fc` | `MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=256m` | Metaspace 128m لم يكفي — OOM |

**المشكلة:** التطبيق يحتاج Metaspace أكبر من الافتراضي بسبب عدد الكلاسات المحملة (Spring Boot + Authorization Server + Quartz + Modulith).

### 2.7 Quartz isClustered مع RAMJobStore

| البند | التفصيل |
|-------|---------|
| **الكوميت** | `cd40edf` |
| **المشكلة** | `isClustered: true` غير متوافق مع `RAMJobStore` (الافتراضي عندما لا يكون Quartz JDBC مُعدّ بالكامل) |
| **الحل المطبق** | حُذف `isClustered: true` بالكامل من application-prod.yml |
| **ملاحظة مهمة** | هذا الحل غيّر سلوك الإنتاج — يجب أن يُناقش: هل نستخدم JDBC JobStore أم RAM؟ وإذا JDBC فـ isClustered مطلوب |

### 2.8 Health Check يفشل

| # | الكوميت | المشكلة | الحل |
|---|---------|---------|------|
| 1 | `bd1dbfb` | JWT keystore env vars بدون default → فشل التشغيل | أُضيف `:` كـ empty default |
| 2 | `cd40edf` | Redis غير متاح → readiness check فشل | حُذف redis من readiness group |
| 3 | `0db439f` | `/actuator/health` لا يطابق `/actuator/health/liveness` | غيّر SecurityConfig لـ `/actuator/health/**` |
| 4 | `0db439f` | Health group يفشل بسبب missing contributors | أُضيف `validate-group-membership: false` |

---

## 3. ملخص المشاكل الحقيقية (تحتاج حلاً صحيحاً)

### 3.1 مشاكل تشغيلية — تحتاج حل صحيح معتمد

| # | المشكلة | السبب الجذري | ما يجب مناقشته |
|---|---------|-------------|---------------|
| 1 | **PORT ديناميكي** | Railway يعين PORT عشوائياً | كيف نمرره بشكل صحيح لـ Spring Boot؟ |
| 2 | **Metaspace OOM** | التطبيق يحتاج > 128m Metaspace | القيمة الصحيحة لبيئة الإنتاج |
| 3 | **LOG_FILE غير موجود** | logback يحاول الكتابة في `/var/log/marketplace/` | معالجة صحيحة في الحاوية |
| 4 | **Health check path** | Railway يحتاج `/actuator/health/liveness` | SecurityConfig يجب أن يسمح بهذا المسار |
| 5 | **maven-wrapper.jar** | .dockerignore يحجبه | هل نزيله من .dockerignore أم نستخدم بناء مختلف؟ |

### 3.2 مشاكل أمنية/تكوينية — تحتاج نقاش قبل أي تعديل

| # | المشكلة | ما حصل بالأمس | ما يجب فعله |
|---|---------|-------------|------------|
| 1 | **SecurityConfig** | غيّر `/actuator/health` لـ `/**` بدون نقاش | يجب أن يكون ضمن خطة إعادة البناء المعتمدة (4 chains) |
| 2 | **JWT keystore defaults** | أضاف `:` فارغ | يُغيّر سلوك الفشل الآمن — يجب مناقشته |
| 3 | **isClustered** | حُذف بالكامل | هل نستخدم JDBC JobStore أم RAM؟ |
| 4 | **Redis في readiness** | حُذف | هل Redis متاح في Railway؟ إن نعم يجب أن يبقى |
| 5 | **validate-group-membership** | أُضيف `false` | يُخفي مشاكل لا يحلها |

---

## 4. ما يعمل في Railway (النتيجة النهائية قبل التراجع)

عند الكوميت `0db439f`، كان التطبيق يعمل على Railway بالتكوين التالي:

### 4.1 railway.toml
```toml
[build]
builder = "NIXPACKS"

[phases.build]
cmd = "chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-app -am"

[deploy]
healthcheckPath = "/actuator/health/liveness"
healthcheckTimeout = 300

[start]
cmd = "java -jar marketplace-app/target/*.jar --server.port=$PORT"
```

### 4.2 Dockerfile (التغييرات فقط مقارنة بالأصلي)
```diff
- EXPOSE 8080 (كان قبل JAVA_OPTS)
+ EXPOSE 8080 (نقل بعد LOG_FILE)

- ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
+ ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError"

+ ENV LOG_FILE="/tmp/application.log"

- ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
+ ENTRYPOINT ["sh", "-c", "echo \"Starting on PORT=${PORT:-8080}\" && SERVER_PORT=${PORT:-8080} java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### 4.3 application-prod.yml (التغييرات فقط)
```diff
- isClustered: true (محذوف)
+ validate-group-membership: false (مضاف)
- include: db,redis,diskSpace
+ include: db,diskSpace
- ${JWT_KEYSTORE_PATH}
+ ${JWT_KEYSTORE_PATH:}
(ونفس الشيء لـ 3 خصائص أخرى)
```

### 4.4 SecurityConfig.java (تغيير واحد)
```diff
- .requestMatchers("/actuator/health", "/actuator/info").permitAll()
+ .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
```

---

## 5. الدروس المستفادة

1. **لا تُعدّل بدون إذن** — كل تعديل في الباك اند يحتاج موافقة مسبقة
2. **لا تُعدّل بدون مرجع رسمي** — كل تغيير يجب أن يستند لتوثيق معتمد
3. **لا تبني حول المشاكل** — ابني بشكل صحيح من الأساس
4. **افصل بين أنواع التعديلات** — أمنية / تشغيلية / بنية تحتية لكل نوع سير عمل مختلف
5. **سجّل كل شيء** — 20 كوميت بـ "fix:" بدون سياق = لا يمكن مراجعتها
6. **اختبر محلياً أولاً** — مشاكل PORT و Metaspace يمكن اكتشافها محلياً
7. **كل patch يُنشئ مشكلة جديدة** — طريقة التجربة والخطأ أنتجت 20 كوميت بدل 3-4 مدروسة

---

## 6. الخطة القادمة (بعد البناء السليم)

عندما نعود لنشر Railway:

1. **أولاً**: إكمال خطة إعادة بناء SecurityConfig (4 chains) بشكل صحيح ومعتمد
2. **ثانياً**: مناقشة كل تعديل تشغيلي بشكل منفصل:
   - PORT: هل نستخدم Dockerfile أم railway.toml؟
   - Metaspace: القيمة الصحيحة
   - LOG_FILE: المعالجة الصحيحة في الحاوية
   - Health check: المسارات الصحيحة ضمن SecurityConfig الجديد
3. **ثالثاً**: مناقشة تعديلات application-prod.yml كل واحد على حدة
4. **رابعاً**: إنشاء railway.toml بشكل صحيح من البداية
5. **خامساً**: نشر مرة واحدة — بناء صحيح لا 20 محاولة
