# Changelog

## 1.2.2 — development (not published)

- Aligned addon starter guidance, API v15 documentation and database diagnostics/locale hints with the supported PostgreSQL backend.

- KSLAddonScope now releases one-shot Bukkit task registrations after completion, preventing long-lived addons from retaining every completed task until unload.

- Binary NBT save preflight now uses the document's configured limits, so documents intentionally created with larger SNBT limits are not rejected by the default 1 Mi-character cap.

- SQLite-to-remote database migration now commits both KSL storage tables as one transaction; any later-table or batch failure rolls back the whole migration instead of leaving earlier batches committed.
- CSV encoding now enforces per-field and total output limits incrementally, and descending sorting reverses comparator arguments without risking integer overflow.

- Database row mappers reject JDBC handles and row-bound streams instead of returning already-closed resources; `queryOne` closes its statement even when a JDBC driver rejects the one-row limit.

- Added per-script generation identities and isolated runtime scopes for each initialized version.
- A failed runtime initialization now cleans partial registrations and attempts to re-evaluate the last successfully compiled artifact in a fresh generation.
- Script registry reports the recovered script as DEGRADED when the latest update failed but the previous version was restored.
- Bound Bukkit tasks, listeners, commands, GUI, placeholders, event-bus subscriptions, worker handles/futures, particle effects, entity spawns, entity tick-index records and entity death/damage/attack/drop hooks to captured generation scopes.
- Generation-owned entity cleanup respects `removeOnReload`; a rejected spawn is removed even when it requested persistence, because it never acquired a valid owner scope.
- GUI disposal requested from a worker thread is marshalled to Bukkit's primary thread.
- Stale script contexts now reject new resource registration instead of attaching resources to a replacement generation.
- The official scheduler facade is bound to its captured generation; scheduled work is cancelled on close, and stale facades cannot schedule or cancel replacement-generation tasks.
- Entity builders reject mutations after their owning generation has unloaded, including for mobs retained with `removeOnReload=false`.
- `/ksl runtime` and watchdog diagnostics include active generation state and generation-owned runtime-scope resource counts.
- Added PostgreSQL as a first-class database backend with the official JDBC driver, native SQL quoting/UPSERT, schema migrations and verified TLS defaults.
- Added binary .nbt file loading, editing and atomic saving through the detached NBT document model.
- Added optional WorldEdit Sponge .schem clipboard capture, load, save and paste with path, file-size and block-volume limits.
- Added a full CSV standard library: bounded RFC 4180 parser/writer, editable table/row abstractions, typed accessors, filtering, projection, sorting, UTF-8 file I/O and script context helpers.
- Exposed CSV types and helper functions through KSLAPI/Public API; API compatibility level is now 14.
- Added TOML arrays-of-tables and inline-table writing, stricter numeric validation and exact integer accessors.
- Added JSONC-compatible input (comments and trailing commas), bounded Kotlin-object conversion, cycle detection and precision-preserving Long/BigInteger/BigDecimal conversion.
- JSON and TOML saves now use collision-safe temporary files, atomic replacement and per-path write serialization; UTF-8 BOM is accepted on input.
- Added regression tests for generation lifecycle, JSON/TOML round trips and concurrent atomic writes, plus JAR-content verification for new runtime classes.
- Recovery re-runs the previous script code; it does not roll back arbitrary world changes, outbound requests or committed database writes. The Bukkit owner registries are still switched by script ID, so this is not yet a gapless transactional hot swap.

## 1.2.1 maintenance update — stability and early warnings

- Added trend-aware DB queue pressure monitoring with an early growth signal, high-watermark fallback and one warning per pressure episode.
- Added regression tests for queue growth detection, alert hysteresis and invalid samples.
- IntelliJ preflight now warns about dynamic SQL string construction and blocking waits on async DB/reload futures.
- IntelliJ preflight bounds work on very large files, caches Gradle model reads by VFS modification stamps and handles nested Kotlin block comments.
- The IDEA companion remains on the 1.2.1 line. No public KSL or IDEA plugin release/tag is created by this maintenance update.

## 1.2.1 maintenance notes — database safety, reload lifecycle and NBT engine

- Remote MySQL/MariaDB connections now default to certificate and hostname verification; insecure `disable` / `trust` modes require explicit opt-in.
- Added bounded script SQL execution (timeouts, row caps, SQL length and batch size), transaction-scoped query sessions, and exact per-script sandbox database capabilities. Raw JDBC remains trusted-script-only.
- Script-owned database operations and table views are tied to runtime generations; queued work is cancelled on unload, and optimistic cache entries are invalidated when writes are cancelled or rejected.
- Added a detached, copy-on-write NBT document engine with bounded SNBT preflight and safe nested compound paths.
- The Public API version remains 12. This is maintenance work on the 1.2.1 line; it does not create a release/tag.


## 1.2.1 maintenance notes — structured logging and IDEA authoring

- Added API v12 structured logging for `.kts` scripts and Kotlin/Java addons: named loggers, TRACE/DEBUG/INFO/WARN/ERROR levels, child categories and key/value fields.
- Added administrator-controlled global minimum level, per-script/per-addon overrides and bounded message length. Code-level logger preferences cannot lower the server minimum.
- Existing `log`, `logWarn` and `logError` script helpers remain compatible and delegate to structured logging.
- Expanded IntelliJ completion for logger types, levels, child categories and addon APIs.
- Added a local warning for bare `print`/`println` calls and a `.kts` quick fix to use `scriptLogger().info(...)`.
- IntelliJ plugin remains version 1.2.1. The KSL build remains on version 1.2.1; no new release/tag is created by this work.

## 1.2.1 — Script & Addon Authoring Reliability
- Added local authoring warnings for blocking sleeps/coroutines, unconditional loops, unmanaged threads and direct Bukkit scheduler tasks.
- `/ksl validate [script-id]` now combines compiler diagnostics with authoring warnings and writes a bounded full report under `plugins/KotlinScriptLoader/reports/`.
- Validation never evaluates script code; chat output is capped while the report retains the full diagnostic set (up to 2 MB).
- Added `KSLAPI.reloadScriptAsync` for background compilation of one script; KSL API contract is now version 11.
- IntelliJ preflight now analyzes KSL addon Kotlin sources as well as .kts files and flags common blocking patterns.
- Compile-pool saturation no longer falls back to executing compilation on the submitting thread.
- Added regression coverage for 50 diagnostics, report truncation, queue saturation, authoring warnings and async reload callback semantics.

## Additional 1.2.1 maintenance changes

## Script Registry
- Расширен снимок реестра скриптов: состояния LOADED, DEGRADED, FAILED и UNLOADED, последняя ошибка, время последней попытки и наличие исходного файла.
- Реестр продолжает показывать активный runtime, даже если исходный .kts файл был удалён до следующей перезагрузки.
- Существующий четырёхпараметрический конструктор KSLScriptInfo сохранён для совместимости.

## Script Testing
- Добавлен assertAll: независимые проверки выполняются все, а отчёт объединяет несколько ошибок в один результат теста.
- Добавлена проверка элементов коллекции через assertContains.

## Performance
- KSLProfiler теперь сообщает минимальное и последнее измеренное время вместе с общим, средним и максимальным временем.
- Сброс профайлера очищает все новые метрики; существующие счётчики и методы остаются доступны.

## Межскриптовое взаимодействие
- Расширены тесты MesLib2 для передачи вызываемых функций между скриптами, проверки типа/версии и очистки экспорта при выгрузке владельца.
- Добавлен практический пример provider/consumer с директивой @requires.

## API
- Public API version: 10. Новые возможности являются аддитивными; существующие сигнатуры KSLScriptInfo сохранены.

## Scheduler API and addon ownership

- Added full `KSLTaskSpec` scheduling through owner-bound task scopes; the scope forcibly applies its own owner ID to prevent task-owner spoofing.
- Added addon-scope ownership and cancellation tests for specification-based tasks, plus IntelliJ completion examples.

## Scheduler lifecycle and factory safety

- Made custom scheduler factory registrations closeable and identity-safe, preventing addon classloader references from surviving unload or removing a replacement factory.
- Added an addon-scope factory overload that registers, creates and owns a custom scheduler engine as one lifecycle unit.
- Corrected scheduler engine ID examples to match the registry's strict identifier format.

## Public addon scheduler API

- Exposed scheduler-engine registration, discovery and creation through the public addon API.
- Extended `KSLAddonScope.create` with a scheduler ID; each addon scope can own an independent custom engine and closes its tasks before closing the engine.
- Added custom scheduler factory coverage and expanded addon/IntelliJ documentation and completion fixtures.

## Scheduler SPI

- Added the `kslshu` scheduler SPI: official KSL adapter, typed task specifications, handles and results, fixed-rate/fixed-delay execution, and an addon-extensible scheduler factory registry.
- Added the owner-bound `scheduler` API to script context; its tasks are automatically cancelled by the existing script unload lifecycle.
- Expanded IntelliJ IDEA completion fixtures and JAR verification for the new scheduler API; documented custom scheduler authoring and thread-safety requirements.

## MCP, runtime metrics and testing

- Added an optional loopback-only MCP Streamable HTTP endpoint with environment-backed Bearer authentication, strict Host/Origin checks, bounded request handling, rate limiting and read-only server/script tools. Single-script reload is opt-in and confirmation-gated; MCP is disabled by default.

- Bukkit/Paper Entity STD: added location snapshots, world-aware distance/radius checks, world-name checks and copy-safe entity teleportation.

- Script Engine 2.1: added successful compile/evaluation counters, success rates and average operation timings.
- Script Testing Framework 2.1: added tagged and parameterized tests, name/tag filtering, filtered counts and collection/numeric assertions.
- Added declarative `scriptCommand` and grouped `scriptTasks` DSLs; command registration remains script-owned and scheduled callbacks follow Bukkit thread rules.

- Expanded Script Testing Framework with suite-level lifecycle hooks, clearer assertion helpers, stack-trace diagnostics, and text/JSON reports.

# KotlinScriptLoader 1.2.1

## Performance, compilation and security

- Added a bounded LRU cache for successful script compilations, keyed by source and compilation environment; exposed hit/miss/hit-rate and eviction telemetry.
- Bounded the compilation worker queue and applied back-pressure instead of retaining unbounded reload work or rejecting submissions during queue saturation.
- Hardened local-library discovery and script watching against symbolic-link traversal outside scripts/.
- Kept retired local-library classloaders alive while existing scripts may still resolve classes; close them during orderly shutdown.
- Denied java.lang.System to strict sandboxed scripts and extended regression coverage.

## Звуковой движок, частицы и мобы

- Добавлен sound(...) с именованными ключами или Sound, громкостью, pitch-range, категорией, воспроизведением для игрока/мира/радиуса, повтором и отменяемыми звуковыми таймлайнами.
- Дополнен particle DSL: компактные particle(s), ring/box/cube/spiral, смещение и вращение формы, spread/speed, DustTransition, block/item data.
- Расширен mob builder: имена, здоровье, цели, постоянство, движение, экипировка по слотам, эффекты зелий в тиках, телепортация и базовые флаги сущности; добавлены overloads по EntityType.
- Новые API включены в default imports, IntelliJ autocomplete, тесты и отдельный справочник docs/07-sound-particles-and-mobs.md.


## Runtime API и управление памятью

- Добавлен `runtimeScope` для скриптов: закрывает пользовательские `AutoCloseable`-ресурсы и cleanup-callbacks в обратном порядке регистрации при reload/unload.
- Добавлены scoped-кэши и профилировщики; их состояние очищается вместе со скриптом.
- Исправлено накопление execution-диагностики и истории времени загрузки после выгрузки/удаления скриптов.
- Расширен `KSLCache`: атомарные map-операции, замена/удаление с возвратом старого значения, обновление TTL, снимки и фильтрация.
- Расширен `KSLProfiler`: `timed`, оператор-вызов, `measureResult` и `recordMillis`.


## Стабильность кастомных частиц и мобов

- Исправлена обработка очень долгих эффектов: счётчик тиков больше не переполняется.
- Проверяются координаты точек пользовательских форм после смещения относительно центра; некорректные координаты не передаются в Bukkit.
- Улучшена очистка индекса мобов, если PDC-тег владельца был изменён или удалён другим плагином.
- Учёт моба освобождается при смерти, даже если у него нет обработчика `onDeath`.
- Задачи `onTick` прекращаются для умерших или невалидных сущностей; повторяющиеся ошибки обработчика не оставляют бесконечный поток исключений.

---

## GUI, PersistentDataContainer и совместимость

- GUI: пагинация проверяет диапазоны, дубликаты и пересечения слотов до замены текущей конфигурации; данные списка фиксируются снимком.
- Исправлена очистка обработчиков кликов и разрешений перемещения при замене предметов через `set`, `fill` и `border`.
- Добавлены `button`, `showPage` с проверкой индекса и `refresh`; закрытие/выгрузка очищает callbacks, а старая ссылка на выгруженный GUI не открывает меню повторно.
- Добавлен типизированный PDC API для ItemStack, Player и PersistentDataHolder: String, Int, Long, Double, Boolean и UUID, с проверкой наличия и удалением.
- Новые пользовательские PDC-ключи изолированы по скриптам и вынесены в путь `scriptdata`; старые строковые ItemStack-теги и внутренние KSL-метки сохраняют прежние ключи.
- Добавлены регрессионные проверки правил разметки слотов GUI и обновлены примеры/руководства.
- Обновлён autocomplete stub для GUI: новые `button`, `pageCount`, `showPage` и `refresh` доступны в Kotlin Script IDE-подсказках.
- Идентификатор namespace скрипта для PDC теперь строится по 96-битному фрагменту SHA-256 вместо 32-битного Java hashCode; добавлен тест стабильности и длины ключа.


## Hooks, KSLRT и исправления скриптов

- Реализации встроенных интеграций и reflection bridge вынесены в `ru.privateserver.ksl.hooks`; прежние классы в корневом пакете оставлены совместимыми фасадами.
- Добавлены `KSLHookIds`, базовый `KSLPluginHook` и aliases для условной регистрации и типизированного получения hooks.
- Hook API загружается только от включённого provider-плагина; Vault-провайдеры, EssentialsX, LuckPerms, SkinsRestorer, TAB и AuthMe разрешаются динамически, чтобы переживать позднюю регистрацию и перезагрузку provider-плагинов.
- Skript introspection больше не возвращает файлы за пределами canonical `Skript/scripts`.
- Ошибки WorldGuard теперь закрывают проверку доступа (`canBuild`/`canPvp` возвращают false при сбое), а Vault отклоняет отрицательные и не finite суммы до вызова провайдера.
- ID hooks и MesLib2 namespace нормализуются независимо от системной locale; регистрация hook проверяет descriptor.
- `kslrt` получил недостающие aliases реестра профайлеров; имена профайлеров и runtime scopes ограничены 256 символами.
- `chance(percent)` в обычных скриптах теперь проверяет конечный диапазон 0..100, как и std helper.
- DSL-функция `item(...)` больше не превращает нулевое или отрицательное количество в один предмет: некорректное значение сразу отклоняется.

## Безопасность sandbox и MesLib2

- `/ksl doctor` проверяет фактический class deny/allow list и тот же путь загрузчиков, который используется при выполнении скриптов.
- Диагностика запрещённых классов ограничена 128 уникальными именами на script classloader; блокировка продолжает действовать и после достижения лимита.
- Реестр межскриптовых экспортов MesLib2/legacy export ограничен 512 записями на скрипт и 8192 записями суммарно; добавлены ограничения длины владельца, ключа, namespace, имени и версии.

## Каталог ошибок и диагностика скриптов

- Каталог расширен с 269 до 273 причин: добавлены отдельные коды для явных вызовов `error()`/`fatal()`, внутреннего сбоя компилятора и отказа evaluator при выполнении.
- `error()` и `fatal()` теперь получают собственные коды даже внутри обёрнутых исключений; остальные ошибки сохраняют код контекста.
- Исправлено ошибочное обозначение сбоя компиляционного конвейера как обычной синтаксической ошибки; ошибки evaluator теперь попадают в историю `/ksl errors`.


## Индекс скриптов и память команд

- Добавлен компактный снимок индекса: подсказки и списки скриптов используют готовые ID, кэш не удерживает File-объекты; команды reload/validation обновляют снимок сканированием каталога.
- Кандидаты команды reload (скрипты, папки и changed) вычисляются один раз на обновление индекса.
- Снижен накладной расход реестра команд: вместо отдельного concurrent hash set для каждого скрипта используются компактные списки владельцев с синхронизированным доступом.
- Обработчик автодополнения переиспользует неизменяемые наборы подкоманд и коды ошибок.

## Конкурентность и владение ресурсами

- Счётчик worker-задач на владельца теперь обновляется атомарно, чтобы конкурентная отправка/завершение задач не обходила лимит на скрипт.
- Значение ресурса и его владелец хранятся одной записью; проверка владельца и удаление теперь выполняются атомарно для сервисов, hooks, placeholders и расширений.
- Инвентарные predicate/similarity helpers одинаково игнорируют пустые и AIR-стеки.

## Публичный API и стандартная библиотека

- Добавлен `KSLAddonScope` через `KSLAPI.addonScope()`: общий жизненный цикл для Bukkit listeners, typed event handlers, event-bus subscriptions, sync/async tasks и внешних `AutoCloseable` ресурсов; очистка в обратном порядке без изменения API-интерфейса.
- Добавлен `std.validation`: композитные типобезопасные валидаторы строк и чисел, накопление ошибок, Unicode code-point length и пользовательские validation rules.
- На выключении KSL теперь вызывается uninstall() у оставшихся стандартных модулей, включая зарегистрированные вне onLoad аддона; очистка выполняется в обратном порядке и не прерывается из-за сбоя одного модуля.
- onUnload() аддона выполняется в его ownership-контексте: ресурсы, зарегистрированные во время cleanup, привязываются к правильному владельцу и тут же очищаются.

## Стандартная библиотека и публичные API

- Строковые функции теперь корректно обрабатывают Unicode whitespace; UUID-парсер отклоняет сокращённые неканонические значения.
- JSON-строковые accessor'ы не преобразуют числа и boolean в строки; добавлены required-accessor'ы, safe array access и поддержка примитивных массивов/Sequence при сериализации.
- Добавлены `lerp`, `inverseLerp`, генераторы случайных чисел и конвертация дней/недель/миллисекунд в тики; арифметика диапазонов защищена от промежуточного переполнения.
- Публичный API получил поиск игроков по UUID, online count/status и rich MiniMessage broadcast без изменения абстрактного интерфейса и версии API.
- Владение ресурсами аддонов учитывается при удалении сервисов, hooks, placeholders, context extensions и модулей; text placeholders получают корректного владельца.

## Игроки и инвентари

- Добавлена проверка вместимости предмета с учётом совместимых неполных стеков, максимального размера стека и лимита инвентаря.
- Добавлены поиск/подсчёт слотов по predicate, Player.canFit, giveOrDrop и удаление ограниченного количества предметов из storage slots.
- Player.feed теперь отклоняет NaN saturation вместо передачи невалидного значения в Bukkit.

## Торговцы, навигация мобов и частицы

- Для shopkeeper добавлены настройки awareness, столкновений, подбора предметов, свечения и гравитации.
- Билдер моба получил Paper Pathfinder: moveTo/follow/stopMoving/lookAt и настройку дверей/плавания.
- Для всех эффектов KSL введён общий бюджет 8192 частицы на тик сервера, при сохранении лимита 2048 на отдельный эффект.
- Добавлены формы частиц arc, cylinder, cone и heart.
- Добавлены короткие имена mob/mobs/shopkeeper/effect, асинхронные afterAsync/repeatAsync, алиасы событий и дополнительные единицы времени.

## 🔒 Ограничение raw-доступа в sandbox

- Свойства `BukkitScriptContext.plugin` и `database` теперь выбрасывают `SecurityException` для sandboxed-скриптов; доверенные скрипты сохраняют прежний доступ.
- Внутренние методы KSL используют скрытый JVM-synthetic accessor, чтобы не зависеть от публичного getter.
- Добавлены тесты политики доступа и синтетичности accessor; это сужает прямой доступ к инфраструктуре, но не превращает in-process sandbox в границу JVM-изоляции.


## 🧩 Public Config API для аддонов

- Добавлен `KSLAddonConfig` и `KSLAPI.createConfig(...)` для YAML-конфигов в собственной папке аддона.
- Поддерживаются типизированное чтение, изменение, сохранение и перезагрузка; значения по умолчанию загружаются из ресурсов JAR.
- Добавлен `config.yml` в ExampleAddon и обновлено руководство разработчика аддонов. Обычный Bukkit config остаётся полностью совместимым.
- Версия публичного API повышена до 8; версия плагина остаётся 1.2.1.

## 🧵 Стабильность ядра и жизненный цикл скриптов

- Регистрация и снятие Bukkit-задач синхронизированы; завершившиеся одноразовые задачи сразу удаляются из учёта.
- Периодические callbacks изолированы от ошибок: после заданного числа последовательных сбоев задача автоматически отменяется. Лимит настраивается через `runtime.task-failure-limit` (по умолчанию 3, диапазон 1–20); успешный запуск сбрасывает счётчик.
- Синхронные и фоновые перезагрузки используют общий барьер, чтобы не менять один runtime параллельно с другим.
- File watcher восстанавливается после переполнения событий, не теряет новые события во время flush и корректно останавливает поток наблюдения.
- Ошибки компиляции указывают имя `.kts` файла и строку; выключение ждёт завершения фоновой компиляции и останавливает runtime workers.
- Добавлены unit-тесты политики последовательных ошибок периодических задач.

## 📚 Расширенная документация KSL

- Добавлены отдельные руководства по быстрому старту, Script DSL, YAML/TOML/JSON, производительности и масштабированию, production-эксплуатации и практическим рецептам.
- Обновлена навигация README и docs index.
- Документация раскрывает ограничения DB queue/backpressure, Future-based SQL API, KSLRT worker pool и profiler registry, feature flags, комментарии языков и правила потоков Bukkit.
- Уточнено, что комментарии Kotlin — // и /* ... */, YAML/TOML — #, JSON не поддерживает комментарии, а точка с запятой не отключает код.
- Документы отдельно различают Script DSL, Addon Public API и внутреннюю реализацию.



## 🧩 MesLib2: управление экспортами и API cleanup

- Добавлены атомарный `exportIfAbsent`, удаление только собственных экспортов, просмотр метаданных namespace через `list` и пакетная очистка через `clear`.
- `MesLib2Info` получил составной `key`; registry расширен потокобезопасными операциями владения.
- Переработана внутренняя структура фасада: канонические операции отделены от сохранённых compatibility aliases.
- Добавлены тесты на отсутствие перезаписи, проверку владельца, сортировку списка и очистку namespace.

## 📚 MesLib2 и короткие API-имена

- MesLib2 получил короткие операции `publish/provide`, `find/resolve`, `contains` и `info`, включая overloads для namespace текущего скрипта.
- Добавлены алиасы контекста `ml2`, `lib2`, `libs`; `meslib2` сохранён.
- Public API получил алиасы `api`, `kslApi`, типы `KslApi` / `KSLPublicAPI`, а также `addImports` и `serviceOrThrow`.
- Ресурсы standard-library module теперь имеют отдельную область владения: явный unregister снимает импорты и регистрации модуля, не затрагивая другие модули аддона.
- Уточнена документация по созданию addon-библиотек и MesLib2; module planner валидирует зависимости, а runtime устанавливает зарегистрированные через KSLAPI модули и очищает их ресурсы при выгрузке.

## 🧩 MesLib2 и runtime-инструменты

- Добавлен `meslib2` для межскриптовых экспортов с namespace, проверкой типа и точным совпадением метки версии; экспорты очищаются вместе с владельцем при unload.
- Добавлены `KSLCache` (ограниченный LRU с optional TTL) и `KSLProfiler` (агрегированные тайминги и счётчик ошибок), доступные через `kslrt`.
- Добавлен явный алиас `kslapi`; прежний `ksl` и старые функции библиотек сохранены.

## 🧵 Диагностика Paper Watchdog

- Добавлен ранний мониторинг heartbeat главного потока: при длительной остановке KSL сохраняет thread dump до стандартного таймаута Paper Watchdog.
- Дамп включает стеки всех JVM-потоков и активную операцию KSL (скрипт, компиляция или задача), если она выполнялась на главном потоке.
- Дампы сохраняются в `plugins/KotlinScriptLoader/dumps/`; число файлов ограничивается настройкой `diagnostics.watchdog.max-dumps`.
- Paper Watchdog не отключается и его предупреждения не подавляются.


## ⚡ SQL query cache

- `dbExecuteYaml` и `dbQueryYaml` теперь используют атомарный кэш именованных SQL-запросов.
- Добавлены `reloadYamlSqlQueries()` для обновления кэша из текущего YAML и `reloadScriptConfig()` для безопасной перезагрузки YAML с диска без перезапуска сервера.
- Добавлены self-tests для замены снимка кэша и удаления устаревших запросов.


## 🧩 YAML Schema и SQL из YAML

- Добавлен `validateConfig { ... }` с проверкой типов, значений по умолчанию и диапазонов; можно отключить автоприменение через `applyDefaults = false`.
- Добавлены `dbExecuteYaml` и `dbQueryYaml`: именованные SQL-запросы читаются из YAML и выполняются через существующий пул с параметрами prepared statement.


## 🧬 Версионирование YAML-конфигов

- Добавлен `migrateConfig(targetVersion, versionPath, migrations)` с последовательными шагами миграции.
- Миграции выполняются на временной копии; запись и обновление живого конфига происходят только после успеха всех шагов.
- Добавлены self-tests для последовательности, no-op, пропущенных версий, более новой версии и исключений в миграциях.


## 🧰 Короткий DSL и расширенный YAML API

- Добавлены короткие алиасы `cmd` / `cmdAny`, `on<T>`, `sync`, `async`, `after` и `repeat`; исходные имена методов сохранены.
- Для дополнительных файлов из `scripts/` добавлены `editYaml(fileName) { ... }` и `saveYaml(fileName, yaml)` с атомарной записью и проверкой пути.
- Добавлены YAML-помощники `getOrDefault`, `setIfAbsent`, `string`, `int`, `long`, `double`, `bool`, `strings` и `section(path) { ... }`.


## 🧩 Команды и aliases

- Имена команд приводятся к нижнему регистру через `Locale.ROOT`; недопустимое имя команды отклоняется с понятной ошибкой.
- Aliases нормализуются, очищаются от пустых/некорректных значений, дублей и совпадений с основной командой.
- Конфликты регистрации теперь диагностируются и для aliases, а не только для основного имени.
- Хранилище зарегистрированных команд скрипта заменено на потокобезопасное множество: параллельная регистрация больше не мутирует общий `MutableList`.

## 🧵 YAML API: синхронизированное редактирование

- Добавлен `editConfig(save = true) { ... }`: блок изменения конфигурации и последующее сохранение выполняются под одной блокировкой.
- `editConfig(save = false) { ... }` позволяет сгруппировать изменения в памяти и вызвать `saveConfig()` позже.
- Старые `config` и `saveConfig()` сохранены для обратной совместимости. Прямые изменения через `config.set(...)` по-прежнему не становятся потокобезопасными автоматически — для конкурентных задач используйте `editConfig`.

## 🔕 Тише и безопаснее: ошибки в скриптах больше не выглядят как падение сервера

- **Причина «The server has not responded for 10 seconds / DO NOT REPORT THIS TO PAPER»** была в том, что `/ksl reload` компилировал скрипты на главном потоке: на слабом хосте это 10-20 секунд, и watchdog Paper печатал дамп. Теперь **стартовая загрузка тоже идёт в фоне** (`scripts.startup-load: async`, после первого тика, когда включены все аддоны), а компилятор прогревается в фоне заранее. Как это понять и что делать, если предупреждение всё же появилось: `docs/Troubleshooting.md`.
- **Ошибки скриптов теперь жёлтые (WARN), короткие и с кодом**: одинаковые строки сворачиваются (`строка 1: ... (×3)`), длинные списки обрезаются, стек урезан до трёх кадров. Вернуть красные: `logging.script-errors-as-warnings: false`.
- Ошибки компиляции теперь попадают в `/ksl errors` со списком строк (раньше там были только исключения времени выполнения).
- Игрокам с правом `ksl.admin` в чат приходит одна короткая строка, если скрипт не загрузился после перезагрузки (`notifications.admins-in-game`).
- Новое руководство `docs/Troubleshooting.md`: что значит предупреждение watchdog, типичные сообщения компилятора, что прислать, если нужна помощь.

## 🔒 Безопасность и sandbox

- **Настоящий запрет на уровне классов** (`security.sandbox-level: strict`, по умолчанию при `sandbox: true`): у скрипта в sandbox свой загрузчик, который не отдаёт `Runtime`, `ProcessBuilder`, `ClassLoader`, рефлексию, `MethodHandles`, внутренности JDK. Это нельзя обойти конкатенацией строк или `Class.forName`. Лямбды работают. Нарушение пишется с кодом `0695a`. Текстовый фильтр остался как ранняя подсказка с номерами строк (`dispatchCommand(` теперь тоже в нём).
- `/ksl doctor` проверяет на твоём сервере, что изоляция реально работает (пробный скрипт пытается загрузить `Runtime`).
- Настройки: `security.sandbox-trusted` (доверенные скрипты), `sandbox-extra-forbidden`/`sandbox-allowed`, `class-deny-extra`/`class-deny-allowed`.
- **Блокировка консольных команд** для `executeConsole`: `op`, `deop`, `stop`, `restart`, `reload`, `rl` по умолчанию (`security.console-command-blocklist`, код `5322c`). Действует всегда, не только в sandbox. Регистр, `/`, `minecraft:` и пробелы/табы не обходят список.
- **Лимиты на скрипт**: до 500 обработчиков событий и 2000 активных задач (`security.limits.*`), лишние отклоняются с кодами `6752a` и `3008a`.
- Пароль БД можно не хранить в `config.yml`: пустой `database.password` и переменная окружения `KSL_DB_PASSWORD`.
- Новое руководство `docs/Security-and-Sandbox.md`: что sandbox защищает, чего не защищает, чек-лист для боевого сервера.

## ⚡ Перезагрузка без фриза и без потери рабочей версии

- `/ksl reload` (все скрипты, один, папка, `changed`) и автоперезагрузка компилируют **в фоновом потоке**: сервер продолжает тикать, главный поток тратит время только на быструю замену.
- **Если новая версия не скомпилировалась, продолжает работать предыдущая** (раньше сначала выгружалась, потом пыталась загрузиться). Код `9065a`.
- `scripts.compile-threads: 2..8` компилирует несколько скриптов одновременно при перезагрузке многих скриптов (по умолчанию 1).
- Время «Загрузка заняла N мс» теперь считает только выполнение скрипта, без компиляции, которая тяжела у всех.
- Повторный `/ksl reload` во время работающего: «перезагрузка уже выполняется».
- Новая команда `/ksl scripts`: статус, время загрузки и число ошибок каждого скрипта.

## 🗄️ База данных: быстрее и прозрачнее

- Повторные записи одного и того же ключа (`persist`, `table().set`), пока предыдущая ещё в очереди, склеиваются в одну запись с самым новым значением. Счётчик, обновляемый каждый тик, больше не засоряет очередь БД. Порядок записей сохраняется: `delete` и `increment` разделяют записи.
- Очередь записи видна в `/ksl doctor`; при росте до 5000 операций раз в 30 секунд пишется предупреждение с кодом `1579b`.

## 🧩 API v6

- `api.scripts()` (`KSLScriptInfo`: id, loaded, loadMillis, errors), `api.reloadScript(id)`, `api.reloadScriptsAsync { }`, `api.isSandboxed(id)`, `api.isConsoleCommandBlocked(cmd)`.
- Темы шины событий `ksl.script.loaded`, `ksl.script.failed`, `ksl.script.unloaded` (payload `KSLScriptEvent`); алиасы `api.onScriptLoaded { }`, `onScriptFailed { }`, `onScriptUnloaded { }`.
- Алиасы `api.warn(code, msg)` и `api.severe(code, msg)`: сообщения аддона с кодом из каталога, чтобы `/ksl error` мог их объяснить.
- `.autocomplete.kts` больше не перезаписывается, если его содержимое не изменилось (IDE не переиндексирует его при каждом reload).

## 🔢 Коды ошибок

Каждая ошибка и предупреждение в консоли теперь с кодом вида `6156a`: четыре цифры и буква-категория. Цифры намеренно разбросаны (не 0001, 0002...), буква означает категорию: `a` скрипты, `b` база данных, `c` команды и события, `d` эффекты, `e` мобы, `f` GUI, `g` интеграции и Discord, `h` аддоны и API, `i` конфиг и файлы, `j` задачи, `k` плейсхолдеры, `l` система. Все 81 код собраны в отдельном файле `KSLError.kt` (название, категория, что делать) и в `docs/Error-Codes.md`. `/ksl error <код>` расшифровывает код (с Tab-подсказкой), `/ksl errors` показывает код последней ошибки.

## 📁 Папки со скриптами и раздельная перезагрузка

- Скрипты ищутся и в подпапках `scripts/` (до 8 уровней; папки и файлы на `.` и папки на `_` пропускаются). Имя скрипта теперь путь без `.kts`, например `economy/shop`: два `shop.kts` в разных папках больше не конфликтуют. Отключить: `scripts.recursive: false`.
- `/ksl reload <скрипт>`, `/ksl reload <папка>/` (всё содержимое папки в порядке зависимостей), `/ksl reload changed` (компилируются только новые и изменённые файлы, удалённые выгружаются). Tab подсказывает скрипты, папки и `changed`.
- `scripts.auto-reload: true` включает наблюдение за файлами: сохранил `.kts`, перезагрузился ровно этот скрипт (удалил файл, скрипт выгрузился). Задумано для разработки.
- `// @requires: shop` находит `economy/shop`, если такое имя одно.
- Внимание: перенос скрипта в другую папку меняет его имя, а значит и пространство его `persist`-данных. Старые данные останутся в базе под прежним именем.
- Ошибка пути (`reload ../x`) блокируется, имя скрипта не может выйти за пределы `scripts/`.

## ⚡ Оптимизация

- `/ksl reload changed` и автоперезагрузка вместо полной перезагрузки: компиляция Kotlin главная статья затрат.
- Кэш значений `table()` (SQLite: включён, MySQL: выключен, `database.cache`): повторные чтения не обращаются к БД. Запись обновляет кэш сразу.
- Эффекты частиц больше не создают `Location` на каждую точку на каждый тик.
- События смерти, урона и удаления мобов ничего не делают, пока нет мобов со скриптовым поведением; при загрузке чанков PDC читается только у живых сущностей.
- `message()`/`richMessage()` без `@` и `%` в тексте пропускают все замены и поиск незаменённых токенов.

## 🧩 Свои текстовые плейсхолдеры (API v5)

- `textPlaceholder("rank") { player -> "Admin" }` в скрипте даёт `@rank@` в `message()`/`richMessage()`/`messageList()`; в `config.yml` секция `text-placeholders:` для постоянных значений (`server_name: "Мой сервер"`).
- Из аддонов: `api.registerTextPlaceholder(key) { player -> }` (алиас `api.textPlaceholder`). `KSL.API_VERSION = 5`.
- Встроенные имена (`player`, `world`, `online`, `maxplayers`, `health`, `ping`) заняты. Исключение в резолвере логируется с кодом и даёт пустую строку.

## 📖 Документация

Новое подробное руководство `docs/Database-Guide.md`: SQLite или MySQL/MariaDB, установка сервера БД (Windows, Linux, Docker), создание базы и пользователя в phpMyAdmin пошагово, настройка, проверка, перенос данных, таблица типичных ошибок, кэш, безопасность и бэкапы.

## 🚚 Перенос данных в MySQL/MariaDB

`/ksl dbmigrate` показывает, сколько записей в `ksl.db`, а `/ksl dbmigrate confirm` копирует `ksl_persist` и `ksl_table` в настроенную базу. Это идемпотентно (upsert, можно повторять), `ksl.db` не изменяется, работает вне главного потока и коммитит пачками по 500 строк. Ключ длиннее 190 символов останавливает перенос с понятным сообщением, а не с ошибкой MySQL на полпути.

## 🔌 API v4

- `api.registerPlaceholder(key) { player -> }` / `api.unregisterPlaceholder(key)`: `%ksl_<key>%` из аддонов (алиас `api.placeholder(...)`; в скриптах тоже `placeholder(...)`). Снимается вместе с аддоном. `KSL.API_VERSION = 4`.

## 🐛 Исправления (по логу сервера)

- **`/ksl doctor` вешал сервер**: тест компилятора Kotlin выполнялся на главном потоке, первый запуск занимал 10+ секунд (Paper писал «server has not responded», на слабом хосте это дошло бы до watchdog). Теперь `doctor` и `validate` работают в фоне, результат приходит в чат; загрузка и проверка скриптов берут общую блокировку.
- **Сырые `§9§l║§r` в консоли**: цветовые коды вырезаются из лога плагина.
- **Голый `/ksl` перезагружал все скрипты**: теперь показывает справку; перезагрузка только через `/ksl reload`. Добавлена `/ksl help`.
- **Discord:** при ошибке отправки в лог попадал полный URL вебхука вместе с токеном (теперь только имя канала); лимиты Discord (2000 символов в сообщении, 256/4096 в embed) обрезаются вместо ответа 400; `@everyone`/`@here` из текста больше не пингуют (параметр `allowMentions = true`, если пинг нужен); при HTTP 429 сообщение отправляется повторно после `Retry-After`; JSON корректно экранирует управляющие символы.
- **Плейсхолдеры:** исключение в резолвере больше не попадает в PlaceholderAPI (логируется как ошибка скрипта); выгрузка скрипта снимает только его плейсхолдеры (раньше могла снять чужой с тем же ключом); при конфликте ключей пишется предупреждение; ключи нечувствительны к регистру; expansion снимается при остановке плагина.

## 🗄️ MySQL и MariaDB (управление через phpMyAdmin)

- Новая секция `database:` в `config.yml`: `type: sqlite | mysql | mariadb`, `host`, `port`, `name`, `user`, `password`, `ssl-mode`, `pool-size`. По умолчанию всё как раньше: SQLite, ничего настраивать не нужно.
- Один драйвер (MariaDB Connector/J) работает с обоими серверами. Таблицы `ksl_*` создаются сами при первом запуске: бинарная сортировка ключей (`Foo` и `foo` различаются, как в SQLite), `utf8mb4`, InnoDB; версия схемы хранится в `ksl_meta`.
- Различия диалектов собраны в одном месте (`KSLDialect`): upsert (`ON CONFLICT` / `ON DUPLICATE KEY`) и кавычки идентификаторов. Колонка `key` в `ksl_persist` теперь всегда в кавычках: `KEY` зарезервировано в MySQL.
- При ошибке подключения в лог пишется причина без пароля и подсказка (host/port/user, создана ли база, права пользователя). `/ksl doctor` показывает тип БД.
- Требуется MySQL 5.7+ или MariaDB 10.2+. Перенос данных из `ksl.db` на сервер пока ручной.

## 🧰 Алиасы для авторов аддонов (36 штук, `import ru.privateserver.ksl.addon.*`)

`simpleAddon`, `KSLBaseHook`, `provide/revoke/hasService/serviceOr`, `imports`, `whenReady`, `isLoaded/findAddon/addonIds`, `requireApi`, `kslVersion`, `reload`, `contextExtension`, `hasHook/hookIds/hookIf`, `shape`, `entityAttribute`, `shapeOf`, `ringPoints`, `parseTemplate`, `attr/attrBase`, `on/emit`, `table`, `log/warn/severe`, `sync/async/later/every`, `listen`; параметры форм `num/int/long/bool/str`. Таблица в `docs/Addon-Development-Guide.md`.

## 🐛 Исправления GUI, эффектов и мобов

- **GUI:** двойной клик по разрешённому слоту собирал предметы из заблокированных слотов (дюп); `rows` вне 1..6 ронял `createInventory`; `gui.open()` из async-потока (например из `onChat`) бросал исключение, а вызов внутри клика мог терять предмет на курсоре, теперь открытие откладывается на тик; при выгрузке скрипта обработчики открытых GUI очищаются (раньше держали старый classloader); слот вне диапазона и пустой `slots` в `paginate` теперь предупреждают; добавлены `pageCount` и `showPage(i)`.
- **Эффекты:** пользовательская форма могла вернуть неограниченное число точек, теперь не более 720 за тик.
- **Мобы:** `equip(...)` молча игнорировал опечатки в слотах и не-предметы, теперь предупреждает; добавлен `dropChance` (по умолчанию как раньше, 0f — экипировка не выпадает).

## 🔌 API v3: хуки и движки расширяются из аддонов

- **Хуки.** `KSLHook` (`hookId`, `pluginName`) + `api.registerHook(...)`, `api.hook<T>("id")`, `api.hooks()`, `api.isPluginEnabled(...)`. Встроенные Vault, LuckPerms, Essentials, WorldEdit, WorldGuard, SkinsRestorer тоже лежат в реестре; в скриптах: `hook<T>("id")`, `requireHook<T>("id")`, `hasHook("id")`. Хуки аддонов видны в `/ksl doctor`.
- **Формы частиц.** `api.registerEffectShape("spiral") { params, durationTicks -> KSLEffectShape }`; в скрипте: `shape("spiral", "radius" to 2.0)`. Хелперы `params.num(...)` и `params.int(...)`.
- **Атрибуты моба.** `api.registerEntityAttribute("SIZE") { entity, value -> }`; в шаблоне: `spawnCustomEntity(loc, "TYPE:SLIME SIZE:4")`.
- Всё зарегистрированное внутри `onLoad` снимается вместе с аддоном. `KSL.API_VERSION = 3`.

## ✨ Эффекты частиц

- Формы: `sphere(radius, points, spinDegreesPerTick)`, `square(size, pointsPerSide)`, `point()`, `custom { tick, center -> List<Vector> }`, `shape("имя", ...)`.
- `color(r, g, b)` / `color("#ff8800")` для `Particle.DUST`; `data = ...` для частиц с данными (раньше `DUST` падал без `DustOptions`; теперь понятная ошибка сразу при вызове).
- `stopWhen { условие }` (раньше пользовательского условия остановки не было) и `onEnd { }`.

## ✨ Мобы

- Новые ключи шаблона: `SPEED` (множитель), `DAMAGE`, `ARMOR`, `KNOCKBACK_RESIST`, `GLOW`, `SILENT`, `INVULNERABLE`. Неизвестный ключ шаблона теперь даёт предупреждение со списком допустимых (опечатки больше не молчат).
- Билдер: `attribute(name, v)`, `speed(x)`, `damage(v)`, `armor(v)`, `potion(type, seconds, amplifier)`, `glow()`, `silent()`, `invulnerable()`, `drops(items, exp, keepDefault)`.
- `behavior { onDamaged { mob, event -> }; onAttack { mob, event -> } }`.

## ✨ Ещё алиасы

`onJoin`, `onQuit`, `onPlayerDeath`, `onRespawn`, `onBlockBreak`, `onBlockPlace`, `onInteract`, `onChat`; `"<red>текст".mm`, `player.title(...)`, `player.sound("entity.player.levelup")`, `player.heal()`, `loc(world, x, y, z)`, `location.offset(...)`, `location.playersNear(r)`.

## 🐛 Исправления

- `/ksl doctor` говорил «не установлен» про Essentials, даже когда плагин стоял, а хук не поднялся (искал плагин по имени «EssentialsX»).

## 🧩 API для аддонов (KSL.API_VERSION = 2)

- `api.service<T>("key")` / `api.requireService<T>("key")` — типизированный доступ к сервисам; при несовпадении classloader'ов в лог пишется понятная подсказка.
- `api.subscribe(topic) { payload -> }` / `api.publish(topic, payload)` — шина событий между аддонами и скриптами без жёсткой зависимости. Подписки аддона снимаются при его выгрузке, подписки скрипта — при выгрузке скрипта (`subscribe`/`publish` доступны и в скриптах).
- `api.reloadScripts()`, `api.apiVersion`, `KSLAddon.minApiVersion` (аддон не загрузится на слишком старом KSL).
- `KSLContextExtension.onContextCreated/onContextDestroyed` имеют пустую реализацию по умолчанию.
- Исправлено: вложенная загрузка аддонов сбрасывала владельца (`currentlyLoading`); аддон с упавшим `onLoad` оставался «загруженным»; повторная регистрация того же `addonId` не выгружала старый экземпляр; колбэки `onAddonReady` теряли владельца и переживали выгрузку; при перезаписи сервиса чужой владелец мог удалить новый сервис; выгрузка при остановке шла в произвольном порядке (теперь — обратный порядку загрузки).

## ✨ Алиасы и упрощения для скриптов (импортируются автоматически)

`on<T> { }`, `command(...)`, `commandAny(...)` (консоль тоже), `later`, `laterAsync`, `sync`, `async`, `cancelTask(id)`, `5.sec` / `2.mins` (в тиках), `msg(...)`, `actionBar(...)`, `item(material, name, lore, amount)`, `giveItem(stack)` (остаток падает под ноги), `online`, `playerNamed(...)`, `chance(percent)`, `cfg(path, default)`, `log` / `logWarn` / `logError`, `gui { button(slot, item) { player -> } }`, `onEvent(ignoreCancelled = ...)`.

## 🐛 Исправления (продолжение)

- **GUI:** shift-клик и двойной клик в инвентаре игрока вытаскивали предметы из GUI и клали их внутрь; перетаскивание (drag) по слотам GUI не блокировалось; `Inventory.getHolder()` на каждый клик по любому контейнеру делал снапшот блока (теперь `getHolder(false)`).
- **Задачи:** `runSync`/`runAsync` не отслеживались и переживали выгрузку скрипта; исключение в `delay`/`every` печатало сырой стектрейс Bukkit при каждом срабатывании (теперь через `KSLErrors`).
- **Команды:** после регистрации и выгрузки команд клиентам отправляется обновлённое дерево команд (`updateCommands`), иначе удалённые команды оставались видны, а новые не подсказывались.

## 🐛 Исправления

- **SQLite:** WAL + `busy_timeout` (больше нет `database is locked`), `leakDetectionThreshold` для скриптов, которые не закрывают соединения; чтения `table()` идут через очередь БД и видят предыдущие `set`/`delete`; `increment()` не зависает при вызове из `dbQuery`/`dbExecute` и не сбрасывает счётчик, записанный как `Int`.
- **`persist` / `table().set`:** неподдерживаемый тип теперь сразу бросает исключение, а не теряется молча; `null` в nullable-свойствах больше не даёт NPE.
- **YAML:** битый `<script>.yml` не перезаписывается дефолтами (копия `*.broken-<время>`); запись атомарная; `loadYaml()` не выходит за пределы `scripts/`.
- **Эффекты:** исключение в `stopCondition` не спамит лог каждый тик; `count` ограничен 0..100.
- **Мобы:** `spawnCustomEntity` не оставляет недособранного моба при ошибке; невалидный `HP` и неизвестный `TYPE` не ломают спавн молча; `onDeath`-хуки привязаны к скрипту и снимаются при выгрузке; `onTick` отключается после 5 ошибок подряд; эфемерные мобы, оставшиеся в мире после рестарта, удаляются при загрузке чанка.
- **Задачи скриптов:** список id задач потокобезопасен и чистится от завершённых; `unloadAllScripts` теперь выгружает и скрипты, у которых только плейсхолдеры/мобы.
- **Кулдауны:** `tryUse` атомарен, просроченные ключи вычищаются.
- **Sandbox:** сканер игнорирует комментарии корректно (раньше `/* x */ Runtime.getRuntime()` проходил), учитывает backticks и переносы строк, список паттернов расширен. Это по-прежнему статический фильтр, а не изоляция.

# KotlinScriptLoader 1.2.0

Движок частиц-эффектов и движок кастомных сущностей — оба спроектированы так, чтобы не тормозить сервер при масштабе и чисто убираться при `/ksl reload`.

## ✨ Новое

### Эффекты (`playEffect`)

```kotlin
player.location.playEffect(Particle.FLAME) {
    circle(radius = 2.0, stepDegrees = 15.0)
    durationTicks = 60L
    intervalTicks = 2L
}
```

- Формы: `circle(radius, stepDegrees)`, `helix(radius, turns, height)` (двойная спираль ДНК), `lineTo(target, points)` (луч/линия до подвижной цели).
- Вся геометрия (`sin`/`cos`) считается в скомпилированном ядре плагина (`KSLEffectShapes`), а не в `.kts` — скрипт только передаёт конфигурацию.
- Анимация идёт через `Bukkit.getScheduler().runTaskTimer` — никакого `Thread.sleep`, сервер не виснет.
- `Entity.playEffect(particle, follow = true) { }` — эффект следует за игроком/мобом каждый тик; при `follow = false` — центр фиксируется в момент вызова.
- Follow-эффект автоматически останавливается, если сущность, за которой он следует, умерла/удалена — не крутится вокруг трупа до конца `durationTicks`.
- Таски эффектов трекаются через тот же механизм, что и `every()`/`delay()` — автоматически отменяются при `/ksl reload <script>`, без отдельного кода очистки.

### Кастомные сущности (`spawnCustomEntity` + `behavior`)

```kotlin
val boss = spawnCustomEntity(location, """TYPE:ZOMBIE NAME:"<red>Босс</red>" HP:200 AI:TRUE""") {
    equip("HAND:DIAMOND_SWORD HELMET:NETHERITE_HELMET")
}

boss.behavior {
    onTick(period = 20L) { entity -> /* урон по area раз в секунду */ }
    onDeath { entity, killer -> broadcastMM("<gold>${entity.name} повержен!") }
}
```

- Строковый шаблон `TYPE:.. NAME:.. HP:.. AI:.. BABY:..` — свой парсер (`KSLTemplateParser`), поддерживает значения в кавычках для текста с пробелами (`NAME:"<red>Big Boss</red>"`).
- `equip("HAND:.. HELMET:..")` — тот же парсер для экипировки.
- `onTick(period) { entity -> }` — привязанная к мобу повторяющаяся задача, сама останавливается при смерти/невалидности сущности.
- `onDeath { entity, killer -> }` — один глобальный listener на весь плагин (не по одному на моба) — O(1) диспетчеризация независимо от количества заспавненных мобов.
- Владелец (`scriptName`) записывается прямо в моба через `PersistentDataContainer` — при `/ksl reload <script>` все мобы этого скрипта автоматически теряют привязанное поведение и (по умолчанию) удаляются из мира; поведение настраивается через `removeOnReload = false`, если моб должен просто "замолчать", но остаться.

## 🛠 Исправления

- `HP` в шаблоне `spawnCustomEntity` ставил здоровье через `Attribute.GENERIC_MAX_HEALTH` — константа переименована Paper'ом в `MAX_HEALTH` (~1.21.3), а на ещё более новых сборках `Attribute` мигрирует с enum-констант на Registry-based доступ целиком, и обеих констант там уже нет. Заменено на `Damageable.setMaxHealth(Double)` — deprecated в доках, но стабильно присутствует с очень старых версий Bukkit и не зависит от текущей миграции `Attribute`.
- Утечка памяти: UUID заспавненных сущностей оставался в индексе владения навсегда, если моб умирал естественно (не через `/ksl reload`) — снятие было привязано только к выгрузке скрипта. Добавлен listener на `EntityRemoveEvent`, ловящий любую причину удаления сущности.
- `world.spawnEntity(location, type) as LivingEntity` мог упасть с сырым `ClassCastException`, если шаблон указывал не-живую сущность (`TYPE:ARROW` и т.п.), оставляя незаспавненную-но-неотслеживаемую сущность в мире. Заменено на safe-cast с явной ошибкой и автоматической очисткой ошибочного спавна.
- **Критично**: `circle(radius, stepDegrees = 0)` мог заморозить сервер по OOM — деление на ноль в вычислении количества точек давало `Int.MAX_VALUE`, и каждый тик эффекта пытался бы аллоцировать список на 2+ миллиарда элементов. Аналогично `lineTo(target, points)` не имел верхней границы на `points`. Обе формы теперь ограничены разумным потолком (`coerceIn`).
- Эффект с отрицательным `durationTicks` не останавливался вообще — крутился до явного `stopEffect()` или рестарта сервера вместо того, чтобы сразу завершиться. Исправлена логика проверки истечения длительности.
- `intervalTicks`/`period` у эффектов, поведения сущностей и уже существовавших `every()`/`everyAsync()` не валидировались перед передачей планировщику — значение `0` или отрицательное кидало малопонятный `IllegalArgumentException` вместо ясного сообщения. Добавлена единая валидация с понятным предупреждением в лог.
- Кулдауны (`cooldown()`) не чистились ни при `/ksl reload`, ни при полной выгрузке скрипта — небольшая утечка памяти и логическая проблема (старые метки времени от предыдущей версии скрипта продолжали действовать после reload). Добавлена очистка по скрипту.

---

# KotlinScriptLoader 1.1.0

Главный релиз после 1.0.0 — Addon API, GUI, межскриптовые библиотеки, состояние между рестартами, интеграции с WorldEdit/WorldGuard/Vault/SkinsRestorer/Discord, и много работы над надёжностью.

## ✨ Новое

### Addon API
- `KSLAddon` / `KSLAPI` / `KSLContextExtension` — сторонние Bukkit-плагины могут регистрировать свои сервисы, импорты и хуки жизненного цикла скрипта, доступные потом любому `.kts` через `service<T>("key")`.
- `dependsOn` — аддон может объявить зависимость от другого KSL-аддона.
- `onAddonReady(id) { addon -> ... }` — безопасная привязка к другому аддону независимо от порядка загрузки Bukkit-плагинов.
- Автоматическая очистка: сервисы/импорты/расширения контекста, которые аддон зарегистрировал, снимаются сами при `unregisterAddon`, даже если автор аддона забыл отписать их вручную.

### GUI
- `gui(title, rows) { }` — DSL для инвентарь-меню: `set`, `fill`, `border`, `paginate` (с автокнопками next/prev), `onClose`.
- Один-единственный listener на весь плагин вместо отдельного на каждое меню — клик обрабатывается за O(1) независимо от количества открытых GUI.
- Анти-дюп по умолчанию: клик по слоту меню отменяется, если явно не разрешено (`allowItemMovement`).
- Открытые GUI скрипта принудительно закрываются при его выгрузке — правка скрипта с открытым у игроков меню больше не оставляет "мёртвые" обработчики.

### Состояние и данные
- `persist(key, persistent = false) { default }` — состояние скрипта, переживает `/ksl reload`; с `persistent = true` переживает полный рестарт сервера (SQLite).
- `table(name)` — общая key-value таблица между разными скриптами, с `set/get/getInt/getLong/getDouble/getBoolean/increment/delete/row`, переживает рестарт.
- `export(key, value)` / `library<T>(key)` / `requireLibrary<T>(key)` — один `.kts` может отдавать данные/функции другому. Порядок загрузки скриптов теперь управляется директивой `// @requires: name1, name2` (топологическая сортировка).

### YAML
- `config.message(path, vararg "key" to value)` / `messageList` / `richMessage` — сообщения с `@placeholder@`-подстановками и авто-покраской `&` → `§`.
- Перегрузка с `Player` — автоматически заполняет `@player@`, `@world@`, `@online@`, `@maxplayers@`, `@health@`, `@ping@`.
- Прозрачная интеграция с PlaceholderAPI: `%papi_placeholder%` резолвится в той же строке, что и свои `@custom@`.
- `config.getOrSetDefault(path, default)` — конфиг скрипта сам дозаполняется новыми ключами при обновлении.
- Предупреждение в лог при незаменённом `@placeholder@` (типичная опечатка) — один раз на комбинацию скрипт/путь/плейсхолдер.

### Интеграции
- **Vault** — экономика и права (`vaultBalance`, `vaultDeposit`, `vaultHasPermission`, `vaultGroup` и т.д.)
- **SkinsRestorer** — `setSkin`, `setSkinRaw`.
- **WorldEdit** — доступ к выделению игрока (`weSelection`, `weSelectionVolume`, `weFillSelection`).
- **WorldGuard** — регионы и **любые** флаги по имени, включая кастомные от сторонних плагинов (`wgRegionsAt`, `wgCanBuild`, `wgFlag`).
- **Discord** — вебхуки прямо из скриптов (`discordSend`, `discordEmbed`), настраиваются в `config.yml`.
- LuckPerms, EssentialsX, PlaceholderAPI — как и раньше, все софт-зависимости.

### Команды и разработка
- `/ksl reload <имя_скрипта>` — точечная перезагрузка одного файла без остальных.
- `registerCommand(..., permission = "...", tabComplete = { player, args -> ... })` — свои права и автодополнение у команд скрипта.
- `/ksl validate` — dry-run компиляция всех скриптов без их выполнения.
- `/ksl doctor` — живая диагностика: реально компилирует и выполняет тестовый скрипт прямо в момент вызова, проверяет БД, `commandMap`, все интеграции.
- `/ksl errors` — счётчик и последняя ошибка скриптов с последнего старта.
- `/ksl libraries` / `/ksl addons` (с зависимостями) / `/ksl services`.
- Sandbox-режим (`config.yml: sandbox: true`) — блокирует опасные Java API в скриптах.

## 🛠 Исправления

- Скрипты не видели даже собственные импорты — `dependenciesFromCurrentContext()` брал classpath из текущего потока, а не плагина. Заменено на явную привязку к classloader'у плагина.
- `Cannot inline bytecode built with JVM target 21 into bytecode built with JVM target 1.8` — добавлен явный `-jvm-target 21` в конфигурацию компиляции скриптов.
- `UnsupportedOperationException: remove` при `/ksl reload` на некоторых сборках Purpur — двухступенчатый фоллбэк снятия команд (публичный API → reflection).
- Исключение в обработчике события/команды/GUI-клика раньше либо падало в общий Bukkit-лог без привязки к скрипту, либо (для команд) вообще не показывалось игроку. Теперь везде отдельный try-catch с логированием по имени скрипта и понятным сообщением игроку.
- `dbExecute`/`dbQuery`/`persist`-запись не гарантировали порядок выполнения (обычный Bukkit async scheduler крутит таски без строгой последовательности) — переведены на выделенный однопоточный executor.
- Все хук-классы (Vault/LuckPerms/EssentialsX/SkinsRestorer/WorldEdit/WorldGuard) обёрнуты в защиту — сбой стороннего плагина в рантайме даёт `null`/`false`, а не падение скрипта.
- `ScriptValidator`/`/ksl validate` не проверял sandbox-правила и мог упасть целиком на одном проблемном файле — исправлено, правила sandbox теперь общие для загрузки и валидации.
- `Cannot select module with conflict on capability 'org.bukkit:bukkit'` при подключении VaultAPI/WorldEdit/WorldGuard — исключены транзитивные зависимости на устаревший `org.bukkit:bukkit`.
- `Platform declaration clash` между `service<T>()` и нетипизированным `service()` — убран лишний overload.
- Kotlin `Unresolved reference` из-за `$var` перед кириллицей в строковых шаблонах (кириллица — валидный символ для идентификаторов) — переменные обёрнуты в `${}`.
- `ConcurrentHashMap` + оператор `in` — компилятор не может однозначно выбрать между `containsKey` и унаследованным от `Hashtable` `contains(value)` ([KT-18053](https://youtrack.jetbrains.com/issue/KT-18053)) — заменено на явный `containsKey`.

## 📦 Прочее

- `.gitignore` для Gradle/Kotlin/IntelliJ-проекта.
- Реальные unit-тесты (28 проверок) для чистой логики без Bukkit-зависимостей — `KSLPersistCodec`, `KSLCooldownStore`, `KSLScriptOrder`; можно прогнать одним `kotlinc` без Gradle и сети.
- Полный гайд по разработке аддонов: `docs/Addon-Development-Guide.md`.
- Примеры: GUI-магазин с пагинацией, межскриптовые библиотеки (`kd.kts`/`pvp.kts`), WorldEdit/WorldGuard, второй аддон с `dependsOn`/`onAddonReady` (`BonusAddon`), YAML-сообщения + общая таблица (`stats.kts`).
