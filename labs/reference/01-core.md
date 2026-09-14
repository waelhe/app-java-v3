# 01 — Spring Framework Core (isolated official reference)

> **Tree refs:** 2.1 (IoC/Beans/DI), 2.2 (Validation/Conversion), 2.3 (SpEL), 2.4 (AOP), 2.6 (Resilience/Null-safety/AOT).
> **Source:** `https://docs.spring.io/spring-framework/reference/` @ 7.0.9 — verified live 2026-09-12.
> **Format:** one extractable rule per line. Fields: `TYPE | RULE | [quote|paraphrase] | URL`.
>   - `[quote]` = verbatim official text · `[paraphrase]` = faithful restatement (URL still authoritative).
> **Isolation:** no internal repo data, no current system. `[our-convention]` marks project-level style.
> **Encoding:** UTF-8 (verified).

---

## 1. IoC Container / Beans / Dependency Injection (2.1)

### 1.1 Container & naming
- MUST | Prefer `ApplicationContext` over a plain `BeanFactory`; a plain `DefaultListableBeanFactory` does NOT auto-register `BeanPostProcessor`/`BeanFactoryPostProcessor`. | [quote] | core/beans/introduction.html · core/beans/beanfactory.html
- ATTEND | Default bean name = class simple name with first letter lower-cased (via `Introspector.decapitalize`). | [quote] | core/beans/basics.html

### 1.2 Dependency injection
- MUST | Use constructor injection for mandatory dependencies; setter/configuration methods for optional ones. | [quote] | core/beans/dependencies/factory-collaborators.html
- ATTEND | A large number of constructor arguments is “a bad code smell”. | [quote] | core/beans/dependencies/factory-collaborators.html
- MUST | Compile with `-parameters` (or use `@ConstructorProperties`) for name-based constructor resolution. | [paraphrase] | core/beans/dependencies/factory-collaborators.html
- AVOID | Constructor-based circular dependencies — throws `BeanCurrentlyInCreationException` at runtime. | [paraphrase] | core/beans/dependencies/factory-collaborators.html
- ATTEND | Setter-based circular dependencies are possible but discouraged. | [paraphrase] | core/beans/dependencies/factory-collaborators.html

### 1.3 depends-on
- MUST | Use `depends-on` to force init order; for singletons it also controls destruction order (dependents destroyed first). | [paraphrase] | core/beans/dependencies/factory-dependson.html

### 1.4 Lazy initialization
- ATTEND | A non-lazy singleton depending on a `@Lazy` bean still forces that bean's creation at startup. | [paraphrase] | core/beans/dependencies/factory-lazy-init.html
- AVOID | `@Lazy` on `@EventListener` beans — the listener will NOT be registered. | [quote] | core/beans/context-introduction.html
- ATTEND | `@Lazy` at an injection point creates a limited lazy-resolution proxy; for sophisticated lazy access prefer `ObjectProvider<T>`. | [paraphrase] | core/beans/java/bean-annotation.html

### 1.5 Autowiring
- MUST-NOT | Expect autowiring of simple properties (primitives, `String`, `Class`) — unsupported by design. | [paraphrase] | core/beans/dependencies/factory-autowire.html
- ATTEND | `autowire-candidate=false` affects type-based injection only; explicit by-name references still resolve. Since 6.2 `defaultCandidate=false` requires an extra qualifier. | [paraphrase] | core/beans/dependencies/factory-autowire.html
- MUST-NOT | Use lookup methods (`@Lookup`) on `@Bean`-produced beans; class and method must not be `final`. | [paraphrase] | core/beans/dependencies/factory-method-injection.html

### 1.6 Scopes
- MUST | Inject shorter-scoped beans into singletons via `ObjectFactory`/`ObjectProvider`/`Provider` or `<aop:scoped-proxy/>`. | [paraphrase] | core/beans/factory-scopes.html
- ATTEND | Prototype destruction callbacks are NOT invoked; the client must clean up. | [paraphrase] | core/beans/factory-scopes.html
- ATTEND | Web scopes require a web-aware context, else `IllegalStateException`. | [paraphrase] | core/beans/factory-scopes.html

### 1.7 Lifecycle / customizing beans
- AVOID | `InitializingBean`/`DisposableBean` — unnecessary Spring coupling; use `@PostConstruct`/`@PreDestroy` or POJO `init()`/`destroy()`. | [quote] | core/beans/factory-nature.html
- AVOID | Expensive work in `@PostConstruct` — runs under the singleton creation lock (deadlock risk); use `SmartInitializingSingleton.afterSingletonsInstantiated()` or `ContextRefreshedEvent`. | [paraphrase] | core/beans/factory-nature.html
- MUST | Declare `@Bean` methods returning `BeanPostProcessor`/`BeanFactoryPostProcessor` as `static` and dependency-free. | [paraphrase] | core/beans/factory-extension.html
- AVOID | Calling `getBean()` inside a `BeanFactoryPostProcessor` — premature instantiation breaks the lifecycle. | [paraphrase] | core/beans/factory-extension.html
- ATTEND | Lifecycle order: `@PostConstruct` → `afterPropertiesSet()` → custom `init()`; destroy symmetric. | [paraphrase] | core/beans/factory-nature.html

### 1.8 Annotation-based config & injection
- ATTEND | Annotation injection runs BEFORE external property injection → external config overrides annotations. | [paraphrase] | core/beans/annotation-config.html
- MUST | Keep injection-point types consistent with the declared `@Bean` return type; expose the most specific type. | [paraphrase] | core/beans/annotation-config/autowired.html
- ATTEND | Only one constructor may be `@Autowired(required=true)`; several → all must be `required=false`. | [paraphrase] | core/beans/annotation-config/autowired.html
- ATTEND | `@Autowired(required=false)`: absent dependency → method not called / field stays unpopulated; `Optional<T>`/`@Nullable` supported. | [paraphrase] | core/beans/annotation-config/autowired.html
- ATTEND | Collections/arrays/maps injected with ALL matching beans; order via `Ordered`/`@Order`/`@Priority` (`@Order` does not affect singleton startup order). | [paraphrase] | core/beans/annotation-config/autowired.html
- AVOID | Self injection except as a last resort (lowest precedence); prefer a delegate bean or `@Resource` by name. | [quote] | core/beans/annotation-config/autowired.html
- AVOID | `@Autowired`/`@Value`/`@Resource`/`@PostConstruct` inside your own post-processors — wire them explicitly. | [paraphrase] | core/beans/annotation-config/autowired.html
- MUST | Disambiguate multiple candidates with `@Primary`/`@Fallback`/`@Qualifier`; qualifiers narrow within type matches, not unique-id references. | [paraphrase] | core/beans/annotation-config/autowired-primary.html · autowired-qualifiers.html
- ATTEND | Bean name is the default qualifier fallback; injection-point-name matching needs `-parameters` (parameter names should match target bean names). | [paraphrase] | core/beans/annotation-config/autowired-qualifiers.html
- ATTEND | `@Resource` is by-name by default; with no name it falls back to a primary type match; supported only on fields and single-arg setters. | [paraphrase] | core/beans/annotation-config/resource.html
- MUST | Declare `PropertySourcesPlaceholderConfigurer` to fail fast on unresolved `${}`; its `@Bean` method must be `static`. | [paraphrase] | core/beans/annotation-config/value-annotations.html
- MUST | Mark a template parent bean `abstract="true"` (especially with a class) to avoid pre-instantiation. | [paraphrase] | core/beans/child-bean-definitions.html

---

## 2. Validation, Data Binding, Type Conversion (2.2)

- MUST | Implement Spring `Validator` with `supports()` + `validate()` and an `Errors` object; since 6.1 `validateObject(Object).failOnError(...)` for immediate checks. | [paraphrase] | core/validation/validator.html
- ATTEND | DataBinder modes: constructor binding (single public constructor, or single non-public with args) vs property binding; multiple constructors → default used. | [paraphrase] | core/validation/data-binding.html
- MUST | Keep `Converter`s thread-safe; `ConversionService` handles `null` sources directly (returns `null` without invoking the converter), so `convert(S)` receives a non-null source and throws `IllegalArgumentException` only for **unsupported/valid but not convertible** values. | [paraphrase] | core/validation/convert.html
- MUST | Use `TypeDescriptor` for parameterized/collection conversions (e.g. `List<Integer>`). | [paraphrase] | core/validation/convert.html
- ATTEND | Register a bean named `conversionService`; otherwise the legacy `PropertyEditor` system is used. | [paraphrase] | core/validation/convert.html
- AVOID | Locale-sensitive style-based date/number formatting on JDK 20+; prefer ISO or an explicit pattern. | [paraphrase] | core/validation/format.html
- MUST | Disable default formatters (`DefaultFormattingConversionService(false)`) to set a global date-time format. | [paraphrase] | core/validation/format-configuring-formatting-globaldatetimeformat.html
- ATTEND | Method validation requires `@Validated`; it is AOP-proxy based — direct field access will not work; set `adaptConstraintViolations=true` for `MethodValidationException`. | [paraphrase] | core/validation/beanvalidation.html
- MUST | Rely on the default `SpringConstraintValidatorFactory` to allow `@Autowired` in custom `ConstraintValidator`s. | [paraphrase] | core/validation/beanvalidation.html

---

## 3. SpEL (2.3)

- MUST-NOT | Evaluate untrusted SpEL with `StandardEvaluationContext`. | [quote] | core/expressions/evaluation.html
- ATTEND | `SimpleEvaluationContext.forReadOnlyDataBinding()` is best-effort, not a safety guarantee (side-effecting accessor-shaped methods are callable). | [paraphrase] | core/expressions/evaluation.html
- MUST-NOT | Reuse one parsed `Expression` across contexts with different security implications; parse a distinct `Expression` per context. | [paraphrase] | core/expressions/evaluation.html
- ATTEND | Built-in limits: `maxExpressionLength`/`maxOperations` = 10,000, `maximumBigPowerBits` = 1,000,000 (configurable via `SpelParserConfiguration` or `spring.context.expression.*`/`spring.expression.*`). | [paraphrase] | core/expressions/evaluation.html
- ATTEND | The SpEL compiler cannot compile: assignment, conversion-service, custom resolvers, overloaded operators, `Optional`+null-safe/Elvis, array construction, selection/projection, bean references. | [paraphrase] | core/expressions/evaluation.html
- MUST | Use `#{...}` for SpEL in bean definitions; predefined vars (`environment`, `systemProperties`, `systemEnvironment`) plus all beans by name. | [paraphrase] | core/expressions/beandef.html

---

## 4. Aspect-Oriented Programming (2.4)

### 4.1 Capabilities
- MUST | Spring AOP supports ONLY method-execution join points on Spring beans (no field interception). | [quote] | core/aop/introduction-spring-defn.html
- MUST | Prefer the least powerful advice that suffices (before/after over around). | [quote] | core/aop/introduction-defn.html
- MUST | Add `@Component` alongside `@Aspect` for classpath autodetection; aspects cannot be advised by other aspects. | [paraphrase] | core/aop/ataspectj/at-aspectj.html
- MUST-NOT | Use unsupported PCDs (`call`, `get`, `set`, `cflow`, `@this`, …) — `IllegalArgumentException`; supported: `execution, within, this, target, args, @target, @args, @within, @annotation, bean`. | [paraphrase] | core/aop/ataspectj/pointcuts.html
- ATTEND | Internal calls within the target are not intercepted (`this` vs `target` differ in Spring). | [paraphrase] | core/aop/ataspectj/pointcuts.html

### 4.2 Advice
- AVOID | `void` around advice (always returns `null`); MUST call `proceed()` and declare an `Object` return. | [paraphrase] | core/aop/ataspectj/advice.html
- MUST | Bind generic collection args as `Collection<?>` and check element types manually. | [paraphrase] | core/aop/ataspectj/advice.html
- MUST | Order multi-aspect advice via `Ordered`/`@Order` (lower = higher precedence); same-aspect same-type advice order is undefined. | [paraphrase] | core/aop/ataspectj/advice.html
- ATTEND | `@AfterThrowing` is not a general exception-handling callback. | [quote] | core/aop/ataspectj/advice.html

### 4.3 Styles
- MUST-NOT | Mix `<aop:config>` with the explicit `AutoProxyCreator` style. | [paraphrase] | core/aop/schema.html
- MUST-NOT | Treat XML named pointcuts as composable (more limited than @AspectJ). | [paraphrase] | core/aop/schema.html
- MUST | Prefer Spring AOP for Spring beans; AspectJ for non-managed/fine-grained/field join points. | [quote] | core/aop/choosing.html

### 4.4 Proxying mechanisms — the core rule
- MUST | JDK dynamic proxy if target implements ≥1 interface; otherwise CGLIB. | [quote] | core/aop/proxying.html
- MUST-NOT | Expect to proxy `final` classes or advise `final`/`private`/non-visible methods. | [quote] | core/aop/proxying.html
- ATTEND | Forcing CGLIB (`proxy-target-class=true`) is global across AOP/tx/async in 7.0; per-bean override via `@Proxyable(INTERFACES or TARGET_CLASS)`. | [paraphrase] | core/aop/proxying.html
- AVOID | `AopContext.currentProxy()` — “highly discouraged”, couples code to Spring AOP, requires `setExposeProxy(true)`. | [quote] | core/aop/proxying.html
- ATTEND | Self-invocation (`this.bar()`) bypasses advice; options: refactor (best), inject a self reference, or `AopContext.currentProxy()` (last resort). | [paraphrase] | core/aop/proxying.html
- ATTEND | AspectJ compile/load-time weaving has no self-invocation issue (bytecode-level). | [paraphrase] | core/aop/proxying.html
- MUST-NOT | Use `@Configurable` on classes already registered as Spring beans (double initialization). | [paraphrase] | core/aop/using-aspectj.html

---

## 5. Resilience / Null-safety / AOT (2.6)

- ATTEND | As of 7.0 core has `@Retryable` + `@ConcurrencyLimit`; `@Retryable` attempts = 1 + `maxRetries`; last exception propagated; custom predicate applied after includes/excludes. | [paraphrase] | core/resilience.html
- MUST | Enable resilience annotations via `@EnableResilientMethods` or the specific `*BeanPostProcessor` beans. | [paraphrase] | core/resilience.html
- MUST | Adopt JSpecify `@NullMarked` + explicit `@Nullable` type-use annotations; old `org.springframework.lang` annotations deprecated in 7. | [paraphrase] | core/null-safety.html
- ATTEND | JSpecify array semantics (`@Nullable Object[]` = nullable elements vs `Object @Nullable []` = nullable array); annotations not inherited on overrides. | [paraphrase] | core/null-safety.html
- MUST | Avoid circular dependencies, instance-supplier beans, and runtime-mutable bean definitions under AOT. | [paraphrase] | core/aot.html
- MUST | Expose the most precise `@Bean` return type for AOT; avoid multiple constructors and complex constructor data structures. | [paraphrase] | core/aot.html
- MUST | Register programmatic bean definitions via `BeanDefinitionRegistry`/`ImportBeanDefinitionRegistrar` for AOT. | [paraphrase] | core/aot.html
- MUST | Register runtime hints close to the requiring component (`@ImportRuntimeHints`, `@Reflective`, `@RegisterReflection`). | [paraphrase] | core/aot.html

---

## Cross-cutting gap insertions

| Gap (missed by a linear scan) | Home | Rule added |
|---|---|---|
| `@Transactional` on the Service, not the Controller | §4.4 (proxy) + 2.8 | Transaction annotation works only through the proxy; keep on service layer, called externally. |
| `@Lazy` self for internal calls | §1.8 | Self injection is a last resort; prefer `ObjectProvider`/delegate. |
| Optimistic locking `@Version` | → `04-data.md` (4.3) | (owned by data family) |
| Event naming / no `catch(Exception)` in listeners | → `05-modulith.md` (5.3/5.8) | (owned by modulith family) |

## Verification note
- All pages fetched live 2026-09-12 at Framework 7.0.9; 12 sampled URLs returned HTTP 200. Large pages retrieved via saved full-output; none failed.
- Tree coverage: 2.1 ✅ · 2.2 ✅ · 2.3 ◐ (used subset) · 2.4 ✅ · 2.5 ⭕ skipped · 2.6 ◐ · 2.7 ⭕ skipped.