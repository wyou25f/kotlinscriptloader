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
23. Declarative automation
24. Typed contracts and asynchronous reload outcomes

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

Ресурсы закрываются в обратном порядке регистрации. close() идемпотентен. Если один ресурс завершился ошибкой, scope всё равно пытается закрыть остальные, затем сообщает общую ошибку. Если несколько потоков одновременно вызывают close(), последующие вызовы ожидают завершения первой очистки. Повторный вызов close() непосредственно из cleanup callback безопасен и не блокирует поток очистки.

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


## Глава 23. Declarative automation

KSL addons can schedule repeatable operational work from YAML or TOML without embedding executable code in configuration. Create an action registry, register trusted Kotlin handlers, then bind an engine to the addon scope:

```kotlin
val actions = KSLAutomationActionRegistry()
scope.own(actions)

actions.register("cache.refresh") { context ->
    val source = context.arguments["source"] as? String ?: "all"
    cache.refresh(source)
}

val automation = scope.automation(actions)
automation.reload(
    File(dataFolder, "automation.yml").readText(),
    KSLAutomationFormat.YAML
)
```

Example YAML:

```yaml
version: 1
automations:
  - id: cache.refresh
    enabled: true
    schedule:
      type: every
      interval-ms: 60000
      initial-delay-ms: 5000
      mode: fixed-delay
    action:
      handler: cache.refresh
      args:
        source: players
```

The TOML format uses the same model with `[[automations]]`, `[automations.schedule]` and `[automations.action]` tables. Schedule types are `once` (with `delay-ms`) and `every` (with `interval-ms`, optional `initial-delay-ms`, and `fixed-delay` or `fixed-rate` mode).

Reload parses and validates the complete document and resolves every enabled handler before replacing the active task set. Candidate callbacks remain gated until the new set is committed, so they cannot run during a partial replacement. A parse, validation or unknown-handler error leaves the previous set running. Task scheduling failures cancel the new candidates. One-shot jobs leave the active index when complete; addon-scope close cancels remaining jobs.

**Security and threads:** configuration can only select a registered handler ID. It cannot execute Kotlin, Java, reflection or shell commands. Handlers run on KSL's worker thread; switch explicitly to the Bukkit main thread before touching worlds, players, entities or inventories. The parser caps input at 1 Mi-character, entries at 256, nested argument depth at 8, and scheduling delays at 30 days. This first version supports delayed and periodic triggers; event-driven triggers are intentionally not inferred from arbitrary config text.


## Навигация по книге

Эта книга объединяет разработку Kotlin/Java-аддонов, Public API, сервисы, hooks, context extensions, KSLAddonScope, Java interop, IDE-инструменты, безопасный lifecycle и совместимость. Скриптовый runtime описан в [Книге I](01-scripts.md), хранилища и транзакции — в [Книге III](03-data-and-databases.md).


---

## Дополнительная глава: Public API Reference и контракты KSL

Справочник публичных точек входа KSL для авторов Kotlin/Java-аддонов и .kts-скриптов. Он дополняет KDoc в исходниках и подробные книги, а не заменяет их. Проверяй API установленной версии JAR: внутренние классы могут меняться быстрее публичных контрактов.

## Быстрая навигация

| Нужно сделать | Основная точка входа |
|---|---|
| Получить API из аддона | **KSL.api**, **KSLApiProvider** |
| Зарегистрировать аддон | **KSLAddon**, **KSLAPI.registerAddon** |
| Обмениваться сервисами | **registerService**, **service<T>()**, **requireService<T>()** |
| Расширить контекст .kts | **KSLContextExtension**, **registerContextExtension** |
| Добавить импорты | **addDefaultImports**, **addImports** |
| Связать аддоны событиями | **subscribe**, **publish**, **KSLTopics** |
| Интегрироваться с другим плагином | **KSLHook**, **hook<T>()**, **requireHook<T>()** |
| Читать состояние скриптов | **scripts**, **reloadScript**, **reloadScriptAsync**, **reloadScriptsAsync** |
| Управлять ресурсами аддона | **KSLAddonScope** |
| Реализовать собственный scheduler | **KSLTaskScheduler**, **KSLTaskSchedulerFactory**, **KSLTaskSpec** |

## Публичный Kotlin API

Для aliases и extension-функций подключай:

~~~kotlin
import ru.privateserver.ksl.api.*
~~~

Основные контракты объявлены в ru.privateserver.ksl.KSLAPI и ru.privateserver.ksl.KSLAddon. Пакет ru.privateserver.ksl.api предоставляет Kotlin-friendly aliases, provider и дополнительные extension-функции.

### Получение API

~~~kotlin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.api.KSLApiProvider

val maybeApi = KSL.apiOrNull
if (KSLApiProvider.isAvailable) {
    val api = KSLApiProvider.requireApi()
    println("KSL API version: " + api.apiVersion)
}
~~~

- **KSL.isAvailable** и **KSLApiProvider.isAvailable** — доступность API.
- **KSL.apiOrNull** и **kslOrNull** — безопасный nullable-доступ.
- **KSL.api** и **ksl** — обязательный доступ; до инициализации бросает ошибку.
- **KSL_API_VERSION** — API version константа, **api.apiVersion** — версия установленного экземпляра.

### Сервисы и расширение скриптов

~~~kotlin
api.registerService("economy", economyService)
val economy = api.service<EconomyService>("economy")
val required = api.requireService<EconomyService>("economy")

api.registerContextExtension(EconomyContextExtension(economyService))
api.addDefaultImports("example.economy.dsl.*")
~~~

Сервисы связывают аддоны без жёсткой зависимости на реализацию. Используй стабильные ключи и nullable lookup, если сервис может отсутствовать. Context extensions добавляют возможности в receiver скрипта; default imports сокращают количество повторяющихся import. Import сам по себе не загружает JAR и не добавляет классы в classloader.

### События KSL

~~~kotlin
val subscription = api.subscribe(KSLTopics.SCRIPT_LOADED) { payload ->
    val event = payload as? KSLScriptEvent ?: return@subscribe
    // Обработать событие загрузки
}
api.publish("example.cache.refresh", "manual")
~~~

Встроенные темы: ksl.script.loaded, ksl.script.failed, ksl.script.unloaded. Подписчики вызываются синхронно на потоке publisher-а. Если callback меняет состояние Bukkit, переключайся на главный поток. Освобождай внешние подписки при выгрузке.

### Hooks и необязательные интеграции

Основные операции: **registerHook**, **unregisterHook**, **getHook**, **hooks**, **isPluginEnabled**. Kotlin extensions: **hook<T>()**, **requireHook<T>()**, **hasHook()**. Hooks позволяют проверять интеграцию без жёсткой compile-time зависимости от необязательного плагина.

### Информация о скриптах и reload

~~~kotlin
val loadedScripts = api.scripts().filter { it.loaded }
val success = api.reloadScript("events/join")
api.reloadScriptAsync("events/join") { result ->
    // true: загружен; false: ошибка; null: другой reload уже выполняется
}
api.reloadScriptsAsync { result ->
    // null: запрос не принят, потому что reload уже выполняется
}
~~~

**reloadScript** и **reloadScripts** выполняются с главного потока сервера и могут задержать тик при больших скриптах. Для addon callbacks предпочитай **reloadScriptAsync** и **reloadScriptsAsync**: компиляция выполняется в фоне, а применение результата — на главном потоке. Для одиночной перезагрузки callback получает true/false, а null означает, что запрос не принят.

## Жизненный цикл KSLAddon

Аддон реализует **KSLAddon**: свойства addonId, addonVersion, addonDescription, dependsOn, minApiVersion, а также методы onLoad(api) и onUnload().

~~~kotlin
class ExampleAddon : JavaPlugin(), KSLAddon {
    override val addonId = "example-addon"
    override val addonVersion = "1.0.0"
    override val minApiVersion = 1

    override fun onEnable() {
        if (!KSL.isAvailable) {
            server.pluginManager.disablePlugin(this)
            return
        }
        KSL.api.registerAddon(this)
    }

    override fun onLoad(api: KSLAPI) {
        api.registerService("example.greeting", GreetingService())
    }

    override fun onUnload() {
        // Закрыть собственные ресурсы.
    }

    override fun onDisable() {
        if (KSL.isAvailable) KSL.api.unregisterAddon(addonId)
    }
}
~~~

Полный готовый Gradle-проект находится в [Addon Starter Kit](../ide/templates/addon-starter/). Он включает plugin.yml, реализацию аддона, пример DSL extension, .kts-скрипт и адаптер scheduler.

## KSLAddonScope

Создавай scope в onLoad, закрывай в onUnload. Он владеет зарегистрированными ресурсами и освобождает их в обратном порядке:

~~~kotlin
private var scope: KSLAddonScope? = null

override fun onLoad(api: KSLAPI) {
    scope = KSLAddonScope.create(api)
    scope?.listen<PlayerJoinEvent> { event ->
        event.player.sendMessage("Welcome")
    }
    scope?.repeat(Duration.ofSeconds(30)) { task ->
        // Callback работает в worker thread.
        scope?.runSync {
            Bukkit.broadcastMessage("Heartbeat #" + task.execution)
        }
    }
}

override fun onUnload() {
    scope?.close()
    scope = null
}
~~~

Scope также предоставляет own, release, ownTask, subscribe, registerEvents, listen, runSync, runAsync, отложенные и повторяющиеся Bukkit tasks. Scope не является JVM sandbox и не делает Bukkit API потокобезопасным.

## kslshu: Scheduler SPI

Публичный пакет экспортирует следующие типы:

- **KSLTaskScheduler** — контракт движка: schedule, cancel, cancelOwner, snapshot, close.
- **KSLTaskSchedulerFactory** и **KSLTaskSchedulerEnvironment** — фабрика и среда создания движка.
- **KSLTaskSpec** — owner, delay, interval, repeat mode, priority и action.
- **KSLTaskContext** — owner, номер выполнения, время отправки.
- **KSLTaskHandle** и **KSLTaskResult** — отмена и результат.
- **KSLTaskSchedulerRegistry** — регистрация и создание scheduler-ов.
- **KSLTaskScope** — фасад, принудительно назначающий owner текущего scope.

~~~kotlin
val registration = api.registerTaskSchedulerFactory("example-addon.engine") { environment ->
    ExampleScheduler(environment)
}
val scheduler = api.createTaskScheduler("example-addon.engine")
try {
    val handle = scheduler.schedule(
        KSLTaskSpec(owner = "example-addon", delay = Duration.ofSeconds(2)) {
            println("Task executed")
        }
    )
} finally {
    scheduler.close()
    registration.close()
}
~~~

Реализация должна ограничивать очередь и конкуренцию, корректно обрабатывать отмену и закрывать только ресурсы, которыми действительно владеет. Не обращайся к Bukkit world/entity/inventory из worker threads. Официальный scheduler ksl использует общий пул KSL; его lifecycle принадлежит плагину.

## Диагностика

- **/ksl doctor** — проверка окружения и конфигурации.
- **/ksl errors** — последние ошибки скриптов.
- **/ksl runtime** — состояние runtime и worker pool.
- **/ksl validate [script-id]** — проверка скриптов без выполнения кода; полный отчёт сохраняется в reports/.
- **/ksl reload <script-id>** — точечная перезагрузка.

Метрики KSL относятся к операциям и callbacks, которые KSL действительно оборачивает. Это не универсальный profiler произвольных Kotlin-выражений, Bukkit tasks и сторонних executors.

## IntelliJ IDEA workflow

1. Открой папку ide/ как Gradle project.
2. Используй JDK 21 и JAR той же версии, что установлена на сервере.
3. Передай -PkslJar=/path/to/KotlinScriptLoader-1.2.2.jar.
4. Передай -PkslScriptsDir=/path/to/plugins/KotlinScriptLoader/scripts, чтобы IDE индексировала живые скрипты.
5. Выполни verifyKslIdeSetup. Эта задача проверяет классы шаблона/API и компилирует Addon Starter Kit.
6. Открой ide/samples/ide-completion-smoke-test.kts и проверь completion, KDoc и navigation. Ошибочный sample предназначен только для проверки диагностики.

Проверка Gradle не может автоматически подтвердить работу интерфейса конкретной версии IntelliJ/Kotlin plugin. Она подтверждает classpath, наличие классов и компиляцию стартового аддона. Для IDE UI нужен ручной smoke test.

## Совместимость

- Сверяй minApiVersion аддона с api.apiVersion.
- Компилируй против JAR той ветки KSL, которую поддерживаешь.
- Предпочитай публичные aliases из ru.privateserver.ksl.api; внутренние пакеты не являются стабильным контрактом.
- Успешная сборка не заменяет тестирование на реальном Paper-сервере.


## Runtime telemetry

KSL exposes read-only JVM telemetry to addon authors. These helpers report the
resources visible to the running Java process; they do not reserve CPU cores,
resize the heap, or allocate memory on behalf of the JVM.

```kotlin
val stats = api.runtimeStats() // alias: api.runtime()
logger.info("Heap: ${stats.heapUsedBytes} / ${stats.heapMaxBytes} bytes")
logger.info("Visible processors: ${api.availableProcessors()}")
logger.info("Current heap usage: ${api.heapUsedBytes()} bytes")
```

The heap limit is configured with Java launch options (for example `-Xms`
and `-Xmx`) and may also be constrained by the container or hosting panel.
KSL's worker scheduler is bounded and configured by the server runtime; addon
code should use its task scopes rather than create unbounded thread pools.


## Structured logging API (v12)

API v12 originally introduced the admin-aware logger for scripts and addons. KSL scripts use `scriptLogger(name) { ... }`; addons use `api.logger(ownerId, name) { ... }` or `api.createLogger(...)`. The logger provides TRACE, DEBUG, INFO, WARN, ERROR, OFF, child categories and structured key/value fields.

The server administrator controls `logging.default-level`, optional `logging.owner-levels` entries and `logging.max-message-length` in config.yml. The effective threshold is the stricter of the logger requested level and the administrator minimum. A logger cannot lower the server minimum.

See [Structured logging](01-scripts.md) for complete examples, config and operational guidance.


## Declarative automation

Addon authors can register a finite set of trusted handlers and select them from YAML/TOML:

```kotlin
val actions = KSLAutomationActionRegistry()
scope.own(actions)
actions.register("stats.flush") { context ->
    stats.flush(context.arguments)
}
val engine = scope.automation(actions)
engine.reload(configText, KSLAutomationFormat.YAML)
```

The engine supports one-shot and repeating schedules, validates the full configuration before replacement, and is owned by `KSLAddonScope`. Callbacks execute on KSL worker threads, not Bukkit's main thread. Configuration cannot contain executable source; it only references registered handler IDs. This automation integration was introduced with API v16; the current development API is v19.


---

## Дополнительная глава: Надёжная разработка скриптов и аддонов

This guide describes the safe development loop for large KSL scripts and Paper addons. The checks are intentionally conservative: a warning points to a risky pattern, but the absence of warnings is not a proof that arbitrary Kotlin code cannot stall the server.

## Recommended script workflow

1. Keep each independent feature in its own `.kts` file instead of building one giant script.
2. Use KSL-owned events, commands, tasks, worker APIs and lifecycle resources.
3. Run `/ksl validate` before loading a whole project, or `/ksl validate events/join` for one file.
4. Review compiler errors and authoring warnings. Validation compiles the source but does not evaluate its top-level code.
5. Reload only after validation is clean. If compilation fails, KSL keeps the previous working version where possible.

## Large diagnostic sets

`/ksl validate` keeps chat output short: it shows a summary and a small sample of failing files. The complete report is written to:

```text
plugins/KotlinScriptLoader/reports/validation-*.txt
```

Reports include compiler errors, compiler warnings, authoring warnings, script IDs, failure codes and compile durations. Reports are capped at 2 MB and the newest ten are retained. This keeps a script with 50 or more diagnostics from flooding the server console while preserving a readable report.

## Watchdog-aware authoring

Compilation is the expensive part of loading and should happen on KSL's compile workers. Do not use `Thread.sleep`, `runBlocking`, unconditional loops, or manual thread pools in script top-level code or synchronous event callbacks.

Prefer KSL-owned scheduling and worker APIs:

```kotlin
after(20L) {
    // Runs as a scheduled KSL callback.
}

repeat(ticks = 20L) {
    // Repeating work is owned by this script and cleaned up on unload.
}

async {
    // Worker thread: do not access Bukkit world/player/inventory state here.
    val result = calculateOrReadRemoteData()
    sync {
        // Apply Bukkit state on the server thread.
    }
}
```

Direct Bukkit scheduler registrations and raw threads may outlive a script reload unless explicitly cancelled. Prefer KSL APIs or register an explicit cleanup action.

The Paper watchdog must remain enabled. KSL diagnostics can identify a long-running operation and capture thread snapshots, but KSL cannot safely terminate arbitrary Kotlin code already executing inside the JVM.

## Addon reload APIs

Addon code should use asynchronous reload APIs from lifecycle callbacks, command handlers or other server-thread code:

```kotlin
api.reloadScriptAsync("events/join") { result ->
    when (result) {
        true -> logger.info("Script reloaded")
        false -> logger.warning("Script failed to compile or load")
        null -> logger.info("Another reload is already running or KSL is shutting down")
    }
}
```

`reloadScript` is synchronous and blocks the caller until compilation and swap finish. Keep it for controlled tooling where blocking is intentional; prefer `reloadScriptAsync` for normal addon integrations.

Structured logging was introduced in API v12; the current development API is v19. Addons should compare minApiVersion with the installed api.apiVersion rather than hard-code a value. See [Structured logging](01-scripts.md). Addons should compare `minApiVersion` with the installed `api.apiVersion` rather than hard-code a value.

## IntelliJ preflight

The IntelliJ companion provides local heuristic checks for:
- blocking sleeps and `runBlocking`;
- unconditional `while (true)` loops;
- manually created threads/executors;
- synchronous addon reload calls;
- missing Paper/PlaceholderAPI/LuckPerms dependencies and common API typos.

It analyzes `.kts` scripts and Kotlin `.kt` sources in KSL addon projects. It does not execute code, download dependencies, or replace Gradle compilation and tests.

## Reliability checklist

- Use `val` by default and split large files by responsibility.
- Give every task, listener, subscription and external client a clear owner.
- Do not access Bukkit world/entity/inventory APIs from worker threads.
- Do not block the main thread waiting for futures or network I/O.
- Keep the Paper watchdog enabled.
- Run validation, addon tests and a real server smoke test before deployment.
- Back up scripts and plugin data before replacing production JARs.


---

## Дополнительная глава: Рабочий процесс, starter kit и IntelliJ IDEA

Это руководство описывает рабочий процесс проекта KSL 1.2.2. Оно не меняет номер версии и не объявляет новый релиз.

## 1. Начать с готового аддона

Скопируй ide/templates/addon-starter в отдельный Gradle-проект. Открой его в IntelliJ IDEA с JDK 21 и укажи JAR именно той сборки KSL, которая установлена на тестовом сервере:

    ./gradlew -PkslJar=/absolute/path/to/KotlinScriptLoader-1.2.2.jar compileKotlin

Не используй JAR другой версии: он может содержать другой публичный API.

## 2. Публичный API

Используй публичный пакет ru.privateserver.ksl.api.* и типизированные сервисы вместо поиска классов через reflection:

    import ru.privateserver.ksl.KSL
    import ru.privateserver.ksl.KSLAPI
    import ru.privateserver.ksl.KSLAddon
    import ru.privateserver.ksl.api.*

    class ExampleAddon : JavaPlugin(), KSLAddon {
        override val addonId = "example-addon"
        override val addonVersion = "1.0.0"

        override fun onEnable() {
            if (!KSL.isAvailable) {
                server.pluginManager.disablePlugin(this)
                return
            }
            KSL.api.registerAddon(this)
        }

        override fun onLoad(api: KSLAPI) {
            api.registerService("example.greetings", GreetingService())
        }

        override fun onUnload() {
            // Close addon-owned resources.
        }

        override fun onDisable() {
            if (KSL.isAvailable) KSL.api.unregisterAddon(addonId)
        }
    }

Для optional-интеграций используй service<T>() и обрабатывай null. Для обязательной зависимости — requireService<T>(), чтобы получить понятную ошибку при отсутствии сервиса.

## 3. Один scope на жизненный цикл

KSLAddonScope владеет listeners, KSL subscriptions и задачами. Закрывай его в onUnload:

    private var scope: KSLAddonScope? = null

    override fun onLoad(api: KSLAPI) {
        val owned = KSLAddonScope.create(api, schedulerId = "example-addon")
        scope = owned

        owned.listen<PlayerJoinEvent> { event ->
            event.player.sendMessage("Welcome!")
        }

        owned.repeat(Duration.ofSeconds(30)) {
            // Worker thread: do not access Bukkit world/player/inventory here.
            val count = api.onlinePlayerCount()
            owned.runSync {
                logger.fine("Online snapshot: $count")
            }
        }
    }

    override fun onUnload() {
        scope?.close()
        scope = null
    }

Не передавай Bukkit Player/World/Inventory между потоками как будто они thread-safe. Получай необходимые immutable данные на server thread, выполняй вычисления в фоне, затем возвращайся на server thread для Bukkit-изменений.

## 4. Расширять KTS без копирования кода

Если несколько скриптов повторяют одинаковые функции, вынеси их в Kotlin-аддон как extension/context API и зарегистрируй пакет:

    // addon module
    fun Player.sendWelcomeCard() {
        sendMessage(Component.text("Welcome!"))
    }

    // addon onLoad
    api.addDefaultImports("example.addon.dsl.*")

После регистрации импорт автоматически доступен KSL script compiler. Он не добавляет произвольные внешние JAR в classloader: зависимости должны быть доступны самому KSL/серверу.

## 5. Безопасная перезагрузка

Не вызывай синхронный reload из event callback, если компиляция может быть долгой:

    api.reloadScriptAsync("events/join") { result ->
        when (result) {
            true -> logger.info("Script reloaded")
            false -> logger.warning("Script reload failed")
            null -> logger.fine("Another reload is already running")
        }
    }

reloadScript остаётся синхронным для совместимости. Предпочитай async API в обычной addon-интеграции.

## 6. IDE и подсказки

В IntelliJ IDEA:

1. Открой ide/ как Gradle-проект.
2. Используй JDK 21.
3. Передай -PkslJar=/path/to/KotlinScriptLoader-1.2.2.jar.
4. Для live scripts добавь -PkslScriptsDir=/path/to/plugins/KotlinScriptLoader/scripts.
5. Запусти verifyKslIdeSetup.
6. Установи собранный KSL IntelliJ plugin и открой ide/samples/ide-completion-smoke-test.kts.

KSL completion contributor подсказывает публичные точки входа, scope/lifecycle, сервисы, typed script events, async reload, scheduler SPI и testing DSL. Для .kt подсказки включаются в файлах, где уже виден KSL API/import или контракт KSLAddon; посторонние Kotlin-проекты не засоряются.

Семантические типы, переход к определению и стандартное Kotlin completion обеспечивает Kotlin plugin IntelliJ. KSL contributor добавляет доменные подсказки, а не заменяет Kotlin compiler.

## 7. Проверка до запуска

- /ksl validate — компиляционная проверка всех скриптов без выполнения top-level кода.
- /ksl validate <script-id> — проверка одного скрипта.
- /ksl doctor — диагностика окружения.
- ./gradlew test — тесты runtime/API.
- ./gradlew verifyKslIdeSetup — classpath и starter kit.
- ./gradlew test в ide/intellij-plugin — тесты IDE diagnostics/completion.

IDE warnings и preflight — эвристики. Они помогают находить частые проблемы, но не доказывают корректность произвольного Kotlin-кода. Перед production тестируй аддон и скрипты на staging Paper-сервере.

## 8. Типичные проблемы

| Симптом | Что проверить |
|---|---|
| Unresolved reference: KSLAPI | KSL JAR подключён как compileOnly(files(...)), путь верный |
| Нет completion для KSL в .kt | В файле есть import ru.privateserver.ksl... или ссылка на KSLAddon |
| Не виден addon DSL в скрипте | Пакет зарегистрирован через addDefaultImports, JAR аддона доступен серверному classloader |
| Reload задерживает тик | Используй reloadScriptAsync / reloadScriptsAsync |
| Bukkit API вызывается из worker | Перенеси Bukkit-операции в scope.runSync { ... } |
| Listener остался после unload | Регистрируй его через KSLAddonScope и закрывай scope |

## Статус

- Runtime build version: 1.2.2 (development, not published).
- Последний опубликованный стабильный релиз: 1.2.1.
- Этот документ не публикует 1.2.2 и не создаёт release tag.
- Compatibility branches не изменяются этим руководством.


## 9. Реальные Paper-интеграционные тесты NBT

Обычные тесты продолжают выполняться на JUnit + MockBukkit. Для бинарного .nbt I/O в CI дополнительно запускается настоящий Paper 1.21.1 в Docker: интеграционный тестовый плагин зависит от KotlinScriptLoader, вызывает его публичный NBT API и записывает результат проверки в result.txt.

Собрать тестовый плагин отдельно:

    ./gradlew --no-daemon paperIntegrationPluginJar

Артефакт появится в build/paper-integration/artifacts/KSLNBTIntegration.jar. Для ручной проверки помести его и собранный KotlinScriptLoader-1.2.2.jar в каталог plugins/ тестового Paper 1.21.1. После старта тестовый плагин выполнит проверки и штатно остановит сервер. MockBukkit при этом не удаляется и не заменяется.


---

## Дополнительная глава: Java/Kotlin interop и strict sandbox

Это руководство относится к development-линии KSL 1.2.2 и не объявляет выпуск новой версии.

## Главное

KSL выполняет настоящий Kotlin/JVM Script. Поэтому в скриптах можно вызывать обычные публичные методы Java и Kotlin-классов, которые присутствуют в classpath скрипта. Не нужно создавать отдельный KSL-wrapper для каждого метода.

Работает привычный Kotlin-синтаксис:

- статические методы Java вызываются через имя класса;
- методы объектов и библиотек вызываются обычным способом;
- Java getters/setters отображаются как Kotlin properties, где это поддерживает Kotlin interop;
- Java SAM-интерфейсы принимают Kotlin lambdas;
- generic-типы Java доступны с обычной проверкой компилятора;
- Kotlin collections, sequences, text, math и extension-функции остаются доступны.

Это не означает, что абсолютно любой класс из всей JVM всегда должен быть доступен. Компилятор видит зависимости текущего KSL runtime и подключённые библиотеки, а strict sandbox специально отсекает опасные классы.

## Больше JDK API без ручных imports

В KSL добавлены стандартные imports для часто используемых Java API:

| Пакет | Для чего |
|---|---|
| `java.math.*` | BigDecimal, BigInteger и точная арифметика |
| `java.text.*` | Форматирование чисел и текста |
| `java.util.*` | Коллекции, UUID, Locale, Optional и утилиты |
| `java.util.regex.*` | Pattern и Matcher |
| `java.time.*` | Instant, Duration, LocalDate и другие типы времени |
| `java.time.format.*` | DateTimeFormatter |
| `java.time.temporal.*` | ChronoUnit и temporal fields |
| `java.util.function.*` | Function, Predicate, Consumer и Java SAM API |
| `kotlin.math.*` | sqrt, abs, roundToInt и математические функции |

Основные Kotlin-пакеты уже входят в стандартные импорты самого Kotlin scripting host. Для сторонних пакетов по-прежнему можно писать обычный `import`.

## Пример: стандартные Java и Kotlin методы

```kotlin
val price = BigDecimal("12.50")
    .add(BigDecimal("2.00"))
    .multiply(BigDecimal("1.20"))

val formatted = DecimalFormat(
    "0.00",
    DecimalFormatSymbols(Locale.US)
).format(price)

val pattern = Pattern.compile("[A-Z]+")
val isKsl = pattern.matcher("KSL").matches()

val started = Instant.parse("2026-11-16T12:00:00Z")
val finished = started.plusSeconds(30)
val elapsedSeconds = ChronoUnit.SECONDS.between(started, finished)

val byName = HashMap<String, Int>().apply {
    put("answer", 42)
}

val distance = sqrt(3.0 * 3.0 + 4.0 * 4.0)
val rounded = distance.roundToInt()
```

Это обычные вызовы JDK/Kotlin API. Они не требуют отдельной регистрации в KSL.

## Подключённые библиотеки и API других плагинов

Доступность метода определяется не только языком, но и тем, находится ли нужный класс в classpath.

Для Kotlin/Java addon подключай API используемого плагина как Gradle dependency (обычно `compileOnly`), а на сервере проверяй наличие соответствующего плагина. Не упаковывай внутрь аддона копию Bukkit/Paper API или чужой plugin API.

Для KTS-скрипта сторонний API должен быть реально доступен через classloader сервера или загруженный KSL addon. Если аддон публикует удобный DSL, добавь его пакет через `api.addDefaultImports("example.addon.dsl.*")`. Import сам по себе не добавляет JAR в classpath и не включает отсутствующий плагин.

Предпочитай публичные API интеграций и KSL hooks, а не reflection и поиск внутренних классов по имени.

## Включение strict sandbox

По умолчанию верхнеуровневый параметр `sandbox` в `plugins/KotlinScriptLoader/config.yml` выключен. Поэтому source preflight и classloader deny-list не применяются к обычным скриптам, пока sandbox явно не включён.

Для включения строгого режима в конфигурации KSL:

```yaml
sandbox: true

security:
  sandbox-level: strict
  sandbox-trusted: []
```

Это включает sandbox для всех скриптов, кроме ID, явно внесённых в `security.sandbox-trusted`. Перед включением проверь существующие скрипты: обращения к файлам, сети, reflection и raw threads могут перестать работать. Режим `security.sandbox-level: text` оставляет только source-фильтр и не включает classloader deny-list; для строгих ограничений оставляй `strict`.

После изменения конфигурации проверь сервер через `/ksl doctor`. Не добавляй опасные классы в `security.class-deny-allowed` для обхода отказов.

## Что ограничивает strict sandbox

Strict sandbox оставляет нормальную работу с безопасными значениями и стандартными библиотеками, но блокирует опасные возможности, в том числе:

- запуск процессов и команд ОС через ProcessBuilder/Runtime;
- прямой доступ к системным свойствам и environment через System;
- создание raw Thread/Timer и собственных Executors;
- reflection, MethodHandles и ClassLoader manipulation;
- прямой файловый доступ через java.io / java.nio.file;
- низкоуровневые сетевые классы java.net;
- динамическую компиляцию, загрузку bytecode и внутренние API JDK.

Теперь очевидные обращения к этим API отклоняются уже на source preflight до запуска, а strict classloader остаётся дополнительной проверкой во время загрузки классов. Некоторые классы нужны самим JVM и внутренним API KSL; их наличие внутри процесса не означает, что sandboxed script получает к ним прямой доступ.

Если нужна работа с файлами, SQL или сетью, используй специально предоставленные KSL API и проверяй права/пути/таймауты. Не снимай запреты на целые пакеты ради одного метода.

## Важная граница безопасности

KSL и Paper работают в одной JVM. Sandbox — это дополнительная защита от ошибочного или ограниченного скрипта, а не полноценная изоляция недоверенного кода от сервера. Доступный Bukkit API, сторонние плагины, уже выданные объекты и тяжёлые вычисления всё ещё могут иметь побочные эффекты.

Поэтому:

1. Не запускай скрипты от неизвестных авторов на production-сервере.
2. Не считай отсутствие предупреждений доказательством безопасности.
3. Не добавляй опасные классы в `security.class-deny-allowed` и не отключай strict sandbox, если не доверяешь каждому скрипту.
4. Длительные вычисления переноси в KSL worker API, Bukkit-операции возвращай на главный поток.
5. Тестируй обновления на отдельном Paper staging-сервере и держи Paper watchdog включённым.

## Проверка

- `/ksl validate` компилирует скрипты и выполняет source preflight, но не исполняет top-level код.
- `/ksl doctor` проверяет конфигурацию и доступность sandbox.
- Runtime classloader проверяет запрещённые классы при загрузке.
- Автоматические тесты компиляции проверяют обычные Java/Kotlin API и ранний отказ для ограниченных пакетов.

Эти проверки повышают предсказуемость, но не превращают in-process Kotlin в изолированную среду выполнения.

## Статус версии

- Development build version: 1.2.2.
- Последний публичный стабильный релиз: 1.2.1.
- Это руководство не создаёт новый релиз или release tag; последний публичный стабильный релиз остаётся 1.2.1.


---

## Глава 24. Типизированные контракты и асинхронные результаты

Старые методы KSLAPI остаются доступными: строковые ключи сервисов, event bus с Any? и callback-перезагрузка. Для нового кода доступны типизированные обёртки. Они используют тот же runtime и не создают вторую шину событий или отдельный реестр сервисов.

### 24.1 Типизированные темы событий

~~~kotlin
import ru.privateserver.ksl.api.*

data class BalanceChanged(val playerId: java.util.UUID, val balance: Long)

val balanceChanged = kslTopic<BalanceChanged>("example.economy.balance-changed")

val subscription = api.subscribe(balanceChanged) { event ->
    logger.info("Player ${event.playerId}: ${event.balance}")
}

api.publish(balanceChanged, BalanceChanged(player.uniqueId, 250L))

// При выгрузке аддона:
subscription.close()
~~~

Тема хранит имя и runtime-класс payload. Если старый код публикует в ту же строковую тему объект другого типа, typed-подписчик отклонит его через IllegalArgumentException; event bus запишет ошибку обработчика и продолжит доставку другим подписчикам. Имена тем ограничены 128 символами и набором букв, цифр, точки, подчёркивания, двоеточия и дефиса.

### 24.2 Типизированные сервисные ключи

~~~kotlin
interface EconomyService {
    fun balance(playerId: java.util.UUID): Long
}

val economyKey = kslServiceKey<EconomyService>("example.economy")

// Аддон-поставщик:
api.registerService(economyKey, economyService)

// Аддон-потребитель:
val economy: EconomyService = api.requireService(economyKey)
val optional: EconomyService? = api.service(economyKey)

// При выключении поставщика:
api.unregisterService(economyKey)
~~~

Тип проверяется через Class из ключа до возврата сервиса. Для взаимодействия между разными плагинами всё равно используйте один общий API-класс из classloader сервера и compileOnly-зависимость на KSL. Не shade-те копии контрактов в addon JAR.

### 24.3 Иерархические resource scopes

~~~kotlin
val root = api.addonScope()
val databaseScope = root.childScope()

databaseScope.subscribe(balanceChanged) { event ->
    // Обработчик принадлежит только databaseScope.
}

databaseScope.own(databaseClient)
root.close() // закрывает дочернюю область и все её ресурсы
~~~

Дочерняя область разделяет scheduler и event bus владельца. Ресурсы освобождаются в обратном порядке регистрации. Закрытие дочерней области вручную безопасно; повторное закрытие не выполняет освобождение повторно. Сама область не является JVM sandbox и не меняет правила потокобезопасности Bukkit.

### 24.4 Futures для reload

Старые callback-методы сохраняются. Новые функции возвращают CompletableFuture и заменяют nullable-сигнал занятости явным результатом:

~~~kotlin
api.reloadScriptFuture("events/join").whenComplete { outcome, failure ->
    if (failure != null) {
        api.kslPlugin.logger.warning("Reload request failed: ${failure.message}")
    } else when (outcome) {
        is KSLReloadOutcome.Script ->
            if (outcome.reloaded) logger.info("Reloaded") else logger.warning("Reload failed")
        KSLReloadOutcome.Busy ->
            logger.info("Another reload is already running")
        is KSLReloadOutcome.Batch -> Unit
        null -> Unit
    }
}
~~~

Для всех скриптов используйте reloadScriptsFuture(). Результат Batch содержит количество успешно перезагруженных и неуспешных скриптов. Ошибки синхронного запуска завершают future exceptionally; ошибка компиляции представляется Script(reloaded = false). Не вызывайте get()/join() на главном потоке Bukkit: дождитесь future на worker thread, а игровые изменения перенесите в sync callback.

### 24.5 Совместимость

- API compatibility level текущей разработки: v19.
- Старые строковые методы subscribe/publish и getService/registerService/unregisterService не удалены.
- Новые типизированные API — дополнительные контракты; старые бинарные интеграции не требуют переписывания.
- Владелец сервиса отвечает за unregisterService при выгрузке, если сервис не зарегистрирован в собственной управляемой области.
- Для addon указывайте minApiVersion, если ему действительно необходим новый контракт.
