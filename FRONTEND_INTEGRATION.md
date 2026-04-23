# Frontend integration guide (Nabd + Marketplace backend)

تمت إضافة نسخة الـ Frontend التجريبية داخل المسار:

- `frontend/`

## 1) تشغيل الـ Backend

```bash
./mvnw -pl marketplace-app spring-boot:run -Dspring-boot.run.profiles=dev
```

الـ API سيكون على:

- `http://localhost:8080/api/v1`

## 2) تشغيل الـ Frontend

```bash
cd frontend
cp .env.example .env.local
bun install
bun run dev
```

الواجهة ستكون على:

- `http://localhost:3000`

## 3) إعدادات الربط التي تم تجهيزها

- تحديث `frontend/.env.example` ليستخدم:
  - `NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1`
- تحديث `frontend/src/lib/api.ts` ليتوافق مع endpoints الموجودة فعلياً في الـ Backend:
  - `/users/me`
  - `/listings`
  - `/search`
  - `/bookings`
  - `/reviews`
  - `/messages`
  - `/payments`

## 4) ملاحظة مهمة عن المصادقة

حالياً معظم مسارات `/api/v1/**` في الباك إند محمية (JWT required).
لذلك أي استدعاء من الواجهة يحتاج Bearer token صحيح في `Authorization` header.

## 5) CORS في التطوير

بروفايل dev في الباك إند يحتوي:

- `marketplace.cors.allowed-origins: http://localhost:3000`

وهذا مناسب للتشغيل المحلي مع Next.js.


## 6) ملاحظة بخصوص الأصول الثنائية (صور PNG/JPG)

تم حذف الملفات الثنائية من المستودع لتفادي مشاكل إنشاء Pull Request في البيئة الحالية.
يمكنك إعادة إضافة الصور لاحقاً عبر CDN أو مستودع أصول منفصل عند الحاجة.
