# نبض الضاحية وقدسيا - Frontend

دليل الأعمال المحلي للضاحية وقدسيا - واجهة المستخدم (Frontend)

## 🚀 البدء السريع

### 1. تثبيت الاعتمادات
```bash
bun install
```

### 2. إعداد متغيرات البيئة
انسخ ملف `.env.example` إلى `.env` وعدّل القيم:
```bash
cp .env.example .env
```

### 3. تشغيل المشروع
```bash
bun run dev
```

## 🔗 الربط مع Backend

### ملف API Client
جميع استدعاءات الـ API موجودة في `src/lib/api.ts`

### تعديل رابط الـ API
في ملف `.env`:
```
NEXT_PUBLIC_API_URL=https://your-backend-api.com/api
```

### أنواع البيانات
الأنواع (Types) معرفة في `src/lib/api.ts`:
- `User` - المستخدم
- `Business` - النشاط التجاري
- `Job` - الوظيفة
- `RealEstate` - العقار
- `UsedItem` - السلعة المستعملة
- `Event` - الفعالية
- `Offer` - العرض
- `EmergencyContact` - رقم الطوارئ

### خدمات API جاهزة
```typescript
import { businessService, jobsService } from '@/lib/api';

// جلب جميع الأنشطة
const businesses = await businessService.getAll({ category: 'restaurants' });

// جلب نشاط معين
const business = await businessService.getById('123');

// البحث
const results = await businessService.search('مطعم');
```

## 📁 هيكل المشروع

```
src/
├── app/                    # الصفحات (Next.js App Router)
│   ├── page.tsx           # الصفحة الرئيسية
│   ├── directory/         # صفحة الدليل
│   ├── market/            # صفحة السوق
│   ├── community/         # صفحة المجتمع
│   └── emergency/         # صفحة الطوارئ
│
├── components/
│   ├── layout/            # Header, Footer, BottomNav
│   ├── local/             # مكونات محلية (أقسام الصفحات)
│   └── ui/                # مكونات UI (shadcn/ui)
│
├── contexts/              # React Contexts
│   ├── LanguageContext    # إدارة اللغة (AR/EN)
│   ├── RegionContext      # إدارة المنطقة
│   └── AuthContext        # إدارة المصادقة
│
├── hooks/                 # Custom Hooks
│
└── lib/
    ├── api.ts             # عميل API للاتصال بـ Backend
    └── utils.ts           # أدوات مساعدة
```

## 🌐 النشر على Vercel

```bash
# تثبيت Vercel CLI
bunx vercel

# النشر للإنتاج
bunx vercel --prod
```

## 📝 الملاحظات

- المشروع يستخدم **Next.js 16** مع **App Router**
- التصميم **RTL** افتراضياً (من اليمين لليسار)
- يدعم اللغتين **العربية** و **الإنجليزية**
- مكونات UI من **shadcn/ui** مع **Tailwind CSS**

## 🔧 التخصيص

### تعديل الألوان
في ملف `tailwind.config.ts` و `src/app/globals.css`

### إضافة لغة جديدة
في ملف `src/contexts/LanguageContext.tsx`

### إضافة منطقة جديدة
في ملف `src/contexts/RegionContext.tsx`

## 📄 License

Apache-2.0
