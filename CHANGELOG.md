# Unreleased

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
