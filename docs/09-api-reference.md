# KSL API Reference и Developer Guide

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
3. Передай -PkslJar=/path/to/KotlinScriptLoader-1.2.1.jar.
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

Public API version 12 adds an admin-aware logger for scripts and addons. KSL scripts use `scriptLogger(name) { ... }`; addons use `api.logger(ownerId, name) { ... }` or `api.createLogger(...)`. The logger provides TRACE, DEBUG, INFO, WARN, ERROR, OFF, child categories and structured key/value fields.

The server administrator controls `logging.default-level`, optional `logging.owner-levels` entries and `logging.max-message-length` in config.yml. The effective threshold is the stricter of the logger requested level and the administrator minimum. A logger cannot lower the server minimum.

See [Structured logging](13-structured-logging.md) for complete examples, config and operational guidance.
