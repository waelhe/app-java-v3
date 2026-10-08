# تدقيق مطابقة Spring Security 7.1.1 — الموجة التنفيذية (2026-10-07)

> **الحاكم المطلق والوحيد لهذا التدقيق (أمر المالك 2026-10-07):** الوثائق الرسمية والمستودعات الرسمية للإطار — لا وثيقة داخلية ولا خطة داخلية واحدة استُخدمت مرجعاً لأي قرار. كل بند أدناه مسنود باقتباس حرفي من الصفحة الرسمية المقيسة هذه الجلسة، أو بدليل من المصدر الرسمي المستخرج من المستودع المُصدَر (بايت-كود/مصادر الأرتفاكت الرسمية 7.1.1 / 4.1.1 / Boot 4.1.1).
>
> **حدود النسخ المقيسة:** Spring Security **7.1.1** (المرجع `docs.spring.io/spring-security/reference/7.1/` — المستقر المعلن 7.1.1) + Spring Session **4.1.1** (المرجع `docs.spring.io/spring-session/reference/4.1/`) + Spring Boot **4.1.1** (auto-config المصادر) + مستودعا `spring-projects/spring-security` و`spring-projects/spring-session` عبر الأرتفاكت الرسمية من Maven Central.

## 0. المنهج والأدلة

1. **جلب الوثائق الرسمية هذه الجلسة** (31 صفحة خُزنت نصاً في `scripts/springsec-docs/` خارج الشجرة): architecture, authentication (index/persistence/events/rememberme/session-management/logout/passwords/storage), exploits (csrf/headers), integrations (websocket/cors/observability), oauth2 (login/logout, resource-server/jwt, authorization-server كاملاً), configuration/java, test/mockmvc/setup + صفحات Spring Session (spring-security, configuration/redis, configuration/common, configurations, guides/boot-redis, web-socket, http-session).
2. **المصادر الرسمية المستخرجة والمدققة بايت-كودياً** (`scripts/verify-aud-claim/`): `spring-security-config-7.1.1-sources.jar` (RememberMeConfigurer, WebSocketMessageBrokerSecurityConfiguration, EnableWebSocketSecurity, MessageMatcherAuthorizationManagerConfiguration, AuthorizationChannelInterceptor), `spring-security-messaging-7.1.1-sources.jar`, `spring-security-oauth2-client-7.1.1-sources.jar` (OidcClientInitiatedLogoutSuccessHandler), `spring-session-core-4.1.1-sources.jar` (SpringSessionRememberMeServices, SpringHttpSessionConfiguration, DefaultCookieSerializer), و`spring-boot-autoconfigure` 4.1.1 (SecurityAutoConfiguration).
3. **قراءة كود الأمن كاملاً**: `SecurityConfig.java` (785 سطراً — السلاسل الثلاث)، `WebSocketConfig/WebSocketSecurityConfig/WebSocketCsrfConfiguration` (messaging)، `EdgeSecurityConfig/EdgeCsrfConfig` (edge)، `application.yml` (الجلسات)، المُهيّئات (العميل السري/العام)، `AccountStatusSessionInvalidator`، والاختبارات الأمنية القائمة.

## 1. البنود المطابقة أصلاً (لا تغيير — بالدليل الرسمي)

| البند | الدليل الرسمي |
|---|---|
| بنية السلاسل الثلاث (AS @Order(1) / RS @Order(2) STATELESS+JWT / form-login @Order(3)) | المرجع: Servlet Architecture + Authorization Server Configuration Model (سلسلة AS نموذجها الموثق حرفياً: `oauth2AuthorizationServer(as -> as.oidc(withDefaults()))` + `endpointsMatcher`) |
| `SpringSessionBackedSessionRegistry` + `maximumSessions` في السلسلتين | حرفياً من صفحة Spring Session — Spring Security Integration («Spring Session provides integration … concurrent session control … providing a custom implementation of Spring Security's SessionRegistry interface») |
| `HttpSessionEventPublisher` | موثق في Session Management + OIDC Logout |
| `DelegatingPasswordEncoder` | «TheDelegatingPasswordEncoder» — الافتراض الموثق في Password Storage |
| `JwtDecoder` bean: iss+aud+توقيع عبر `NimbusJwtDecoder.withJwkSource` + `DelegatingOAuth2TokenValidator` | Resource Server JWT: «Or, exposing a JwtDecoder @Bean has the same effect» |
| CORS: `CorsConfigurationSource` bean + `.cors(withDefaults())` | CORS Integration — النمط الموثق |
| CSRF للسلاسل: الافتراضي في سلسلة form-login + `ignoringRequestMatchers` للأسطح عديمة الحالة | صفحة CSRF — نمط «Ignoring certain URLs» الموثق؛ `/ws/**` وفق «SockJS & Relaxing CSRF» |
| CSRF على edge: `.spa()` | حرفياً قسم «Single-Page Applications» في صفحة CSRF |
| عميل edge بخصائص `spring.security.oauth2.client.*` (الإدارة التلقائية) | OAuth2 Login/Core: التسجيل بالخصائص هو المسار الموثق |
| `JWKSource` + prod fail-fast (المفتاح العابر = نمط «getting started» حصراً) | AS Getting Started («a minimal configuration for getting started quickly») + Core Model Components (JWKSource bean) |
| `OAuth2TokenCustomizer<JwtEncodingContext>` (roles/aud/email) | How-to: Customize JWT Claims الموثقة |
| خدمات JDBC للـAS (RegisteredClientRepository/AuthorizationService/ConsentService) | Core Model Components — الأنماط الموثقة |
| `@EnableMethodSecurity` | Method Security — النمط الموثق |
| معالجات 401/403 بـProblemDetail عبر `exceptionHandling`/`oauth2ResourceServer().authenticationEntryPoint` | درز التخصيص الموثق في Resource Server/Exception Handling |
| `AccountStatusSessionInvalidator` (انتهاء جلسة الحساب المعطّل عبر `SessionInformation.expireNow` + `ConcurrentSessionFilter`) | على الدروز الرسمية نفسها (SpringSessionBackedSessionRegistry + Session Management) |
| المراقبة (Observability) | «When an ObservationRegistry bean is present, Spring Security creates traces for: the filter chain, the AuthenticationManager, and the AuthorizationManager» — الحالة الافتراضية الكاملة مقصودة ومثبتة باختبار (G-4) |

## 2. المخالفات/الفجوات — أُغلقت كلها هذه الجلسة (الأمر: بلا استثناء)

### G-1 — أحداث المصادقة (كانت مستحيلة الرصد)

**المرجع (Authentication Events):** «For each authentication that succeeds or fails, a AuthenticationSuccessEvent or AuthenticationFailureEvent, respectively, is fired. To listen for these events, you must first publish an AuthenticationEventPublisher.» ثم عينة `AuthenticationEvents` الموثقة حرفياً (@Component + @EventListener للنوعين).
**المسار التلقائي (مقيس):** Boot 4.1.1 يهيّئ `DefaultAuthenticationEventPublisher` تلقائياً (`SecurityAutoConfiguration` بشرط `@ConditionalOnMissingBean`) — فلا حبة يدوية أصلاً.
**التنفيذ:** `AuthenticationEvents` الجديدة بنفس شكل العينة الموثقة، بأجسام PII-free (النجاح debug، الفشل warn بصنف الاستثناء ورسالته — رسائل الإطار الفاشلة بلا معرّفات أشخاص).
**التثبيت:** `AuthenticationEventsTest` (4/4): الشكل الموثق بالانعكاس + كلا النوعين الموثقين بعيناتهما (منها `UsernameNotFoundException → AuthenticationFailureBadCredentialsEvent` من جدول الاستثناءات الموثق).

### G-2 — «الجلسة حتى الخروج» بالمسار الرسمي (كانت 30 دقيقة)

**المرجع (Spring Session 4.1.1 — Spring Security Integration):** «The support: Changes the session expiration length. Ensures that the session cookie expires at Integer.MAX_VALUE.» + الإعداد الموثق حرفياً: حبة `SpringSessionRememberMeServices` (مع «optionally customize: setAlwaysRemember(true)») موصولة بـ`.rememberMe((rememberMe) -> rememberMe.rememberMeServices(rememberMeServices()))`.
**المسار التلقائي (بايت-كودياً):** وجود الحبة وحده يجعل `SpringHttpSessionConfiguration#setApplicationContext` يسلّح `CookieSerializer` الافتراضي بـ`setRememberMeRequestAttribute(REMEMBER_ME_LOGIN_ATTR)` → `DefaultCookieSerializer#getCookieMaxAge` يكتب `Integer.MAX_VALUE` عند وجود السمة؛ و`loginSuccess` يرفع الجلسة إلى `setMaxInactiveInterval(2592000)` (افتراض `THIRTY_DAYS_SECONDS` الموثق — الافتراض الرسمي نفسه هو السياسة، بلا قيمة مخصصة). **ولا يوجد مسار تلقائي للوصل نفسه**: `RememberMeConfigurer` (7.1.1) لا يلتقط حبباً (المصدر: `getRememberMeServices` يعود للافتراض إن لم يُمرَّر صراحة) — لذلك الوثيقة توثّق نداء الـDSL هذا تحديداً كالإعداد.
**التنفيذ:** الحبة (`setAlwaysRemember(true)` — صفحة الدخول الافتراضية بلا خانة remember-me، فالمفتاح الموثق يحمل سياسة كل دخول) + نداء الـDSL على سلسلة form-login.
**التثبيت:** `RememberMeSessionUntilLogoutTest` (6/6 على مستوى المكونات الموثقة) + `RememberMeSessionUntilLogoutIntegrationTest` (السياق الحي في CI: دخول form حقيقي ⇒ `Set-Cookie … Max-Age=2147483647` + الجلسة المخزنة 2592000 ثانية عبر المستودع، والخروج يُنهيها فعلاً).

### §7-أ — WebSocket: إزالة الانحراف غير الموثق (تجاوز حبة `csrfChannelInterceptor`)

**المرجع (WebSocket Security — «Disable CSRF within WebSockets»):** «At this point, CSRF is not configurable when using @EnableWebSocketSecurity … To disable CSRF, instead of using @EnableWebSocketSecurity, you can use XML support or add the Spring Security components yourself» — يليها السرد الموثق حرفياً (سلسلة `WebSocketMessageBrokerConfigurer` بمعترضَي `SecurityContextChannelInterceptor` + `AuthorizationChannelInterceptor` مع `SpringAuthorizationEventPublisher` و`AuthenticationPrincipalArgumentResolver`).
**الانحراف المقيس:** `WebSocketCsrfConfiguration` كانت تستبدل `XorCsrfChannelInterceptor` بحبة فارغة عبر نقطة الامتداد الداخلية `getBeanOrNull("csrfChannelInterceptor")` — **لا توثقها المرجعية إطلاقاً** (المرجع يقول صراحة: CSRF «not configurable» تحت الـannotation). حُذفت الحبة واختبارها كلياً.
**التنفيذ (بالسرد الموثق حرفياً):** `WebSocketSecurityConfig` أعيد بناؤها كالتوصيل اليدوي الموثق + `WebSocketAuthorizationConfig` (حبة `AuthorizationManager<Message<?>>` — «simply … publish an AuthorizationManager<Message<?>> bean» — عبر `MessageMatcherDelegatingAuthorizationManager.builder()` العام؛ حبة الـBuilder النمطية كانت تسجَّل فقط من الـannotation حسب المصدر). القواعد نفسها بلا تغيير، وترتيب [JWT, Identity, SecurityContext, Authorization] محفوظ بالـ`@Order(HIGHEST_PRECEDENCE)` على `WebSocketConfig`.
**ما يسقط مع الـannotation (موثق كأثر للسرد اليدوي):** معترض CSRF + `CsrfTokenHandshakeInterceptor` + غلاف الملاحظة `ObservationAuthorizationManager` — لا شيء منها ضمن السرد المرجعي اليدوي.
**التثبيت:** `WebSocketSecurityWiringTest` (3/3: الترتيب النسبي الحامل + **صفر معترض CSRF من أي نوع** + لا annotation متبقية) + `WebSocketSecurityConfigTest` (10/10 قواعد) + اختبارات التكامل الحية للـWebSocket في CI.

### C-7 — خروج RP-initiated الموثق على edge («should» الوثيقة)

**المرجع (OIDC Logout):** «Also, you should configure OidcClientInitiatedLogoutSuccessHandler, which implements RP-Initiated Logout, as follows: …» — بعينة الـhandler الموثقة حرفياً مع `setPostLogoutRedirectUri("{baseUrl}")` (الوثيقة: «supports the {baseUrl} placeholder»).
**المسار التلقائي:** تسجيل العميل بخصائص Boot (قائم)؛ الـhandler هو التوصيل الموثق للتوصية.
**التنفيذ:** `oidcLogoutSuccessHandler(clientRegistrationRepository)` على سلسلة edge بجسم الـ`logout` DSL. القناة المقابلة على خادم التفويض قائمة أصلاً (تسجيل `postLogoutRedirectUri` مع prod fail-fast في المُهيّئ — قيمة الإنتاج `OAUTH_POST_LOGOUT_REDIRECT_URI` يجب أن تطابق أصل edge العام).
**التثبيت (حي محلياً):** `EdgeSessionIT.oidcLogoutBuildsTheDocumentedRpInitiatedLogoutRedirect` — القياس الفعلي: `http://…/connect/logout?id_token_hint=test-id-token-value&post_logout_redirect_uri=http://localhost:8081` على مستودع تسجيل حقيقي مبني من discovery حقيقي.

### C-8 — مسح كوكيز الخروج بـClear-Site-Data

**المرجع (Handling Logouts — «Using Clear-Site-Data to Clear Cookies»):** العينة الموثقة حرفياً: `new HeaderWriterLogoutHandler(new ClearSiteDataHeaderWriter(Directive.COOKIES))` عبر `.logout((logout) -> logout.addLogoutHandler(clearSiteData))`.
**التنفيذ:** على سلسلة form-login (المصدر الوحيد للخروج الافتراضي) — يبقي كل افتراضات الخروج الأخرى كما هي («Adding Clean-up Actions»). السلوك الموثق للمكوّن: يكتب فقط على الطلبات الآمنة (`SecureRequestDataMatcher` الخاصة به) — التطوير المحلي http غير متأثر.
**التثبيت:** `ClearSiteDataLogoutHandlerTest` (2/2) + في الـIT: طلب خروج آمن ⇒ الترويسة `"cookies"` + الجلسة تُمحى من المستودع.

### G-4 — المراقبة: الافتراض الكامل الموثق مقصوداً ومختبراً

**المرجع (Observability):** الحاضر اعتماداً تلقائياً + لا `SecurityObservationSettings` = كل الملاحظات. **التثبيت:** ضمن الـIT (حبة `ObservationRegistry` موجودة + صفر حبب `SecurityObservationSettings`).

## 3. قرار «إبقاء» مدروس بدليل رسمي

**`jwtDecoder` المخصص (iss + aud + توقيع):** النمط الموثق في Resource Server JWT — «exposing a JwtDecoder @Bean has the same effect» — والمدقق الكامل (issuer + audience) **أقوى** من أي مسار أسهل، ضمن الدرز الرسمي ذاته. يُبقى.

## 4. قدرات موثقة «يمكنك» (متاحة لا مفروضة) — غير مُتبنّاة بلا مخالفة

Passkeys/WebAuthn، One-Time-Token Login، `@EnableMultiFactorAuthentication`، DPoP-bound tokens، OIDC Back-Channel Logout (`OidcBackChannelLogoutHandler` + `OidcSessionRegistry`)، `FormPostRedirectStrategy` للخروج — كلها موثقة كخيارات؛ أي تبنٍّ مستقبلي قرار منتج خلف بوابة مالك، لا فجوة امتثال.

## 5. سجل التنفيذ (قياس هذه الجلسة)

| القياس | النتيجة |
|---|---|
| تجميع المفاعل كاملاً (22 وحدة) | **BUILD SUCCESS** |
| platform-infra (اختبارات) | أخضر — من الجديد: RememberMeSessionUntilLogoutTest 6/6، AuthenticationEventsTest 4/4، ClearSiteDataLogoutHandlerTest 2/2 (وAccountStatusSessionInvalidatorTest 5/5) |
| messaging (اختبارات) | أخضر — WebSocketSecurityWiringTest 3/3، WebSocketSecurityConfigTest 10/10 |
| edge (verify: surefire + failsafe) | **BUILD SUCCESS** — EdgeSessionIT 5/5 (فيها اختبار خروج OIDC الجديد بقيمة مقيسة)، EdgeRelayIT 2/2، المجموع 7/7 |
| حارس أرقام التوثيق | 6/6 بعد تحديث «106 ملفًا» في SYSTEM.md |
| سطر الانحراف المحذوف | `WebSocketCsrfConfiguration.java` + اختبارها — صفر بقايا (`rg csrfChannelInterceptor` على الكود = صفر) |
| أثر الحدود | صفر — لا حدود Modulith/SPI/أحداث/ترحيلات/env/workflows جديدة؛ تعديلات داخل حزم قائمة + 3 ملفات اختبار صافية + 1 IT |
