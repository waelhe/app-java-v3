# مرجع رسم المعرفة (Knowledge Graph) — الملاحة الرخيصة عبر النظام كله

> **غرض هذا الملف:** تثبيت الطريقة الدائمة للاستفادة من رسم المعرفة المولَّد في `.ua/` — بحيث يبدأ أي جلسة عمل من **استعلامات رخيصة مقيسة** بدل إعادة دراسة النظام من الصفر. الوصفات في §3 مُختبرة سطراً بسطر (جلسة 2026-09-19) على لقطتي الرسم القائمتين.
>
> **قاعدة المكانة (حاسمة):** هذا مرجع ملاحي، **ليس ملفاً حاكماً** (AGENTS.md §0.1-3) — لا يملك قراراً ولا يفتح بوابة ولا يعيد ترتيب مصفوفة. الحقيقة النهائية دائماً **القياس الحي للكود** (بروتوكول §0.1)؛ الرسم **بداية التوجه لا نهايته**: يختصر «من أين أبدأ؟ وما نصف قطر المس؟» ثم يُقرأ الكود نفسه.
>
> **قاعدة الهوية قبل الاستخدام:** الرسم **لقطة زمنية** مرتبطة برأس main لحظة تحليلها (مسجلة في `.ua/meta.json`). أول سطر في أي استخدام = وصفة R1 (هوية اللقطة) — ثم تُقاس حدود حداثتها قبل الاعتماد على غياب/وجود أي عنصر.

---

## 1. أين يعيش وكيف وُلد

- **الموقع:** `.ua/` في جذر المستودع — **متتبَّع في git** منذ PR #334 (كوميت `6e757fa`): الرسم أصل مستودعي محكوم بالـPRs مثل أي أصل آخر، لا ناتج بناء عابر.
- **المولِّد:** أداة التحليل `understand` (ضوابطها: `.ua/.understandignore` بصياغة gitignore نفسها + `.ua/config.json` حيث `outputLanguage: "ar"` — لذلك الملخصات والوسوم بالعربية).
- **الملفات الأربعة:**

| الملف | الدور |
|---|---|
| `knowledge-graph.json` | الرسم نفسه: عقد + حواف + طبقات + جولة معمارية |
| `meta.json` | هوية اللقطة: `gitCommitHash` + `lastAnalyzedAt` + `analyzedFiles` |
| `fingerprints.json` | بصمات الملفات (أساس التحليل التزايدي للمولِّد) |
| `intermediate/scan-result.json` | ناتج المسح الوسيط |

### جدول اللقطات المقيسة (قياس 2026-09-19)

| اللقطة | أين تعيش اليوم | مقيسة على رأس | عقد/حواف | جيل الحواف |
|---|---|---|---|---|
| #334 | **main نفسه** (`.ua/` المتتبعة — كوميت `6e757fa`) | `604bb5b` (2026-09-17T12:18Z، 863 ملفاً) | 3571 / 3828 | أول: **بلا `imports` و بلا `migrates`** |
| الفرع `knowledge/graph-957e9ac` (رأسه `81249e9`) | فرع قائم — دمجه بكلمة المستخدم (§6) | `957e9ac` (2026-09-19T07:48Z، 918 ملفاً) | 3813 / 5357 | أحدث: `imports` 1004 + `migrates` 81 + `calls` 202 + `tested_by` 131 |

**حدود حداثة اللقطتين مقيسة بصدق كامل (لا تُفترض):** لقطة main لا تعرف وحدة `marketplace-community` أصلاً (صفر عقد تحتها — وُلدت قبل L41/L42)؛ ولقطة الفرع تعرف community (88 عقدة رئيسية) وmessaging لكنها **تسبق L45** (لا `content_reports` ولا `CONTENT_MODERATED`). أي عنصر أحدث من لقطة الرسم = غير موجود فيه حكماً — يُقاس حياً كالعادة.

**الوصول للقطة الأحدث قبل دمج فرعها** (يعمل دون أي دمج) — **استخرج الملفين معاً** (الرسم + هويته، فلا تخلط لقطة بهوية أخرى):
```bash
git show origin/knowledge/graph-957e9ac:.ua/knowledge-graph.json > /tmp/kg.json
git show origin/knowledge/graph-957e9ac:.ua/meta.json > /tmp/meta.json
```
وبعد دمج المستخدم للفرع تصبح `.ua/` على main هي اللقطة الأحدث مباشرة (الوصفات كلها تعمل على المسارين).

---

## 2. المخطط (Schema) — مستقر عبر الجيلين

جذر JSON: `version` / `project` / `nodes` / `edges` / `layers` / `tour`.

**العقدة (node):**

| الحقل | الدلالة | مثال مقيس |
|---|---|---|
| `id` | بادئة النوع + المسار (+ `:اسم` للعناصر داخل الملف) — **المسار موجود فيه دائماً حتى لو غاب `filePath`** | `class:marketplace-messaging/.../MessagingService.java:MessagingService` |
| `name` | الاسم المجرد | `MessagingService` |
| `type` | أحد ثمانية: `function` / `file` / `class` / `table` / `document` / `config` / `service` / `pipeline` | `class` |
| `filePath` | مسار المستودع — **حاضر دائماً في عقد file/table/document/config؛ في عقد class/function قد يغيب بين الأجيال** (فَاستخرجه من `id` عند غيابه — انظر R2) | `marketplace-messaging/src/main/java/...` |
| `summary` | ملخص عربي مولَّد (نصه يختلف بين الأجيال — وصفُ لقطة لا اقتباس أزلي) | «خدمة المحادثات تتحقق من المشاركين عبر BookingParticipantProvider...» |
| `tags` | وسوم — **لغتها تختلف بين الأجيال**: عربية في جيل #334 وإنجليزية في جيل الفرع | `['service','messaging','conversation']` |
| `complexity` | `simple` / `moderate` / ... | `moderate` |
| `lineRange` | نطاق أسطر العنصر داخل ملفه (عقد class/function — **أضافه جيل الفرع**) | — |

**الحافة (edge):** `source` / `target` (معرّفا عقدة) + `type` + `direction` + `weight`.

**مستوى ارتباط كل نوع حافة — مقيس على لقطة 957e9ac (حرج لصحة الوصفات):**

| نوع الحافة | ترتبط من → إلى | العدد | الدلالة |
|---|---|---|---|
| `contains` | `file → function/class`، و`service → service` | 2814 | الملكية الهيكلية (ملف يضم دواله وأصنافه) |
| `exports` | `file → function/class` | 1073 | واجهة الملف المصدَّرة |
| `imports` | `file → file` | 1004 | اقتران الاستيراد بين الملفات |
| `calls` | `function → function/class/file` | 202 | استدعاء من دالة |
| `tested_by` | `file → file` | 131 | ملف رئيسي ← ملف حرّاسه |
| `migrates` | `table → table` | 81 | ملف ترحيلة → جدول أنشأه |
| `implements` / `inherits` | `class → class` | 26 / 5 | عقود التصنيف |
| `depends_on` / `configures` / `related` | مختلط | 4 / 3 / 14 | متفرقات (خدمات/إعدادات/وثائق) |

**الطبقات (9، معرّفاتها مستقرة):** `layer:api` / `layer:service` / `layer:data` / `layer:types` / `layer:middleware` / `layer:config` / `layer:test` / `layer:infrastructure` / `layer:documentation` — لكل طبقة اسم ووصف عربي وقائمة `nodeIds`.
**الجولة (tour):** خطوات مرتبة (`order` + `title` + `description`) من نقطة دخول التطبيق حتى الحاويات والتسليم — جاهزة ك«تقديم معماري» لمن يريد قراءة النظام أول مرة.

**تحذير التوافق بين الأجيال:** مفردات الحواف تتطور بين أجيال المولِّد (جيل #334 لا يحمل `imports`/`migrates` أصلاً). الوصفات أدناه تعلن متطلباتها؛ ما لا تجده من الحواف في لقطتك يعني أن جيلها أقدم لا أن الكود خالٍ من الاقتران.

---

## 3. وصفات الاستعلام — كلها مختبرة (جلسة 2026-09-19)

> **قاعدة الاستخدام:** تُشغَّل من **جذر المستودع** بـ`python3` فقط (المكتبة القياسية تكفي — لا اعتماديات). السطر الأول في كل وصفة `KG = '.ua/knowledge-graph.json'` (لقطة main) — **عدّله إلى `'/tmp/kg.json'`** لتشغيلها على لقطة الفرع الأحدث بعد استخراجها، **وفي R1 عدّل `META` أيضاً إلى `'/tmp/meta.json'`** (أمر §1 — الهوية والرسم من اللقطة نفسها دائماً). **النتائج المقيسة أدناه مأخوذة من لقطة الفرع** (الأحدث وقت التوثيق) — فعلى لقطة main الحالية (جيل #334) تعطي R1/R2/R4/R6 نواتجها، بينما R3/R5/R7/R8 تحتاج جيل `imports`/`migrates`: R5/R7/R8 تعطي قوائم فارغة/صفراً على الجيل الأقدم، وR3 يبقى بحواف `calls` وحدها (تصل الدوال/الأصناف لا الملفات في جيل #334 — المقيس: 88 حافة كلها function/class الأهداف) فتعطي **للهدف-الملف المثال صفراً مقيساً**، ولهدفٍ وظيفي رقماً جزئياً أدنى من الحقيقة — حتى يُدمج فرع اللقطة الجديدة.

### R1 — هوية اللقطة (إلزامية قبل أي استخدام آخر)

**السؤال:** «على أي رأس رُسم هذا الرسم؟ ومتى؟ وكم حجمه؟» — تحديد حدود الصدق قبل أي استنتاج.

```bash
python3 - <<'PY'
import json
KG = '.ua/knowledge-graph.json'
META = '.ua/meta.json'
meta = json.load(open(META))
g = json.load(open(KG))
print(meta['gitCommitHash'][:8], '|', meta['lastAnalyzedAt'],
      '|', len(g['nodes']), 'nodes |', len(g['edges']), 'edges')
PY
```
**الناتج المقيس الآن على main:** `604bb5bc | 2026-09-17T00:00:00Z | 3571 nodes | 3828 edges` — أي أن لقطة main الحالية تسبق L41/L42/L45؛ من يبحث عن عناصر هذه الطبقات في الرسم يجدها غائبة **بحكم اللقطة** لا بحكم الكود.

### R2 — سياق فوري لعنصر بالاسم (قبل قراءة ملفه)

**السؤال:** «ما هذا الصنف/الملف؟ ما وسومه وتعقيده وملخصه؟» — بديل دقيقي عن فتح الملف وقراءته كاملاً للتوجيه الأول.

```bash
python3 - <<'PY'
import json
KG = '.ua/knowledge-graph.json'
g = json.load(open(KG))
for n in g['nodes']:
    if n['name'] == 'MessagingService' and n['type'] == 'class':
        path = n.get('filePath') or n['id'].split(':', 1)[1].rsplit(':', 1)[0]
        print(path, '|', n['complexity'], '|', n['tags'])
        print(n['summary'])
PY
```
**الناتج المقيس (لقطة الفرع):** `marketplace-messaging/.../MessagingService.java | moderate | ['service','messaging','conversation']` + الملخص العربي (المسار هنا مستخرج من `id` — بعد نزع بادئة النوع ولاحقة اسم العنصر — لأن جيل الفرع لا يحمل `filePath` على عقدة الصنف هذه). **وعلى لقطة main (جيل #334) نفس الوصفة تعطي:** المسار نفسه بوسوم عربية `['خدمة','مراسلة','websocket']` وملخص أقدم — مثال حيّ على أن الملخصات/الوسوم وصفُ لقطة لا اقتباس أزلي (حد 6 أدناه). **تنبيهان:** الاسم الواحد قد يصادف صنفاً ودالته البانية معاً (فلتر `type` إلزامي)؛ وحقل `filePath` قد يغيب عن عقد class/function في بعض الأجيال — لذلك يُستخرج المسار من `id` عند غيابه (سطر `path = ...`).

### R3 — نصف قطر المس (من يعتمد على هذا الملف؟)

**السؤال:** «قبل تعديل X — كم ملفاً يستورده/يستدعيه؟» — تحديد انفجار التغيير قبل البدء (يتطلب جيل `imports`؛ على جيل #334 يعمل على `calls` فقط فيعطي رقماً أدنى من الحقيقة).

```bash
python3 - <<'PY'
import json
KG = '.ua/knowledge-graph.json'
g = json.load(open(KG))
target = 'file:marketplace-platform-infra/src/main/java/com/marketplace/shared/security/CurrentUserProvider.java'
hits = [e['source'] for e in g['edges']
        if e['target'] == target and e['type'] in ('imports', 'calls')]
print(len(hits), 'حافة واردة من', len(set(hits)), 'عقدة مصدر مختلفة (أعلى 10):')
for h in sorted(set(hits))[:10]:
    print('  ', h)
PY
```
**الناتج المقيس (لقطة الفرع):** `94 حافة واردة من 94 عقدة مصدر مختلفة` — كلها حواف `imports` من ملفات (بلا تكرار)، أعلى Fan-In في النظام كله (§4). العدّ للمصادر الفريدة هو المعنى الصحيح لـ«معتمدًا»؛ عدّ الحواف وحده قد يضاعف ملفاً واحداً بعدة حواف.

### R4 — حرّاس الاختبار لملف

**السؤال:** «ما الذي يكسر إن كسرت هذا الملف؟» — حرّاس الوحدة المباشرون (حافة `tested_by` تربط الملف الرئيسي بملف حرّاسه؛ تعمل على الجيلين).

```bash
python3 - <<'PY'
import json
KG = '.ua/knowledge-graph.json'
g = json.load(open(KG))
f = 'file:marketplace-messaging/src/main/java/com/marketplace/messaging/MessagingService.java'
guards = [e['target'] for e in g['edges'] if e['type'] == 'tested_by' and e['source'] == f]
print(guards)
PY
```
**الناتج المقيس (لقطة الفرع):** `['file:marketplace-messaging/src/test/java/.../MessagingServiceTest.java']`.

### R5 — أي ترحيلة أنشأت هذا الجدول؟

**السؤال:** «أرى جدولاً في القاعدة/الكيان — من أي V جاء؟» — قفزة مباشرة للترحيلة عبر حافة `migrates` (تتطلب جيل لقطة الفرع).

```bash
python3 - <<'PY'
import json
KG = '.ua/knowledge-graph.json'
g = json.load(open(KG))
t = [n for n in g['nodes'] if n['type'] == 'table' and n['name'] == 'neighborhood_posts']
if not t:
    print('لا عقدة جدول بهذا الاسم في هذه اللقطة (اسم مختلف أو جيل أقدم من migrates)')
else:
    for e in g['edges']:
        if e['type'] == 'migrates' and e['target'] == t[0]['id']:
            print(e['source'], '->', e['target'])
PY
```
**الناتج المقيس (لقطة الفرع):** `table:marketplace-app/src/main/resources/db/migration/V61__neighborhood_posts.sql -> table:...V61__neighborhood_posts.sql:neighborhood_posts` — أي أن معرّف عقدة الجدول يحمل رحلة إنشائه نفسها (نمط `table:<الترحيلة>:<الجدول>`).

### R6 — شريحة وحدة كاملة (جرد قبل التعمق)

**السؤال:** «ما كل ما يملكه هذا الوحدة؟» — عقد كودها الرئيسي مصنفة بالنوع (تعمل على الجيلين).

```bash
python3 - <<'PY'
import json
from collections import Counter
KG = '.ua/knowledge-graph.json'
g = json.load(open(KG))
s = [n for n in g['nodes']
     if str(n.get('filePath', '')).startswith('marketplace-community/src/main')]
print(len(s), dict(Counter(n['type'] for n in s)))
PY
```
**الناتج المقيس (لقطة الفرع):** `88 {'file': 21, 'function': 48, 'class': 19}` — شريحة community الرئيسية.

### R7 — المراكز (أعلى/أدنى اعتماد)

**السؤال:** «ما المراكز المعمارية؟ أين قلب النظام؟» — Fan-In عبر `imports` (الأكثر اعتماداً = قلب النواة المشتركة). انعكس الاتجاه تحصل على Fan-Out (يتطلب جيل `imports`).

```bash
python3 - <<'PY'
import json
from collections import Counter
KG = '.ua/knowledge-graph.json'
g = json.load(open(KG))
fin = Counter(e['target'] for e in g['edges'] if e['type'] == 'imports')
for nid, c in fin.most_common(8):
    print(f'{c:>4}  {nid}')
PY
```
**الناتج المقيس (لقطة الفرع):** جدول §4 الأول حرفياً.

### R8 — مصفوفة الاقتران عبر الوحدات (تحقق قاعدة التسليم)

**السؤال:** «من يستورد من من عبر حدود الوحدات؟ وهل قاعدة Modulith (الأعمال ← shared/infra حصراً) قائمة في الرسم نفسه؟» (يتطلب جيل `imports`).

```bash
python3 - <<'PY'
import json
from collections import Counter
KG = '.ua/knowledge-graph.json'
g = json.load(open(KG))
nodes = {n['id']: n for n in g['nodes']}
def mod(nid):
    fp = (nodes.get(nid) or {}).get('filePath') or ''
    return fp.replace('\\', '/').split('/')[0]
cross = Counter()
for e in g['edges']:
    if e['type'] in ('imports', 'depends_on'):
        s, t = mod(e['source']), mod(e['target'])
        if s != t and s.startswith('marketplace') and t.startswith('marketplace'):
            cross[(s, t)] += 1
LEGIT_TARGETS = {'marketplace-shared', 'marketplace-platform-infra'}
SPI_DECLARED = {('marketplace-messaging', 'marketplace-catalog'),
                ('marketplace-realestate', 'marketplace-catalog')}
violations = sorted(p for p in cross
                    if p not in SPI_DECLARED and p[1] not in LEGIT_TARGETS
                    and p[0] != 'marketplace-app')
print('إجمالي الأزواج العابرة:', len(cross), '(أعلى 12 للعرض)')
for (s, t), c in cross.most_common(12):
    print(f'{c:>4}  {s} -> {t}')
print('انتهاكات الأعمال→أعمال خارج الأوعية المشروعة وSPI المعلنة:', violations if violations else 'صفر')
PY
```
**الناتج المقيس (لقطة الفرع):** `إجمالي الأزواج العابرة: 52` ثم أعلى 12 زوجًا للعرض (جدول §4.2 يجمعها في أوعيتها الأربعة) — **والفحص الحاسم يطبع السالبة الحاكمة على كل الأزواج لا على أعلى 12 فقط:** `انتهاكات الأعمال→أعمال خارج الأوعية المشروعة وSPI المعلنة: صفر`.

---

## 4. الحقائق المستقرة المقيسة (لقطة `957e9ac` — لا تُدرس من جديد، تُستشهد)

> هذه الأرقام صالحة للقطة المذكورة؛ تتغير مع كل لقطة جديدة فتُعاد بوصفة R7/R8 ويُحدَّث هذا الجدول بدفعتها (§6).

### 4.1 المراكز — Fan-In عبر `imports` (الأكثر اعتماداً في النظام)

| # | العنصر (ملف) | حواف واردة | القراءة المعمارية |
|---|---|---|---|
| 1 | `shared/security/CurrentUserProvider.java` | **94** | هوية المتصل — يمسّ كل سطح API |
| 2 | `shared/api/ResourceNotFoundException.java` | **59** | عائلة 404 الموحدة |
| 3 | `shared/api/ConflictException.java` | **43** | عائلة 409 |
| 4 | `shared/api/BadRequestException.java` | **38** | عائلة 400 |
| 5 | `shared/jpa/BaseEntity.java` | **30** | قاعدة الكيانات كلها |
| 6 | `shared/api/BookingParticipantProvider.java` | **28** | عقد مشاركي الحجز |
| 7 | `shared/api/ApiConstants.java` | **24** | مسارات v1 الموحدة |
| 8 | `shared/api/ProviderLookupPort.java` | **24** | عقد بحث المزوّد |

**القراءة الجامعة:** أعلى عشرة كلها في `shared`/`platform-infra` — النواة المشتركة هي الجاذبية المعمارية للنظام، وتعديل أي عنصر منها له أكبر نصف قطر مس (لهذا يبدأ تقدير أثر أي تغيير بحوافه عبر R3).

### 4.2 قاعدة التسليم المقيسة في الرسم نفسه

حواف `imports`/`depends_on` العابرة لحدود الوحدات (920 حافة داخل أزواج وحدات marketplace): كلها تسير في **أربعة أوعية مشروعة فقط**:

| الوعاء | الحواف | الدلالة |
|---|---|---|
| وحدات الأعمال/التجميع → `marketplace-shared` | **690** | SPI والأنواع المشتركة — القاعدة الأولى |
| → `marketplace-platform-infra` | **142** | البنية المشتركة (أمن/JPA/ويب) |
| `marketplace-app` → وحدات أعمال | **84** | جذر التركيب يستضيف أسطح REST/GraphQL |
| عبر SPI معلنة (`catalog-spi`) | **4** | messaging/realestate → `catalog/spi/CatalogSpi.java` — مصرَّح بها في `package-info.java` للطرفين (`"catalog :: catalog-spi"` في allowedDependencies) |

**السالبة الحاكمة:** صفر اقتران أعمال→أعمال على تنفيذيات وحدات بعضها خارج SPI المعلنة — قاعدة Modulith (§5 من SYSTEM.md) محقَّقة **في الرسم نفسه** لا في الفحص وحده؛ ومصفوفة R8 هي المرآة المستمرة لها بعد كل لقطة.

### 4.3 كثافة الحراسة وأعلى Fan-Out (للاسترشاد)

- `tested_by`: **131** حافة — أكثر الملفات حراسةً: `RadiusSearchIntegrationTest` و`SearchPropertyFilterIntegrationTest` (كل منهما يحرس 5 ملفات).
- أعلى Fan-Out عبر `imports`: `UserDataExportServiceTest` (18) و`CatalogService` (16) و`DisputeFinancialResolutionIntegrationTest` (15) و`BookingService` (12) — الأعلى استهلاكاً للنواة المشتركة.

---

## 5. حدود الصدق (اقرأها قبل أول استخدام)

1. **لقطة زمنية لا حقيقة حية:** الرسم مرتبط برأس محدد (`meta.json`) — كل ما دُمج بعده غائب منه حكماً. الغائب من الرسم **ليس** دليل غياب من الكود؛ الحاضر فيه قد يكون أقدم مما في الكود. القياس الحي (§0.1) يبقى القاضي، والرسم يختصر الطريق إليه.
2. **الملخصات والوسوم مولَّدة آلياً:** بالعربية، ممتازة للتوجيه الأول، **لا تُقتبس كدليل** — الدليل يبقى الكود وملفات الحقيقة (SYSTEM.md قاعدة الذهب).
3. **مفردات الحواف تعتمد على جيل المولِّد:** جيل #334 بلا `imports`/`migrates`؛ الوصفات تعلن متطلباتها (§3) — نتيجة صفر على حافة غير موجودة في الجيل تعني «غير مقيس هنا» لا «غير موجود في الكود».
4. **الأسماء غير فريدة:** صنف ودالة تحمل الاسم نفسه (الباني) — وصفة الاسم تُفلتر بالنوع حصراً.
5. **`fan-in/fan-out` عبر `imports` = اقتران ملف→ملف** (ألف حافة): أداة ترجيح نصف قطر المس، وليست جرداً كاملاً لكل مسارات الاتصال (SPI والأحداث المشتركة يقرأها `SYSTEM.md §5` — والرسم يوثق imports الحرفية فقط).
6. **الملخصات والوسوم وصفٌ للقطة لا للكود الأزلي:** الجيلان يصفان العنصر الواحد بوسوم وملخص مختلفين (R2 مثالاً حياً) — تُستعمل للتوجيه ثم يُقرأ الكود نفسه.

---

## 6. دورة التحديث المحكومة

**متى تُحدَّث اللقطة:** بعد كل دمج طبقة/قدرة إلى main (خياطة L-جديدة أو وحدة جديدة) أو عند محطة مراجعة — بحيث لا تفترق هوية اللقطة عن رأس main أكثر من طبقة واحدة إن أمكن. تحديثها إجراء توثيقي صرف لا يمس زمن البناء أو التشغيل (`.dockerignore` يستبعد غير الكود أصلاً).

**كيف (النمط القائم المقيس):**
1. تشغيل المولِّد على رأس main النقي → يحدّث `.ua/` (أربعة ملفات).
2. فرع باسم `knowledge/graph-<short-hash-للرأس>` (نمط الفرع القائم `knowledge/graph-957e9ac`).
3. PR توثيقي واحد بالأرقام الجديدة (عقد/حواف + الفرق عن اللقطة السابقة) — كسابقة PR #334.
4. الدمج **بكلمة المستخدم حصراً** (§14.3) — شأنه شأن كل دمج.
5. **بعد الدمج تُعاد وصفة R7 وR8 وتُحدَّث جداول §4 وجدول لقطات §1 في نفس دفعة توثيقية** (درس البيت: ملفات الحقيقة تركّب الحالة بنفس الدفعة) — هذا ما يُبقي «الحقائق المستقرة» مستقرة فعلاً بلا إعادة دراسة.

**اللقطتان القائمتان اليوم:** لقطة main (#334 @604bb5b) متقادمة بثلاث طبقات (تسبق community كله)؛ ولقطة الفرع (`957e9ac`) هي الأحدث القابلة للدمج — قرار دمجها بيد المستخدم، وبعده تصبح `.ua/` على main هي الأحدث مباشرة.

---

## 7. الأثر الدائم في نظام التوجيه (لماذا يوجد هذا الملف)

قبل هذا المرجع كان استيعاب الرسم جهد جلسة كاملة يُعاد من الصفر ولا يترك أثراً في ملفات الحقيقة (قياس 2026-09-19: صفر إشارة للرسم أو `.ua` في SYSTEM.md/PROJECT_MAP.md/AGENTS.md رغم أن #334 مدموج منذ 2026-09-17). بعده:

- **SYSTEM.md §10** (خريطة التعمق) يوجّه للرسم كأول طبقة ملاحية — الدخول من هوية اللقطة (R1) ثم الوصفات.
- **SYSTEM.md §12** (حقائق مؤكدة مسبقاً — «لا تُدرس من جديد») يحمل مركزيّة النواة وقاعدة التسليم المقيسة — فأي جلسة قادمة تبدأ بعلامات معمارية جاهزة بدل اكتشافها.
- هذا الملف يحمل الوصفات المختبرة نفسها — فالسؤال المتكرر («ما نصف قطر مس X؟ ما حرّاسه؟ من أي ترحيلة جاء جدوله؟») يصبح أمراً واحداً بدل استكشاف ساعة.

**وعد الأداة بحدوده الصادقة:** ما يختصره الرسم هو **التوجيه** (أين أبدأ؟ ما الجوار؟ ما المراكز؟)؛ وما لا يختصره أبداً: القرار (ملفات الخطط الحاكمة)، الدليل (الكود والمصادر الرسمية)، والقياس الحي لحظة العمل.
