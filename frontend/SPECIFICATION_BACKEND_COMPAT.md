# 📋 مواصفات الفرونت إند المتوافقة مع الباك إند — مشروع نبض (Nabd)

> هذه الوثيقة تُعدِّل المواصفات العامة لتتوافق مع الباك إند الحالي في هذا المستودع (Java/Spring Boot).

## 1) 🏗️ الهوية الأساسية

| البيان | القيمة |
|--------|--------|
| **الاسم** | نبض (Nabd) |
| **الإصدار** | 0.2.0 |
| **الوصف** | نبض الضاحية وقدسيا — خدمات منطقتك في مكان واحد |
| **الإطار** | Next.js (App Router) + React + Bun |
| **اللغة الافتراضية** | العربية (RTL) |
| **PWA** | مدعوم (manifest + service worker) |
| **رابط API الافتراضي** | `http://localhost:8080/api/v1` |

---

## 2) 🌈 معمارية قوس قزح (مقيدة بالتكامل الفعلي)

```text
Backend API -> Adapter -> Service -> Store/Hook
                                ↓
                      EntityRenderer/SystemRegistry
                                ↓
                   component-map + ActionRenderer
```

المعمارية صالحة، لكن `SystemRegistry` في بيئة الإنتاج يجب أن يُسجّل الأنظمة المدعومة فعلياً من API فقط.

---

## 3) ✅ الأنظمة المدعومة فعلياً بالباك إند الحالي

1. **Users / Identity**
2. **Listings / Catalog**
3. **Bookings**
4. **Reviews**
5. **Messaging**
6. **Payments**
7. **Search**
8. **Admin**
9. **Pricing** (موجود بالباك إند وقابل للتكامل لاحقاً من الواجهة)

### أنظمة موجودة بالواجهة كمفهوم/UI فقط (ليست API جاهزة حالياً)
- Auction
- Event
- Emergency
- Real Estate كنظام خلفي مستقل
- Jobs/Used Items كـ APIs مستقلة

---

## 4) 🔌 مسارات API الرسمية المعتمدة

جميعها تحت `API_V1 = /api/v1`:

| الخدمة | المسار الصحيح |
|--------|----------------|
| **identityService** | `/users` |
| **catalogService** | `/listings` |
| **bookingService** | `/bookings` |
| **reviewsService** | `/reviews` |
| **messagingService** | `/messages` |
| **paymentsService** | `/payments` |
| **searchService** | `/search` |
| **adminService** | `/admin` |
| **pricingService** | `/pricing` |

### تصحيح مسارات قديمة
- ❌ `/api/v1/identity` → ✅ `/api/v1/users`
- ❌ `/api/v1/catalog` → ✅ `/api/v1/listings`
- ❌ `/api/v1/messaging` → ✅ `/api/v1/messages`

---

## 5) 🔐 المصادقة والتفويض

- الباك إند يعمل كـ **OAuth2 Resource Server (JWT)**.
- أغلب `/api/v1/**` محمية وتحتاج `Authorization: Bearer <token>`.
- لا توجد محلياً Endpoints جاهزة من نوع:
  - `/auth/login`
  - `/auth/register`
  - `/auth/refresh`
- لذلك تسجيل الدخول/تجديد التوكن يتم عبر **Identity Provider** خارجي أو بوابة auth منفصلة.

---

## 6) 🧩 حالات الكيانات المتوافقة

### Listings
`DRAFT -> ACTIVE -> PAUSED -> ARCHIVED`

### Bookings
`PENDING -> CONFIRMED -> COMPLETED / CANCELLED`

### Payments
Payment Intent lifecycle حسب حالات الباك إند (`create/process/confirm/cancel/refund`).

### Reviews / Messaging / Search
مدعومة بعمليات القراءة/الإنشاء/التحديث حسب الـ endpoints الفعلية.

---

## 7) 📁 الهيكل الحالي (ملائم للتكامل)

واجهة `frontend/` تحتوي على:
- صفحات App Router (`/`, `/market`, `/emergency`, `/directory`, `/community`)
- مكونات UI ومحلية
- `src/lib/api.ts` لخدمات API المتوافقة مع الباك إند
- سياقات لغة/مصادقة/منطقة/سلة

> ملاحظة: بعض الصفحات لا تزال تعتمد بيانات مضمنة (Mock/static) وتحتاج ربط تدريجي بخدمات API.

---

## 8) 🛠️ مواصفة الخدمات (نسخة متوافقة)

| الخدمة | الوظائف المتوقعة |
|--------|------------------|
| `identityService` | `me` |
| `catalogService` | `list`, `byCategory`, `byProvider`, `byId`, `create`, `update`, `activate`, `pause`, `archive` |
| `bookingService` | `byId`, `byConsumer`, `byProvider`, `create`, `confirm`, `complete`, `cancel` |
| `reviewsService` | `byId`, `byProvider`, `byReviewer`, `create`, `update` |
| `messagingService` | `conversationById`, `messages`, `unreadCount`, `createConversation`, `send`, `markRead` |
| `paymentsService` | `intentById`, `createIntent`, `processIntent`, `confirmIntent`, `cancelIntent`, `refund` |
| `searchService` | `search`, `byCategory` |

---

## 9) 📱 PWA و Offline

- `manifest.json` مهيأ بإعدادات عربية RTL.
- تم تبسيط الأيقونات الحالية لتفادي مشاكل الأصول الثنائية في سير العمل الحالي.
- يمكن إعادة الأصول متعددة الأحجام عبر CDN أو pipeline منفصل لاحقاً.

---

## 10) ⚠️ فجوات يجب أخذها بالحسبان

1. وجود أنظمة UI غير مدعومة بعد على مستوى API.
2. ضرورة توحيد المصادقة بين سياق الواجهة وخدمات JWT الفعلية.
3. ضرورة نقل الصفحات المعتمدة على mock إلى استدعاءات API تدريجياً.
4. لا تعتمد أسماء endpoints القديمة (`identity/catalog/messaging`) في أي تطوير جديد.

---

## 11) ✅ خلاصة تنفيذية

المواصفات المعتمدة للإنتاج يجب أن تبنى على وحدات الباك إند الحالية:
`users, listings, bookings, reviews, messages, payments, search, admin, pricing`.

أي نظام آخر يُعامل حالياً كـ **Planned/Mock** إلى حين توفر API رسمي له.

### حالة التنفيذ الحالية في الكود

- تم تنفيذ ربط فعلي داخل صفحة السوق `market/page.tsx` لجلب بيانات من `catalogService.list`.
- المصدر الفعلي للبيانات: `/api/v1/listings`.
- تمت إضافة واجهات حالة التحميل/الخطأ/عدم وجود بيانات.
