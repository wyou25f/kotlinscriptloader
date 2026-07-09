# KotlinScriptLoader (KSL) — полная документация

> Версия документа соответствует KSL 1.0.0. Этот файл — источник истины по всему проекту: что это, как устроено внутри, как писать скрипты, как писать аддоны, как контрибьютить в сам плагин.

## Содержание

1. [Что такое KSL](#1-что-такое-ksl)
2. [Архитектура проекта](#2-архитектура-проекта)
3. [Установка и сборка](#3-установка-и-сборка)
4. [Жизненный цикл плагина](#4-жизненный-цикл-плагина)
5. [Файлы и структура данных](#5-файлы-и-структура-данных)
6. [Написание скриптов (.kts) — полный DSL](#6-написание-скриптов-kts--полный-dsl)
7. [GUI DSL](#7-gui-dsl)
8. [Persist API — состояние между reload и рестартами](#8-persist-api)
9. [Интеграции](#9-интеграции)
10. [Sandbox и безопасность](#10-sandbox-и-безопасность)
11. [Команды и права](#11-команды-и-права)
12. [Addon API — как расширять KSL](#12-addon-api)
13. [Внутреннее устройство — для контрибьюторов](#13-внутреннее-устройство)
14. [Известные технические ловушки](#14-известные-технические-ловушки)
15. [Диагностика проблем](#15-диагностика-проблем)
16. [Как контрибьютить](#16-как-контрибьютить)

---

## 1. Что такое KSL

**KotlinScriptLoader (KSL)** — плагин для Paper 1.21.1 (Java 21), который компилирует и выполняет `.kts`-файлы (Kotlin Script) прямо на сервере, без пересборки и рестарта. Идея похожа на Skript, но вместо собственного псевдоязыка — настоящий Kotlin с полным доступом к Bukkit/Paper API, JDBC, Adventure/MiniMessage и всему, что есть в classpath сервера.

Кратко, что даёт KSL "из коробки":

- **Hot-reload скриптов** — `/ksl reload` компилирует и подгружает всё из `scripts/*.kts` без рестарта сервера.
- **DSL для типичных задач** — события, команды, шедулер, конфиги, БД, MiniMessage — всё через простые функции внутри скрипта, без ручной возни с `Bukkit.getPluginManager().registerEvent(...)`.
- **Интеграции**: PlaceholderAPI, LuckPerms, EssentialsX, Vault, SkinsRestorer, Discord-вебхуки.
- **GUI DSL** — построение инвентарных меню с пагинацией без ручной работы с `InventoryClickEvent`.
- **Persist API** — переменные, которые переживают `/ksl reload`, а при желании и полный рестарт сервера.
- **Addon API** — сторонние Bukkit-плагины могут регистрировать в KSL свои сервисы, доступные из любого `.kts`-скрипта.
- **Sandbox** — опциональная проверка скриптов на опасные Java-паттерны перед компиляцией.

KSL **не** песочница в смысле "изолированная JVM" — скрипт выполняется в том же процессе, с теми же правами, что и сам плагин. Sandbox (раздел 10) — это защита от случайных опасных вызовов, а не от злонамеренного кода.

---

## 2. Архитектура проекта

Всего в проекте три условных слоя:

```
┌─────────────────────────────────────────────────────────┐
│  Слой 1: Ядро плагина                                    │
│  KotlinScriptLoaderPlugin, ScriptRunner, KSLScript,       │
│  Migrations, DynamicCommand, KSLCommandExecutor           │
├─────────────────────────────────────────────────────────┤
│  Слой 2: DSL для скриптов                                 │
│  BukkitScriptContext (implicit receiver каждого .kts)      │
│  + KSLGuiInstance/Holder/Manager, KSLPersistStore          │
├─────────────────────────────────────────────────────────┤
│  Слой 3: Интеграции и Addon API                            │
│  KSL*Hook (PAPI/LuckPerms/Essentials/Vault/SkinsRestorer/  │
│  Discord), KSL / KSLAPI / KSLAddon / KSLContextExtension   │
└─────────────────────────────────────────────────────────┘
```

### Карта файлов

| Файл | Роль |
|---|---|
| `KotlinScriptLoaderPlugin.kt` | Главный класс (`JavaPlugin`). Жизненный цикл, БД, интеграции, баннер, трекинг ресурсов скриптов, очистка `commandMap`. |
| `ScriptRunner.kt` | Компилирует и выполняет один `.kts`-файл через `BasicJvmScriptingHost`. Sandbox-проверка, форматирование ошибок. |
| `KSLScript.kt` | Конфигурация компиляции скриптов: `defaultImports`, JVM target, classloader. Аннотация `@KotlinScript`. |
| `BukkitScriptContext.kt` | **Implicit receiver** каждого скрипта — весь DSL, который видит `.kts`-файл (события, команды, БД, MiniMessage, GUI, persist, интеграции). |
| `DynamicCommand.kt` | `org.bukkit.command.Command`, создаётся под каждый `registerCommand(...)` в скрипте. |
| `KSLCommandExecutor.kt` | Обработчик `/ksl` — `reload`, `addons`, `services`, `discord`, `sandbox`. |
| `Migrations.kt` | Версионированные миграции SQLite-схемы через `PRAGMA user_version`. |
| `KSL.kt` | Статический `object` — точка входа для сторонних плагинов-аддонов (`KSL.api`, `KSL.isAvailable`). |
| `KSLAPI.kt` | Интерфейс, который аддоны используют для регистрации сервисов/импортов/расширений. |
| `KSLAddon.kt` | Интерфейс, который реализует плагин-аддон. |
| `KSLAddonManager.kt` | Реализация `KSLAPI`. Хранит зарегистрированные аддоны, сервисы, импорты, расширения контекста. |
| `KSLContextExtension.kt` | Интерфейс хука на создание/уничтожение контекста каждого скрипта. |
| `KSLGuiHolder.kt` / `KSLGuiInstance.kt` / `KSLGuiManager.kt` | GUI DSL: холдер инвентаря, сам инстанс меню (слоты, пагинация), единственный на весь плагин `Listener`. |
| `KSLPersistStore.kt` | Хранилище для `persist()` — in-memory (реживает reload) + опционально SQLite (реживает рестарт). |
| `KSL*Hook.kt` (Vault/LuckPerms/Essentials/SkinsRestorer) | Тонкие обёртки над API сторонних плагинов. Создаются только если плагин реально установлен. |
| `KSLDiscordHook.kt` | Отправка сообщений/embed'ов через Discord-вебхуки, `java.net.http.HttpClient`. |
| `KSLPlaceholderExpansion.kt` | `PlaceholderExpansion` для PlaceholderAPI — резолвит `%ksl_<key>%` через плейсхолдеры, зарегистрированные скриптами. |

---

## 3. Установка и сборка

### Требования

- Java 21 (Temurin/Adoptium рекомендуется).
- Paper 1.21.1 (или совместимый форк — Purpur и т.п.).
- Gradle с поддержкой Kotlin DSL (`build.gradle.kts`).

### Сборка

```bash
JAVA_HOME=/путь/к/jdk-21 gradle shadowJar
```

Результат — `build/libs/KotlinScriptLoader-1.0.0.jar`. Копируется в `plugins/`.

### Зависимости сборки (`build.gradle.kts`)

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "9.4.2"
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("net.essentialsx:EssentialsX:2.21.2")
    compileOnly("net.skinsrestorer:skinsrestorer-api:15.5.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.0.21")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.0.21")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
}
```

Важные нюансы, если трогаешь эту часть:

- **`VaultAPI:1.7` тянет транзитивно древний `org.bukkit:bukkit:1.13.1`**, который конфликтует с Paper API по одной "capability". Исключение `exclude(group = "org.bukkit", module = "bukkit")` обязательно, иначе `Could not resolve all files for configuration ':compileClasspath'`.
- **`shadowJar` релоцирует только `com.zaxxer.hikari`.** Пакеты `kotlin.*`/`org.jetbrains.kotlin.*` релоцировать нельзя — динамически скомпилированный скрипт линкуется против stdlib по оригинальным именам классов, relocate сломает компиляцию `.kts` в рантайме. `org.sqlite` тоже не релоцирован — избегаем риска перезаписи строкового литерала `driverClassName = "org.sqlite.JDBC"`.
- **`com.gradleup.shadow:9.4.2`** требует Gradle 9.1+ именно в `gradle-wrapper.properties`, не только в объявлении плагина.

---

## 4. Жизненный цикл плагина

### `onEnable()`

Порядок вызовов важен, вот он:

1. `saveDefaultConfig()` — создаёт `config.yml`, если его нет.
2. Создаётся `KSLAddonManager`, читается `sandbox` из конфига, создаётся `ScriptRunner`.
3. `KSL.init(addonManager)` — с этого момента `KSL.isAvailable == true`, сторонние аддоны могут регистрироваться.
4. Регистрируется исполнитель команды `/ksl`.
5. `initDatabase()` — поднимает HikariCP + SQLite, прогоняет `Migrations.run(...)`.
6. `persistStore = KSLPersistStore(this)`.
7. `guiManager = KSLGuiManager(this)`, `guiManager.register()` — **единственная** регистрация GUI-listener'а за весь жизненный цикл плагина.
8. `setupIntegrations()` — проверяет наличие PlaceholderAPI/LuckPerms/Essentials/Vault/SkinsRestorer, создаёт соответствующие хуки.
9. `discord = KSLDiscordHook(this).takeIf { it.channels().isNotEmpty() }`.
10. `generateAutocompleteStub()` — пишет `scripts/.autocomplete.kts`.
11. `loadAllScripts()` — компилирует и выполняет всё из `scripts/*.kts`.
12. `printStartupSummary(...)` — цветной баннер в консоль.

### `onDisable()`

```
unloadAllScripts()      (обёрнуто в runCatching)
addonManager.shutdown() (обёрнуто в runCatching)
database.close()        (обёрнуто в runCatching)
KSL.shutdown()
```

Каждый шаг независим — падение одного не мешает остальным отработать. Это принципиально: раньше падение на одном шаге останавливало весь `onDisable`, что могло мешать штатной остановке сервера.

### `/ksl reload`

```
reloadConfig()
sandboxEnabled = обновляется из config.yml
discord = пересоздаётся (конфиг мог поменяться)
unloadAllScripts()
generateAutocompleteStub()
loadAllScripts()
```

`unloadAllScripts()` — самая важная функция для понимания hot-reload:

1. Собирает **имена всех загруженных скриптов** (`tasksByScript.keys + listenersByScript.keys + commandsByScript.keys + scriptsWithGuis`) — это делается **до** любых `.clear()`, иначе часть данных потеряется до того, как её использовали.
2. Отменяет все таски (`Bukkit.getScheduler().cancelTask(id)`).
3. Снимает все listener'ы (`HandlerList.unregisterAll(listener)`).
4. Удаляет команды скриптов из `commandMap` (см. раздел 14 — там нетривиально).
5. Удаляет плейсхолдеры.
6. Уведомляет аддоны через `KSLContextExtension.onContextDestroyed(scriptName)`.
7. Закрывает у всех онлайн-игроков открытые GUI, принадлежащие выгружаемым скриптам.

Каждый из этих шагов обёрнут в `runCatching` **по отдельности** — ошибка на любом из них не мешает выполнить остальные.

---
