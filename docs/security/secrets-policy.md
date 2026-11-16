# JIRA-SEC-02 — Secrets & Config Hardening Policy

## 1) منع وضع الأسرار داخل Git

- يُمنع منعًا باتًا تخزين أي Secret داخل المستودع (passwords, API keys, private keys, tokens, webhook secrets).
- يشمل المنع كل الملفات النصية والـ YAML والـ `.env` والسكريبتات والاختبارات.
- أي Secret تم اكتشافه في Git history يُعامل كـ incident أمني ويستلزم:
  1. إبطال/تدوير المفتاح فورًا.
  2. تنظيف التاريخ عند الحاجة (`git filter-repo` أو BFG) بعد التنسيق مع الأمن.
  3. توثيق الحادثة وخطوات الاحتواء.

## 2) آلية حقن الأسرار

- المصدر المعتمد للأسرار في التشغيل: **Environment Variables** أو **Secret Manager** (Vault / AWS Secrets Manager / GCP Secret Manager / Azure Key Vault).
- لا تُعرّف قيم افتراضية حساسة (sensitive defaults) في `application*.yml`.
- يجب تمرير الأسرار عبر متغيرات البيئة فقط، مثل:
  - `DB_PASSWORD`
  - `JWT_KEYSTORE_PASSWORD`
  - `JWT_KEY_PASSWORD`
  - `MAIL_PASSWORD`
  - `GOOGLE_CLIENT_SECRET`
- يمنع تسجيل قيمة السر في logs أو رسائل الأخطاء.

## 3) سياسة تدوير المفاتيح

- دورة تدوير دورية إلزامية:
  - مفاتيح الخدمة/التكامل: كل 90 يومًا كحد أقصى.
  - Secrets عالية الحساسية (مفاتيح التوقيع/الدفع): كل 30-60 يومًا أو حسب متطلبات الامتثال.
- تدوير فوري عند:
  - الاشتباه بالتسريب.
  - مغادرة موظف لديه وصول للأسرار.
  - تغيير كبير في البنية أو امتيازات الوصول.
- التدوير يجب أن يكون **بدون توقف خدمة** حيثما أمكن (dual key / overlap window).
- يجب الاحتفاظ بسجل تدقيق Audit Log يوضح: المالك، تاريخ التدوير، الأثر، وحالة الإكمال.

## 4) ضوابط CI لاكتشاف الأسرار

- فحص secrets إلزامي في CI عبر `gitleaks`.
- يفشل الـ pipeline عند أي نتيجة بدرجة **high** أو **critical**.
- نتائج severity الأقل تُراجع قبل الدمج عند الحاجة.

## 5) Baseline إعدادات آمنة للإنتاج

- `management.server.port` مخصص ومنفصل عن منفذ التطبيق العام.
- حصر actuator exposure على الحد الأدنى (`health`, `info`, `prometheus`).
- تعطيل إظهار تفاصيل health الحساسة (`show-details: never`).
- تفعيل إخفاء/تنقيح البيانات الحساسة في السجلات عبر masking patterns.

## 6) آلية المراجعة

- أي PR يغير إعدادات التشغيل أو المصادقة أو بيانات الاتصال يجب أن يمر عبر checklist أمني في قالب PR.
- لا يتم الدمج قبل اجتياز فحص secrets في CI.
