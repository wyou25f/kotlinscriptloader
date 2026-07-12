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
