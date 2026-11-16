# Книга II. Плагины, аддоны и библиотеки для KSL

**Руководство разработчика по расширению KotlinScriptLoader на Kotlin и Java**

Эта книга предназначена для разработчиков Paper-плагинов, которые хотят подключаться к KSL, публиковать сервисы, создавать библиотеки, добавлять собственный DSL, интеграции и versioned Standard Library modules.

Главная идея: не нужно добавлять каждую новую возможность в ядро KSL. Большинство расширений лучше реализовать отдельным addon-плагином с узким, документированным API.

## Содержание

1. Архитектура расширений
2. Выбор механизма расширения
3. Создание Gradle-проекта
4. plugin.yml и регистрация addon
5. Жизненный цикл
6. Public API: доступ и совместимость
7. KSLAPI: карта возможностей
8. Сервисы
9. Импорты и classloader
10. Собственные библиотеки и extension-функции
11. Расширение script DSL
12. Context extensions
13. Hooks для других плагинов
14. Event bus
15. Standard Library modules
16. KSLAddonScope и управление ресурсами
17. Конфигурация аддона
18. Java-интеграция
19. Потоки, безопасность и classloader
20. Тестирование и публикация
21. Частые ошибки
22. Release checklist

---

## Глава 1. Архитектура расширений

У KSL несколько API-слоёв. Не смешивайте их.

| Слой | Назначение | Кто использует |
|---|---|---|
| Script DSL | Функции и receiver, доступные в .kts | Авторы скриптов |
| Public API | Контракты для Kotlin/Java plugins | Разработчики аддонов |
| Internal core | Реализация загрузчика и runtime | Контрибьюторы KSL |

Функция, доступная из .kts, не обязательно является методом KSLAPI. Public API аддона не всегда автоматически появляется в script context. Kotlin typealias не создаёт Java-класс.

### 1.1 Что такое addon

Аддон — обычный Paper-плагин, который реализует KSLAddon и регистрируется в KSL. Он может:

- предоставлять сервисы скриптам и другим аддонам;
- добавлять default imports;
- расширять script context;
- регистрировать hooks для сторонних плагинов;
- поставлять versioned Standard Library modules;
- подписываться на KSL event bus;
- владеть собственными задачами, listeners и внешними клиентами.

### 1.2 Почему отдельный JAR

Отдельный JAR удобен, когда функция должна быть повторно используемой, иметь собственные зависимости и конфигурацию, поставляться на несколько серверов или обслуживаться отдельно от скриптов.

---

## Глава 2. Выбор механизма расширения

| Задача | Лучший механизм |
|---|---|
| Одна небольшая игровая механика на сервере | .kts |
| Общая функция между скриптами одного сервера | MesLib2 |
| Компилируемые extension-функции для скриптов | Addon + default imports |
| Общий объект с состоянием или бизнес-логикой | Addon service |
| Необязательная интеграция с другим плагином | Hook |
| Персональное состояние каждого .kts | KSLContextExtension |
| Библиотека с версией и зависимостями | Standard Library module |
| Новый механизм загрузчика | Изменение KSL core |

Не создавайте addon ради одной строки, если функцию можно оставить в скрипте. Но и не раздувайте один .kts до библиотечной платформы.

---

## Глава 3. Создание Gradle-проекта

### 3.1 Минимальный build.gradle.kts

~~~kotlin
plugins {
    kotlin("jvm") version "2.0.21"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly(files("libs/KotlinScriptLoader.jar"))
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
~~~

Пример ориентирован на целевое окружение, описанное в README KSL. Для реального сервера выровняйте Kotlin plugin, Paper API, JDK и KSL JAR по совместимым версиям.

### 3.2 Почему compileOnly

Paper и KSL предоставляет сервер. Аддон не должен класть в свой JAR вторую копию KSL API или Paper API.

Если два classloader загрузят одинаково названный интерфейс как два разных класса, JVM сочтёт их разными типами. Сервис может быть найден по ключу, но не пройти проверку типа.

Используйте compileOnly. Не делайте shade KSL API внутрь addon JAR.

### 3.3 Структура проекта

~~~text
ExampleAddon/
├── build.gradle.kts
├── settings.gradle.kts
└── src/
    └── main/
        ├── kotlin/
        │   └── com/example/addon/
        │       ├── ExampleAddon.kt
        │       ├── service/EconomyService.kt
        │       └── dsl/PlayerExtensions.kt
        └── resources/
            ├── plugin.yml
            └── config.yml
~~~

Пакеты в Kotlin-файлах — обычные Kotlin packages. Путь к файлу обычно повторяет пакет, но именно package declaration задаёт полное имя класса.

---

## Глава 4. plugin.yml и регистрация addon

### 4.1 plugin.yml

~~~yaml
name: ExampleAddon
version: 1.0.0
main: com.example.addon.ExampleAddon
api-version: '1.21'
depend:
  - KotlinScriptLoader
~~~

Используйте depend, если аддон не может работать без KSL. Если KSL необязателен, используйте подходящую optional dependency модель и всегда проверяйте доступность.

### 4.2 Минимальная реализация

~~~kotlin
package com.example.addon

import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon

class ExampleAddon : JavaPlugin(), KSLAddon {
    override val addonId = "example-addon"
    override val addonVersion = "1.0.0"
    override val addonDescription = "Пример KSL addon"

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader недоступен")
            server.pluginManager.disablePlugin(this)
            return
        }

        KSL.api.registerAddon(this)
    }

    override fun onLoad(api: KSLAPI) {
        // Регистрация возможностей для KSL.
    }

    override fun onUnload() {
        // Очистка собственных ресурсов.
    }

    override fun onDisable() {
        if (KSL.isAvailable) {
            KSL.api.unregisterAddon(addonId)
        }
    }
}
~~~

Не обращайтесь к KSL.api до проверки KSL.isAvailable. KSL может ещё не завершить инициализацию или уже переходить к остановке.

---

## Глава 5. Жизненный цикл

Основные свойства и callbacks:

| Член | Назначение |
|---|---|
| addonId | Уникальный ID аддона |
| addonVersion | Версия аддона |
| addonDescription | Описание |
| dependsOn | Метаданные о зависимостях |
| minApiVersion | Минимальная версия KSL API |
| onLoad(api) | Регистрация возможностей |
| onUnload() | Освобождение ресурсов |

### 5.1 Рекомендуемый порядок

1. Bukkit загружает плагин.
2. onEnable проверяет KSL.
3. Аддон вызывает registerAddon(this).
4. KSL вызывает onLoad(api).
5. Аддон публикует services, imports, hooks и другие возможности.
6. При остановке аддон вызывает unregisterAddon(addonId).
7. KSL вызывает onUnload.
8. Аддон закрывает собственные внешние ресурсы.

### 5.2 Зависимости других аддонов

dependsOn полезен как metadata, но не заменяет механизм ожидания готовности другого KSL addon. Если нужен чужой сервис, используйте onAddonReady(id) и получайте сервис внутри callback.

Не предполагайте, что Bukkit plugin load order автоматически гарантирует порядок регистрации KSL addons.

---

## Глава 6. Public API: доступ и совместимость

### 6.1 Kotlin imports и точки доступа

Канонический Kotlin namespace:

~~~kotlin
import ru.privateserver.ksl.api.*
~~~

| Имя | Поведение |
|---|---|
| ksl | Non-null API; ошибка, если KSL недоступен |
| kslapi | Алиас |
| kslApi | Алиас |
| api | Алиас |
| kslInstance | Алиас |
| kslOrNull | Nullable API |
| isKslAvailable | Флаг доступности |
| KSL_API_VERSION | Уровень совместимости |

### 6.2 Type aliases

Публичный Kotlin package экспортирует aliases для KSLAPI, KSLAddon, KSLHook, KSLContextExtension, KSLScriptInfo, KSLScriptEvent, KSLAddonConfig, BukkitScriptContext, KSLShapeFactory, модулей Standard Library, MesLib2 и выбранных Runtime API types.

Kotlin alias упрощает imports, но не создаёт отдельный JVM class. Java-разработчику нужно использовать реальные типы из ru.privateserver.ksl.

### 6.3 Additive API

Многие удобные короткие операции реализованы Kotlin extension functions, а не новыми abstract methods интерфейса KSLAPI. Это помогает добавлять возможности без изменения обязательных методов для существующих реализаций.

Новое abstract member — изменение внешнего контракта. Перед его добавлением нужно оценить API version, совместимость и migration notes.

### 6.4 Версия API

Читайте фактический KSL_API_VERSION или api.apiVersion. Не копируйте число из старого примера. minApiVersion в addon сообщает, какой уровень API ему необходим.

---

## Глава 7. KSLAPI: карта возможностей

KSLAPI — центральный контракт для интеграций аддонов.

| Группа | Основные методы |
|---|---|
| Addons | registerAddon, unregisterAddon, registeredAddons, isAddonLoaded, onAddonReady |
| Services | registerService, unregisterService, getService |
| Imports | addDefaultImports |
| Standard modules | registerStandardLibraryModule, unregisterStandardLibraryModule, standardLibraryModules |
| Context extensions | registerContextExtension, unregisterContextExtension |
| Event bus | subscribe, publish |
| Scripts | scripts, reloadScript, reloadScripts, reloadScriptsAsync |
| Sandbox policy | isSandboxed, isConsoleCommandBlocked |
| Hooks | registerHook, unregisterHook, getHook, hooks, isPluginEnabled |
| Placeholders | registerPlaceholder, unregisterPlaceholder, registerTextPlaceholder, unregisterTextPlaceholder |
| Engines | registerEffectShape, registerEntityAttribute |
| Config | createConfig |

Kotlin extensions добавляют короткие формы: service<T>(), requireService<T>(), hook<T>(), requireHook<T>(), findAddon(), requireAddon(), findScript(), reloadAll(), emit(), on(), onlinePlayers(), broadcast() и другие.

### 7.1 Addon inspection

- registeredAddons() — снимок зарегистрированных аддонов;
- isAddonLoaded(id) — проверка загрузки;
- findAddon(id)/requireAddon(id) — поиск и обязательный поиск;
- onAddonReady(id) — callback готовности.

### 7.2 Script management

- scripts() — метаданные скриптов;
- reloadScript(id) — точечная блокирующая перезагрузка, main-thread only;
- reloadScripts() — полная перезагрузка, main-thread only;
- reloadScriptsAsync(callback) — фоновая компиляция с применением результата в main thread;
- isSandboxed(id) и isConsoleCommandBlocked(command) — проверки политик KSL.

Async callback может получить null, если другая перезагрузка уже идёт. Не блокируйте main thread синхронной долгой операцией.

---

## Глава 8. Сервисы

### 8.1 Что такое service

Service — объект под стабильным ключом, через который скрипты или другие addons получают общую бизнес-логику.

Примеры: EconomyService, ProfileService, PermissionService, RewardService, ServerStatsService.

### 8.2 Общий интерфейс

~~~kotlin
package com.example.economy.api

import java.util.UUID

interface EconomyService {
    fun balance(playerId: UUID): Long
    fun deposit(playerId: UUID, amount: Long): Boolean
}
~~~

Поставщик и потребитель должны использовать один и тот же интерфейс, загруженный совместимым classloader.

### 8.3 Регистрация и получение

~~~kotlin
// В provider addon
api.registerService("economy", economyService)

// В consumer addon или скрипте
val economy = api.service<EconomyService>("economy")
    ?: error("Economy service is unavailable")
~~~

Nullable service вернёт null, если сервис отсутствует или не подходит по типу. requireService выбрасывает понятную ошибку.

### 8.4 Состояние и синхронизация

Если service вызывается одновременно из нескольких скриптов или потоков, спроектируйте concurrency. ConcurrentHashMap защищает операции самой map, но не делает values внутри неё thread-safe.

### 8.5 Владение ключами

Регистрации внутри onLoad принадлежат аддону. Удаляйте собственные ключи. KSL ограничивает снятие чужих owned resources, чтобы один аддон не ломал другой.

---

## Глава 9. Импорты и classloader

### 9.1 Добавление imports

~~~kotlin
override fun onLoad(api: KSLAPI) {
    api.addDefaultImports(
        "com.example.servertoolkit.player.*",
        "com.example.servertoolkit.service.MessageService"
    )
}
~~~

Import не скачивает зависимость. Объявление должно находиться в addon JAR или доступной runtime dependency.

### 9.2 Wildcard imports

Wildcard импорт относится к конкретному package. Он не включает подпакеты автоматически.

~~~kotlin
import com.example.toolkit.*
import com.example.toolkit.player.*
import com.example.toolkit.text.*
~~~

### 9.3 Classloader

KSL делает classloader зарегистрированного аддона доступным компиляции и исполнению скриптов. При unload imports и classloader exposure удаляются для последующих компиляций.

Перед заменой addon JAR корректно выгрузите зависимые скрипты. Не оставляйте активные .kts, которые ссылаются на классы отключаемого плагина.

---

## Глава 10. Собственные библиотеки и extension-функции

### 10.1 Stateless библиотека

~~~kotlin
package com.example.servertoolkit.text

fun String.serverLabel(): String =
    "[Server] " + this
~~~

### 10.2 Bukkit extension

~~~kotlin
package com.example.servertoolkit.player

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

fun Player.sendToolkitMessage(message: String) {
    sendMessage(Component.text("[Toolkit] " + message))
}
~~~

### 10.3 Регистрация пакета

~~~kotlin
override fun onLoad(api: KSLAPI) {
    api.addDefaultImports(
        "com.example.servertoolkit.player.*",
        "com.example.servertoolkit.text.*"
    )
}
~~~

### 10.4 Использование из .kts

~~~kotlin
player.sendToolkitMessage("Добро пожаловать")
val label = "Shop".serverLabel()
~~~

### 10.5 Service или extension

Используйте extension для маленькой stateless операции. Используйте service, когда есть состояние, конфигурация, БД, кэш, интеграции или управляемый lifecycle.

Если сервис содержит общее состояние, создайте его один раз в addon и опубликуйте через KSLAPI. Не создавайте независимую копию внутри каждого скрипта.

---

## Глава 11. Расширение script DSL

Script context позволяет публиковать extension-функции на BukkitScriptContext. Они выглядят в .kts как встроенные DSL-вызовы.

~~~kotlin
package com.example.shop.dsl

import org.bukkit.entity.Player
import ru.privateserver.ksl.BukkitScriptContext

fun BukkitScriptContext.shopWelcome(player: Player) {
    player.sendMessage("Добро пожаловать в магазин!")
}
~~~

В addon:

~~~kotlin
override fun onLoad(api: KSLAPI) {
    api.addDefaultImports("com.example.shop.dsl.*")
}
~~~

В скрипте:

~~~kotlin
shopWelcome(player)
~~~

Держите DSL-функции небольшими и однозначными. Сложную логику делегируйте сервису.

---

## Глава 12. Context extensions и per-script state

KSLContextExtension нужен, когда аддону требуется отдельное состояние для каждого загруженного скрипта.

Интерфейс предоставляет callbacks:

- onContextCreated(context);
- onContextDestroyed(scriptName).

~~~kotlin
class ProfileContextExtension : KSLContextExtension {
    override val extensionId = "profile-context"

    private val profiles = mutableMapOf<String, MutableMap<String, Any>>()

    override fun onContextCreated(context: BukkitScriptContext) {
        profiles[context.scriptName] = mutableMapOf()
    }

    override fun onContextDestroyed(scriptName: String) {
        profiles.remove(scriptName)
    }
}
~~~

Перед реализацией сверяйте точные свойства интерфейса с target KSL JAR. Не храните ссылки на уничтоженные context. Очищайте per-script maps, иначе reload будет накапливать старое состояние.

---

## Глава 13. Hooks для других плагинов

Hook — слой интеграции с внешним плагином. Он предоставляет KSL или скриптам узкий контракт вместо распространения деталей внешнего API по всему проекту.

### Optional dependency

~~~kotlin
override fun onLoad(api: KSLAPI) {
    if (api.isPluginEnabled("SomePlugin")) {
        api.registerHook(MySomePluginHook())
    }
}
~~~

### Принципы хорошего hook

- У hook стабильный уникальный ID.
- Контракт содержит только нужные операции.
- Ошибки внешнего API обрабатываются.
- Hook снимается при выгрузке аддона.
- Потребитель проверяет nullable result.

### Использование

~~~kotlin
val integration = hook<MyIntegrationHook>("my-integration")

if (integration != null) {
    // Вызов методов конкретного hook.
}
~~~

Не придумывайте имена методов — сверяйте реализацию hook и документацию.

---

## Глава 14. Event bus

Event bus снижает связанность между аддонами и скриптами. Отправитель публикует topic и payload, не зная о подписчиках.

~~~kotlin
// Подписчик
val subscription = api.subscribe("shop.purchase") { payload ->
    val purchase = payload as? PurchaseInfo ?: return@subscribe
    api.log("Покупка: " + purchase.playerId)
}

// Отправитель
api.publish("shop.purchase", purchaseInfo)
~~~

Особенности:

- обработчики запускаются синхронно на потоке publisher;
- ошибка одного handler не должна останавливать остальные;
- publish возвращает число обработчиков, завершившихся успешно;
- подписки из onLoad принадлежат addon и снимаются при unload;
- ручную подписку вне lifecycle callback нужно закрыть через AutoCloseable.

Не выполняйте длительную работу внутри event bus callback.

---

## Глава 15. Standard Library modules

KSL поддерживает декларативные versioned modules.

Модуль описывает:

- уникальный ID;
- версию;
- минимальный API уровень;
- dependencies;
- default imports;
- install/uninstall lifecycle.

KSLModulePlanner.resolve проверяет дубли ID, отсутствующие зависимости, циклы и совместимость API, после чего возвращает детерминированный dependency-first порядок.

Планировщик только проверяет и упорядочивает. Он не устанавливает произвольные JAR из сети.

Используйте module, если расширение должно быть библиотечным компонентом с явно объявленной версией и зависимостями. Для одного сервиса достаточно service. Для маленькой функции — default import.

Install/uninstall должны быть симметричными. Ресурсы модуля должны закрываться владельцем.

---

## Глава 16. KSLAddonScope и управление ресурсами

KSLAddonScope — явный scope для задач, listeners, подписок и внешних AutoCloseable ресурсов аддона.

### Создание

Kotlin:

~~~kotlin
val scope = api.addonScope()
~~~

Java:

~~~java
KSLAddonScope scope = KSLAddonScope.create(api);
~~~

KSL не закрывает вручную созданный scope автоматически только потому, что addon unregister вызывается. Храните ссылку и вызывайте close() в onUnload.

### Публичные свойства и методы

| API | Назначение |
|---|---|
| isClosed | Показывает, закрыт ли scope |
| resourceCount | Число ресурсов |
| own(resource) | Передаёт AutoCloseable под владение scope |
| release(resource) | Удаляет и закрывает ресурс |
| subscribe(topic, handler) | Подписка на event bus |
| registerEvents(listener) | Регистрирует Bukkit listener |
| listen<T>(...) | Типизированный event handler |
| runSync { } | Задача на серверном потоке |
| runAsync { } | Фоновая задача |
| runSyncLater(ticks) { } | Отложенная sync задача |
| runAsyncLater(ticks) { } | Отложенная async задача |
| runSyncRepeating(...) { } | Повтор на главном потоке |
| runAsyncRepeating(...) { } | Повтор в фоне |
| close() | Закрывает все принадлежащие ресурсы |

### Полный пример

~~~kotlin
class MetricsAddon : KSLAddon {
    override val addonId = "metrics-addon"
    override val addonVersion = "1.0.0"

    private var resources: KSLAddonScope? = null

    override fun onLoad(api: KSLAPI) {
        val scope = api.addonScope()
        resources = scope

        scope.listen<PlayerJoinEvent> { event ->
            recordJoin(event.player.uniqueId)
        }

        scope.subscribe("backup.completed") {
            recordBackup(it)
        }

        scope.runSyncRepeating(periodTicks = 20L) {
            publishMetrics()
        }

        scope.own(createMetricsClient())
    }

    override fun onUnload() {
        resources?.close()
        resources = null
    }
}
~~~

### Порядок и ошибки очистки

Ресурсы закрываются в обратном порядке регистрации. close() идемпотентен. Если один ресурс завершился ошибкой, scope всё равно пытается закрыть остальные, затем сообщает общую ошибку.

Если own вызывается после закрытия scope, ресурс немедленно закрывается и выбрасывается IllegalStateException.

Регистрация Bukkit listeners требует серверного потока. Async task не должна менять мир, игроков, сущности или инвентари. KSLAddonScope управляет жизненным циклом, но не делает Bukkit API thread-safe.

---

## Глава 17. Конфигурация аддона

KSLAPI.createConfig создаёт YAML конфигурацию в data folder владельца. Defaults можно положить в JAR как resource.

~~~kotlin
class ExampleAddonPlugin : JavaPlugin(), KSLAddon {
    private lateinit var settings: KSLAddonConfig

    override fun onEnable() {
        if (!KSL.isAvailable) {
            server.pluginManager.disablePlugin(this)
            return
        }

        settings = KSL.api.createConfig(this)
        KSL.api.registerAddon(this)
    }

    override fun onLoad(api: KSLAPI) {
        val enabled = settings.getBoolean("settings.enabled", true)
        if (enabled) logger.info("Модуль включён")
    }

    override fun onDisable() {
        if (KSL.isAvailable) KSL.api.unregisterAddon(addonId)
    }

    override fun onUnload() = Unit
}
~~~

Пример config.yml внутри JAR:

~~~yaml
settings:
  enabled: true
  prefix: "<gold>[Addon]</gold>"
~~~

Доступны операции чтения строк, чисел, boolean, string lists, contains, getKeys, getSection, set, save и reload. Для расширенных сценариев доступно underlying Bukkit FileConfiguration.

Пути должны оставаться внутри data folder аддона. Не принимайте абсолютные пути и ../ от пользователя.

---

## Глава 18. Java-интеграция

Java не видит Kotlin typealias как отдельные классы. Используйте реальные core interfaces:

~~~java
import ru.privateserver.ksl.KSLAPI;
import ru.privateserver.ksl.api.KSLApiProvider;

KSLAPI api = KSLApiProvider.getApi();

if (api != null) {
    api.scripts().forEach(script ->
        getLogger().info(script.getId())
    );
}
~~~

Для Java не используйте импорты Kotlin alias вместо реальных интерфейсов. Для Kotlin-only helper могут потребоваться вызовы сгенерированных static facade methods. Проверьте байткод и IDE.

Для KSLAddonScope доступна Java factory KSLAddonScope.create(api).

---

## Глава 19. Потоки, безопасность и classloader

Addon выполняется как полноценный Paper plugin. Sandbox скриптов не изолирует сторонний JAR addon.

- Bukkit world/entity/player/inventory обычно требует главный поток.
- SQL и network выполняйте в фоне.
- Не блокируйте main thread через join/get.
- Используйте immutable DTO для передачи данных.
- При завершении фоновой задачи проверяйте, что addon ещё активен.
- Не shade KSL API.
- Не публикуйте одинаковые service keys с несовместимыми типами.
- Shared interfaces должны быть доступны обоим участникам через совместимый classloader.

HTTP-клиент, file watcher, executor и third-party subscription должны иметь явную процедуру закрытия. Не рассчитывайте на сборщик мусора как на lifecycle mechanism.

---

## Глава 20. Тестирование и публикация

### Что тестировать

- service регистрируется и читается;
- отсутствующий service корректно обрабатывается;
- addon unload снимает owned registrations;
- listener перестаёт работать после scope.close;
- repeating task отменяется;
- module dependencies валидируются;
- classloader зависимостей совместим;
- callback не меняет Bukkit state из async;
- обновление JAR не оставляет старые script references.

### Проверка вручную

1. Установите KSL и addon JAR на тестовый Paper.
2. Проверьте успешную регистрацию.
3. Создайте тестовый .kts, импортирующий addon extension.
4. Вызовите service.
5. Перезагрузите скрипт несколько раз и убедитесь, что listener не дублируется.
6. Остановите addon и убедитесь, что ресурсы сняты.
7. Просмотрите логи и /ksl doctor.

CI не заменяет реальный Paper сервер с нужной комбинацией плагинов.

---

## Глава 21. Частые ошибки

**NoClassDefFoundError.** Проверьте зависимости, plugin.yml и classloader. Не shade KSL API внутрь addon JAR.

**Service найден, но тип не совпадает.** Вероятно, provider и consumer используют разные копии интерфейса. Поместите shared contract в compileOnly dependency.

**Скрипт не видит extension function.** Проверьте package declaration, default import, загрузку аддона, classloader и компиляцию скрипта после регистрации импорта.

**Другой addon ещё не готов.** Используйте onAddonReady, а не рассчитывайте на dependsOn как на готовый service resolver.

**Задача работает после unload.** Сохраните BukkitTask и отмените её или используйте KSLAddonScope. Внешние клиенты и subscriptions закрывайте явно.

**Java не видит KSLPublicAPI.** Это Kotlin alias. Используйте ru.privateserver.ksl.KSLAPI и KSLApiProvider.

---

## Глава 22. Release checklist

- [ ] KSL JAR используется как compileOnly.
- [ ] Paper и Java/Kotlin target совпадают с сервером.
- [ ] plugin.yml содержит правильную зависимость.
- [ ] KSL availability проверяется до обращения к API.
- [ ] addonId уникальный и стабильный.
- [ ] minApiVersion соответствует используемым методам.
- [ ] imports ведут к скомпилированным классам.
- [ ] services используют shared API types.
- [ ] listeners/tasks/clients закрываются.
- [ ] onDisable вызывает unregisterAddon.
- [ ] документация содержит пример .kts потребителя.
- [ ] Поведение проверено на target Paper/KSL версии.

**Конец книги II.** Далее — persistent state, SQL, SQLite, MySQL, MariaDB, phpMyAdmin, YAML, TOML и JSON.


---

## KSL Scheduler Hub (kslshu)

KSL exposes an owner-scoped scheduler to every script through `scheduler`:

```kotlin
val delayed = scheduler.schedule(delay = 2.seconds) { task ->
    logger.info("Execution #${task.execution} for ${task.owner}")
}

val repeating = scheduler.repeat(
    interval = 30.seconds,
    initialDelay = 5.seconds,
    mode = KSLTaskRepeatMode.FIXED_DELAY
) { task ->
    // This callback runs on a KSL worker thread.
    // Use runSync { ... } before changing Bukkit world/player state.
}

delayed.cancel()
repeating.cancel()
```

The official implementation delegates to KSL's bounded priority worker engine. Script
ownership is automatic: tasks created through the context are cancelled when that script
is unloaded or reloaded.

### Implementing a custom scheduler

Addon authors can implement `KSLTaskScheduler` and register a factory with the plugin's
scheduler registry. The SPI deliberately separates task description from execution policy:
a custom engine can choose a dedicated executor, a virtual-thread strategy, a tick-based
queue, or an external scheduler, while preserving task handles, owner cancellation and
diagnostics.

```kotlin
// The overload registers the factory and owns its registration + engine lifecycle.
val scope = KSLAddonScope.create(api, schedulerId = "example-addon.tick") { environment ->
    MyAddonScheduler(environment.officialWorkers)
}
scope.schedule { task -> println("custom scheduler task #${task.execution}") }
scope.schedule(KSLTaskSpec(owner = "ignored-by-scope", delay = Duration.ofSeconds(2)) {
    println("The scope replaces the supplied owner with its own addon identity")
})
scope.repeat(Duration.ofSeconds(5)) { task -> println("tick #${task.execution}") }

// In onUnload:
scope.close()
```

A custom implementation must:
- enforce owner-scoped cancellation and release task resources on completion;
- return a handle whose completion future resolves on success/cancellation and completes
  exceptionally on failure;
- bound queues and concurrency rather than allowing unbounded task growth;
- document which thread executes callbacks;
- implement `close()` and close only resources it owns.

A `KSLTaskScope` can also accept a full `KSLTaskSpec` through `schedule(spec)`.
The scope always overwrites the spec's owner with its own owner ID, preventing an
addon or script from accidentally scheduling work under another owner's quota or
cancellation namespace.

The factory registry does not silently replace the official scheduler used by scripts.
This keeps existing scripts stable while addons can opt into a custom scheduler explicitly.
Use `api.registerTaskSchedulerFactory(id, factory)` for a manually managed
registration; it returns an `AutoCloseable` that must be closed on addon unload.
Use `api.availableTaskSchedulers()` to inspect registered engine IDs and
`api.createTaskScheduler(id)` when you need manual lifecycle control. Prefer
`KSLAddonScope.create(api, schedulerId = "...")` for addons: it owns the created
scheduler, cancels its tasks first, and closes the engine last. The default
`schedulerId = "ksl"` selects the official KSL engine.
