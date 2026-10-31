# خريطة استكمال مخطط التجارة — تدقيق 2026-10-24 (فرع التكامل، موجة fb23ad4c)

## الطريقة
تدقيق الأدلة لكل جذر NOT_STARTED (294) مقابل كود الموجة على الفرع: توصيل DGS fetcher (@DgsMutation/@DgsQuery) + إنفاذ @PreAuthorize على طبقة الخدمة + اختبارات DgsTest/IntegrationTest + إعلان SDL.

## النتيجة
- **56 جذرًا مطبقة فعليًا والجرد متخلف عنها** — قُلبت إلى INTEGRATED بأدلتها (commit fb23ad4c): كل عائلات كتابة P2 (فئات 5، مجموعات 8، منتجات 5، وسائط 3، أنواع 5، متغيرات 6، سمات 15، تعيينات أنواع 3، قراءتا collection/collections) + 4 جذور P3 (voucherCreate/Update/Delete/BulkDelete).
- **238 جذرًا هي الفجوة الحقيقية** (غير معلنة أصلًا على السلك):

| الموجة | الغائب | حسب العائلة |
|---|---|---|
| P2 | 28 | ترجمة 5، مستودعات/مخزون 9، قوائم قناة 4، ترتيب 3، وسائط 4، متفرقات 3 |
| P3 | 59 | خصومات 23، بطاقات هدايا 16، ضرائب 7، متجر 7، قنوات 6 |
| P4 | 94 | طلبات 43، مدفوعات 21، دفع/checkout 17، شحن 13 |
| P5 | 31 | تطبيقات 16، غير مصنف 9، ويبهوكس 6 |
| P6 | 26 | اشتراكات 26 (قرار النقل: Spring for GraphQL transport — §P6) |

## قرارات P2 المطلوبة من المالك (لا بناء بلا قرار — لا حلول وهمية)
| العائلة | الجذور | المطلوب |
|---|---|---|
| الترجمة (productTranslate, productBulkTranslate, productVariantTranslate, productVariantBulkTranslate, productMediaTranslate) | 5 | **D-P2-TRANSLATE-001**: كيانات Product/ProductVariant/MediaAsset أحادية اللغة (title/name بعمود واحد) — قرار الأعمدة ثنائية اللغة + ترحيل Flyway (نمط Category nameEn/nameAr) أو جدول ترجمات |
| المستودعات والمخزون (createWarehouse, updateWarehouse, deleteWarehouse, assign/unassignWarehouseShippingZone, stockBulkUpdate, productVariantStocksCreate/Update/Delete) | 9 | **D-P2-WAREHOUSE-001**: قرار المالك الممتلك (وحدة جديدة مقابل توسعة availability) — §P4: المخزون/الحجز/التخصيص موارد منفصلة |
| قوائم القناة (product/productVariant/collectionChannelListingUpdate, productVariantChannelListingAvailabilityUpdate) | 4 | **D-P2-CHANNEL-001**: سياق القناة التجارية (§P3: العملة/السعر/التوافر/الضريبة) — تبعية دلالات القناة |
| الترتيب (productReorderAttributeValues, productVariantReorderAttributeValues, productVariantReorder) | 3 | **D-P2-REORDER-001**: أعمدة position للكيانات المعنية (ProductVariant بلا عمود ترتيب مقاس) |
| الوسائط (productMediaCreate, productMediaUpdate, variantMediaAssign, variantMediaUnassign) | 4 | **D-P2-MEDIA-001**: مسار الرفع/signed-URL (§P2: لا URL وهمي) + حقول alt + ربط variant↔media |
| متفرقات (productVariantSetDefault, productVariantPreorderDeactivate, exportProducts) | 3 | **D-P2-MISC-001**: أعمدة isDefault/preorder + بنية مهام التصدير |

## قرارات الموجات التالية (ملخص)
- **P3**: بطاقات الهدايا تحتاج المالك الممتلك للالتزام/الرصيد (نص المخطط §P3 يوجب إنشاءه — قرار معماري)؛ الخصومات/الضرائب فوق marketplace-pricing؛ القنوات = السياق التجاري.
- **P4**: أكبر كتلة (94) — فوق marketplace-orders/payments/delivery/RMA القائمة (§P4).
- **P5**: التطبيقات = سجل امتدادات حقيقي فقط حيث توجد القدرة؛ الويبهوكس خارج الحسبة (D-WHBK-001 BLOCKED).
- **P6**: قرار النقل أولًا (approved end-to-end consumer).

## القاعدة
لا جذر يُعلن INTEGRATED بلا دليل مقاس. لا fake resolvers (D2). كل اختلاف يسجل في سجل الانحرافات. الموجة نفسها تحت مراجعة CodeRabbit واختبارات CI على PR #723 — ولا هبوط إلى main إلا بأمر صريح من المالك.
