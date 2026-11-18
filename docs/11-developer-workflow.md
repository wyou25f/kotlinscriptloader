# KSL 1.2.2 — удобная разработка скриптов и аддонов

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
