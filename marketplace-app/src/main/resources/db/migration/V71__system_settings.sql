-- W0 (yelp-level-plan §5 — التحكم): جدول إعدادات المنصة. هذا هو التحكم
-- الأول في السلسلة: صفوف قابلة للتعديل من السطح الإداري دون إعادة نشر،
-- تُقرأ عبر منفذ مكاشد في W1 (بوابة إنشاء المراجعات تقرأ reviews.mode).
--
-- قواعد الإسناد (§1.4 من الخطة الحاكمة):
--   * أعمدة BaseEntity كاملة من اليوم الأول (درس V25/V32 — كل عمود يُنشأ
--     مع الجدول لا يُضاف لاحقاً).
--   * CHECK بنمط V44: NOT VALID (فهرس الميتاداتا فقط — قيد فوري للصفوف
--     الجديدة) ثم VALIDATE في معاملتها الخاصة (درس V66: داخل معاملة واحدة
--     يبقى القفل المحتجز حتى الالتزام).
--   * الفهرس الفريد عالمي على المفتاح: المفتاح هوية الإعداد ولا يُعاد
--     تدويره — قرار V70 المضاد (uq_categories_code: الهوية لا تُعفى
--     بالحذف الناعم كي لا يُعاد تدوير مفتاح بجوار بيانات قديمة تحمله).
--   * المرآة system_settings_aud بنمط V24: كل الأعمدة قابلة للإفراغ (سجل
--     DEL يحمل (id, rev, revtype) وحدها — درس V24/الذي عولج في V54).
--   * القيمة JSONB عبر الرسم الرسمي Hibernate (V48 amenities / V54
--     criteria — @JdbcTypeCode(SqlTypes.JSON)) — الشكل polymorphic:
--     نص/عدد/منطق/كائن كما تحتاجه سياسات W1+.
--   * البذرة تتجاوز Envers بطبعها (سابقة geo D-E11 وV70 categories: المرآة
--     تسجّل أول كتابة إدارية حقيقية).
--
-- حقوق العبور: /api/v1/admin/** => hasRole(ADMIN) في سلسلة SecurityConfig
-- (L30) + المستوى الصنفي على AdminController + مستوى الخدمة — ثلاث طبقات.

CREATE TABLE system_settings (
    id          UUID PRIMARY KEY,
    setting_key VARCHAR(100) NOT NULL,
    setting_value JSONB NOT NULL,
    description VARCHAR(500),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- شكل المفتاح: فولذي منقوط (reviews.mode / reviews.organic.daily-cap).
    CONSTRAINT chk_system_settings_key
        CHECK (setting_key = lower(setting_key)
           AND length(setting_key) BETWEEN 1 AND 100
           AND setting_key ~ '^[a-z][a-z0-9-]*(\.[a-z0-9-]+)*$')
        NOT VALID
);

ALTER TABLE system_settings VALIDATE CONSTRAINT chk_system_settings_key;

-- المفتاح هوية الإعداد (عالمي، لا شرط الحذف الناعم — قرار V70 للهوية).
CREATE UNIQUE INDEX uq_system_settings_key
    ON system_settings (setting_key);

-- البذرة: بوابة المراجعات تبقى في وضعها الراهن تماماً (صفر تغيير سلوك
-- مرئي — معيار قبول W0). معرف ثابت لِماَ يُقرأ من الـ seed نفسه.
INSERT INTO system_settings (id, setting_key, setting_value, description)
VALUES ('71717171-7171-4171-8171-717171717171',
        'reviews.mode',
        '"VERIFIED_ONLY"'::jsonb,
        'Review-creation gate: VERIFIED_ONLY | OPEN | HYBRID (yelp plan 4.1)');

-- Envers audit history (V24 convention; all columns nullable — a DEL
-- revision carries only (id, rev, revtype)). The seed above bypasses
-- Envers by nature; the mirror records the first real admin write.
CREATE TABLE system_settings_aud (
    id            UUID NOT NULL,
    rev           INTEGER NOT NULL,
    revtype       SMALLINT,
    setting_key   VARCHAR(100),
    setting_value JSONB,
    description   VARCHAR(500),
    is_deleted    BOOLEAN,
    version       BIGINT,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ,
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);