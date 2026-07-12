# KotlinScriptLoader (KSL)

Приватный hot-reload загрузчик `.kts` скриптов для Paper 1.21.1 / Java 21.

## Сборка

```
./gradlew shadowJar
```

Готовый jar — `build/libs/KotlinScriptLoader-1.0.0.jar`. Кладёшь в `plugins/`.

## Структура после первого запуска

```
plugins/KotlinScriptLoader/
  ksl.db              SQLite база (HikariCP)
  scripts/
    test.kts
    test.yml
```

Скрипты из `examples/` — это пример, их нужно скопировать в `plugins/KotlinScriptLoader/scripts/` вручную.

## DSL внутри .kts

`BukkitScriptContext` — implicit receiver скрипта (через `kotlin-scripting-jvm-host`,
не через JSR223 bindings), поэтому всё ниже доступно прямо по имени, без префиксов и импортов:

- `plugin` — экземпляр `JavaPlugin`
- `config` / `saveConfig()` — `имя_скрипта.yml`, лениво
- `database` — общий `HikariDataSource`
- `onEvent<T> { }` — регистрация ивента
- `registerCommand(name, aliases) { player, args -> }` — динамическая команда
- `registerPlaceholder(key) { player -> "..." }` — плейсхолдер `%ksl_key%` для PlaceholderAPI
- `luckPermsGroup/Prefix/Suffix(player)`, `hasGroup(player, name)` — LuckPerms (мягкая зависимость)
- `balance(player)`, `setBalance(player, amount)`, `isAfk(player)` — EssentialsX (мягкая зависимость)
- `runAsync { }` / `runSync { }` — таскшедулер

Плюс `defaultImports` покрывает практически весь нужный Bukkit API: `entity.*`,
весь `event.*` с под-пакетами (player/block/entity/inventory/world/server/weather/
vehicle/hanging), `inventory.*`, `block.*`, `boss.*`, `attribute.*`, `enchantments.*`,
`metadata.*`, `persistence.*`, `permissions.*`, `advancement.*`, `scheduler.*`,
`potion.*`, `util.*`, `configuration.file.*`, плюс `java.time.*` / `java.util.concurrent.*` /
`UUID` — большинству скриптов вообще не нужны ручные `import`.

(`java.util.*` целиком намеренно НЕ добавлен — `List`/`Map`/`Set` из него конфликтуют
с `kotlin.collections.*`, который у Kotlin всегда подключён неявно, и компиляция
скрипта будет падать на "ambiguous import".)

## /ksl reload

Снимает все listener'ы (`HandlerList.unregisterAll`), плейсхолдеры и команды скриптов,
затем заново сканирует `scripts/`. Один сломанный скрипт не валит остальные —
`ScriptRunner.loadScript` ловит исключение целиком и просто считается "с ошибкой"
в итоговой статистике. `unloadAllScripts()` сам по себе никогда не кидает исключение
наружу (см. пункт 2 ниже) — это касается и `/ksl reload`, и `onDisable` при рестарте сервера.

## Старт/стоп

`onEnable` печатает баннер со статусом (БД, число загруженных/сломанных скриптов,
PlaceholderAPI/LuckPerms/EssentialsX) — зелёным и жирным, с переходом в жёлтый/красный
на конкретной строке, если там что-то не так. `onDisable` оборачивает выгрузку скриптов
и закрытие БД в `runCatching`, чтобы ошибка на одном из шагов не валила весь
disable-конвейер.

## PlaceholderAPI / LuckPerms / EssentialsX

Все три — софт-зависимости (`softdepend` в plugin.yml). Если плагин не установлен —
соответствующие DSL-методы просто пишут warning / возвращают null/false, без падений.
Классы-обёртки (`KSLPlaceholderExpansion`, `KSLLuckPermsHook`, `KSLEssentialsHook`)
ссылаются на классы сторонних плагинов, поэтому создаются только ПОСЛЕ проверки
`getPlugin(...) != null`, иначе JVM попытается загрузить несуществующий класс при старте.

`hasGroup`/`luckPermsGroup` сравнивают именно primary group, не полное дерево
наследования прав — для большинства скриптов это и есть "в какой группе игрок".

## Миграции БД

`Migrations.kt` — версии хранятся в `PRAGMA user_version` самого SQLite-файла, без
отдельной таблицы. Каждый шаг — лямбда в списке `steps`; новые добавляются строго
в конец, старые никогда не трогаются (иначе версия "проскочит" уже применённый шаг
на действующих базах).

## Важные нюансы

1. **Главный баг, из-за которого скрипты не видели импорты вообще ничего**:
   `dependenciesFromCurrentContext()` берёт classpath из `Thread.currentThread().contextClassLoader`.
   В Bukkit/Paper на потоке, которым дёрнули `/ksl reload` (консоль, команда), это
   почти никогда не classloader плагина — поэтому компилятор скрипта не видел даже
   собственные классы плагина, не то что Bukkit API. Исправлено: и компиляция
   (`dependenciesFromClassloader(classLoader = ..., wholeClasspath = true)`), и evaluation
   (`jvm { baseClassLoader(pluginClassLoader) }`) теперь явно привязаны к classloader'у
   плагина, а не к состоянию текущего потока.

2. **UnsupportedOperationException: remove** — на твоей сборке Purpur даже публичный
   `Bukkit.getCommandMap().getKnownCommands()` отдаёт что-то, на чём `iterator.remove()`
   падает. Теперь двухступенчатый фоллбэк: сначала пробуем без reflection через
   публичный геттер, если это падает именно на удалении — откатываемся на reflection
   к приватному полю `knownCommands` внутри `SimpleCommandMap` (это обходит любую
   defensive-обёртку, которую конкретный форк мог повесить на геттер). Если и это
   не сработало — не падаем, просто warning в лог: старые команды доживут до рестарта,
   но `/ksl reload` всё равно зарегистрирует актуальные версии поверх них.

3. **Конфликт команд**: `commandMap.register(prefix, command)` молча уходит в форму
   `prefix:label`, если bare-команда с таким именем уже занята другим плагином — раньше
   это выглядело как "скрипт загрузился", а команда просто не работала. Теперь после
   регистрации `trackCommand` проверяет, действительно ли `/label` указывает на нашу
   команду, и если нет — пишет explicit warning с именем конфликтующего скрипта.

4. **Shadow 9.4.2** требует Gradle 9.1+ у тебя в проекте (не только версия плагина —
   сам `gradle/wrapper/gradle-wrapper.properties` тоже должен быть на 9.1+, иначе
   плагин просто не применится). Если wrapper трогать не хочешь — рабочая альтернатива
   без смены ветки: `com.gradleup.shadow` версия `8.3.11` (последняя в 8.x, тоже
   совместима с Gradle 9, но не требует ровно 9.1+).

5. **Relocation**: `kotlin.*` / `org.jetbrains.kotlin.*` не релоцированы — динамически
   скомпилированный скрипт линкуется против stdlib по оригинальным именам классов,
   relocate их сломает компиляцию `.kts` в рантайме. SQLite-драйвер тоже не релоцирован
   (меньше риска от relocate строковых литералов вроде `driverClassName = "org.sqlite.JDBC"`).
   Hikari релоцировать безопасно — там нет built-in строковых ссылок на свой пакет.

6. Внутренние коллекции (`listenersByScript`, `commandsByScript`, `placeholdersByScript`,
   `placeholders`) теперь на `ConcurrentHashMap`, а не на обычных `HashMap` — PAPI
   может дёргать `placeholders` с произвольного потока (скорборды/таблисты часто
   обновляются асинхронно), и читать его одновременно с `/ksl reload`, пишущим в ту же
   карту с основного потока, без этого было небезопасно.

7. Зависимость `kotlin-scripting-jsr223` в графе зависимостей не используется
   (пайплайн целиком на `BasicJvmScriptingHost`), но не мешает — оставлена для
   полноты набора scripting-модулей.

8. Версии PlaceholderAPI (`2.11.6`), LuckPerms (`5.5`) и EssentialsX (`2.21.2`),
   а также их сигнатуры (`onRequest(OfflinePlayer, String)`, `User.getPrimaryGroup()`,
   `User.getMoney()/setMoney()`, `User.isAfk()`) не прогнаны через реальный компилятор
   в этой песочнице — нет доступа к `repo.extendedclip.com` / `repo.essentialsx.net`.
   Если у тебя другие мажорные версии — сверь сигнатуры с офиц. вики этих плагинов.

Собрать и реально прогнать через `gradle build` здесь не вышло — нет доступа к
внешним maven-репозиториям из контейнера. Синтаксис не прогнан через настоящий
компилятор, проверь сборку локально перед деплоем.

## 1.1.0 — межскриптовые библиотеки

`export(key, value)` / `library<T>(key)` / `requireLibrary<T>(key)` — один `.kts`
может явно отдавать данные/функции, другой их забирать, без прямой зависимости
через аддон-плагин.

```kotlin
// kd.kts
fun damageFor(weapon: Material): Double = ...
export("kd", mapOf("damageFor" to ::damageFor))
```

```kotlin
// pvp.kts
// @requires: kd
val kdLib = library<Map<String, Any?>>("kd") ?: error("kd.kts не загружен")
val damageFor = kdLib["damageFor"] as (Material) -> Double
```

**Важное ограничение, о котором стоит знать сразу**: каждый `.kts` компилируется
полностью независимо от остальных (отдельный вызов `host.eval` со своей
`ScriptCompilationConfiguration`). Это значит, что кастомный класс, объявленный
прямо внутри одного скрипта (`class KdApi { ... }`), физически не существует как
именуемый тип для другого скрипта — Kotlin-компилятор второго скрипта просто не
может написать `library<KdApi>(...)`, потому что такого типа в его classpath нет
и появиться не может (скрипты компилируются по одному, не как единый проект).

Поэтому `export`/`library` рассчитаны на типы, которые **уже видны на classpath
независимо от скриптов** — то есть:
- обычные функции через ссылку (`::damageFor`), тип которых — стандартный
  `Function1<Material, Double>` из Kotlin stdlib;
- `Map<String, Any?>` как контейнер для нескольких значений/функций сразу
  (сам `Map` — тоже stdlib-тип, а к содержимому обращаешься через `as`);
- любые Bukkit/Paper/Java-типы (`Player`, `ItemStack`, `Int`, `String` и т.д.);
- типы из аддон-плагинов (`service<CoinService>("coins")`) — они компилируются
  как обычный jar и видны всем скриптам одинаково, в отличие от классов внутри `.kts`.

Если нужен полноценный API с несколькими методами и состоянием — оформи его как
обычный Kotlin-класс в аддон-плагине (см. `docs/Addon-Development-Guide.md`) и
отдай через `service<T>()`, а не пытайся объявлять класс прямо в `.kts`.

**Порядок загрузки**: раньше `loadAllScripts()` просто шёл по `listFiles()` в
порядке файловой системы (не гарантирован). Теперь `KSLScriptOrder` сканирует
первые 20 строк каждого `.kts` на директиву `// @requires: name1, name2` и
топологически сортирует список перед загрузкой — так `kd.kts` гарантированно
загрузится раньше `pvp.kts`, если тот объявил `@requires: kd`. Отсутствующая
зависимость или циклическая ссылка не роняют загрузку — только warning в лог
и откат к исходному порядку для затронутых скриптов.

Все `export`-ы конкретного скрипта автоматически снимаются при его выгрузке
(`/ksl reload` или ошибка компиляции) — так же, как listener'ы, команды и GUI,
чтобы другой скрипт не держал ссылку на «протухшую» библиотеку молча.

## WorldEdit / WorldGuard

Обе — софт-зависимости (`softdepend` в plugin.yml), как и остальные интеграции.
Хуки (`KSLWorldEditHook`, `KSLWorldGuardHook`) полностью защищены через
`KSLErrors.hookSafe` — сбой в самом WorldEdit/WorldGuard (например, несовместимая
версия) даёт `null`/`false`/`emptyList()` в скрипте, а не падение.

**WorldEdit** — доступ к текущему выделению игрока (`//pos1`, `//pos2`):
```kotlin
val volume = weSelectionVolume(player)          // Long? — объём выделения в блоках
val (min, max) = weSelection(player) ?: return  // Pair<Location, Location>
val changed = weFillSelection(player, Material.STONE)  // Int? — залить выделение материалом
```

**WorldGuard** — регионы и флаги в точке:
```kotlin
val regions = wgRegionsAt(player.location)          // List<String> — id всех регионов тут
val canBuild = wgCanBuild(player, player.location)   // Boolean — стандартный флаг BUILD
val canPvp = wgCanPvp(player, player.location)       // Boolean — стандартный флаг PVP
val greeting = wgFlag(player.location, "greeting")   // String? — ЛЮБОЙ флаг по имени, включая кастомные от других плагинов
```

`wgFlag` — сознательно универсальный: ищет флаг по строковому имени через
`WorldGuard.getInstance().flagRegistry`, а не через захардкоженный список
констант. Значит работает не только со стандартными `build`/`pvp`/`greeting`,
но и с любым кастомным флагом, который зарегистрировал сторонний плагин —
для этого достаточно знать его имя, отдельный код под каждый флаг не нужен.

`wgCanBuild`/`wgCanPvp` возвращают `true`, если WorldGuard не установлен —
это соответствует поведению "нет защиты — нет ограничений", а не наоборот.

**Важная оговорка по сборке**: `worldedit-bukkit` и `worldguard-bukkit` — как и
`VaultAPI` раньше — тянут в POM транзитивную зависимость на старый
`org.bukkit:bukkit`, поэтому `exclude(group = "org.bukkit", module = "bukkit")`
уже стоит на обеих зависимостях в `build.gradle.kts` по аналогии с фиксом Vault.
Кроме того, `worldguard-bukkit` сам транзитивно тянет `worldedit-bukkit` —
если Gradle всё же пожалуется на конфликт версий между явно указанной
`worldedit-bukkit:7.2.15` и тем, что просит WorldGuard, подними/опусти версию
WorldEdit до той, что действительно требует установленный у тебя WorldGuard
(смотри `worldguard-bukkit-VERSION.pom` в `https://maven.enginehub.org/repo/`).
Версии в `build.gradle.kts` (`7.2.15` / `7.0.9`) не прогнаны через реальный
`gradle build` в этой песочнице — сверь с версией, которая реально стоит на
твоём сервере, перед деплоем.

## Реальные тесты чистой логики

Три модуля (`KSLPersistCodec`, `KSLCooldownStore`, `KSLScriptOrder`) не зависят
от Bukkit/Paper вообще — только `java.*`/`kotlin.*`. Это значит их можно
скомпилировать и прогнать без всего плагина, без сети, без Gradle:

```bash
kotlinc src/main/kotlin/ru/privateserver/ksl/KSLPersistCodec.kt \
        src/main/kotlin/ru/privateserver/ksl/KSLCooldownStore.kt \
        src/main/kotlin/ru/privateserver/ksl/KSLScriptOrder.kt \
        src/test/kotlin/ru/privateserver/ksl/PureLogicSelfTest.kt \
        -include-runtime -d pure-logic-test.jar
java -jar pure-logic-test.jar
```

Прогнано прямо при разработке (28 проверок, все зелёные): кодирование/декодирование
всех поддерживаемых типов `persist`, кулдауны, топологическая сортировка `@requires`
(простая цепочка, независимые скрипты, циклическая зависимость, отсутствующая
зависимость, порядок в списке файлов). Если после правок в одном из этих трёх
файлов тест краснеет — это баг в самой логике, а не в Bukkit-интеграции, что
сильно сужает, где искать проблему.

## /ksl doctor

Новая команда — не просто печатает статус из памяти, а реально прогоняет
тривиальный скрипт (`6 * 7`) через тот же `ScriptRunner`/`BasicJvmScriptingHost`,
которым грузятся настоящие `.kts`, и проверяет, что результат — `42`. Если
compiler/classloader/JVM target где-то разъехались — `doctor` покажет это
явно, а не тихо промолчит до первого реального скрипта. Дополнительно
проверяет: живое ли соединение с БД, доступна ли `scripts/` для записи,
зарегистрирована ли команда `/ksl` в `commandMap`, статус каждой софт-интеграции
(отдельно отличает "не установлен" от "установлен, но хук не поднялся"),
и сколько ошибок скриптов накопилось с последнего старта.

## Найденные и исправленные баги

1. **`validateAllScripts()`, `verifyTrackedCommands()`, `checkDatabaseTables()`
   были полностью мёртвым кодом** — реализованы, но нигде не вызывались, ни из
   одной команды. Подключил: первые два — в `/ksl doctor` (проверка таблиц БД
   отдельно от общей проверки соединения, и список команд, перехваченных
   другими плагинами), третий — в новую команду `/ksl validate`.

2. **`/ksl validate` — новая команда**: компилирует все `.kts` без их запуска
   (dry-run через `JvmScriptCompiler`, а не полноценный `eval`). Не трогает БД,
   не регистрирует команды/listener'ы — можно проверить, что скрипты после
   правки хотя бы компилируются, прежде чем реально делать `/ksl reload`.

3. **Список sandbox-паттернов был задублирован** между `ScriptRunner` (реальная
   загрузка) и валидатором скриптов — если бы кто-то добавил новый запрещённый
   паттерн в одном месте и забыл про другое, `/ksl validate` показывал бы
   "всё чисто" для скрипта, который реальная загрузка потом бы отклонила.
   Вынес правила в `KSLSandboxRules` — единый источник истины для обоих.

4. **`/ksl validate` мог упасть целиком из-за одного проблемного файла** —
   `ScriptValidator.validate()` не был обёрнут в try-catch; неожиданная ошибка
   чтения/компиляции одного скрипта уронила бы всю команду. Обернул — теперь
   такой файл просто помечается "не удалось проверить: ...", остальные
   проверяются как обычно.

5. **`dbExecute`/`dbQuery` не гарантировали порядок выполнения** — оба
   запускались через обычный Bukkit async scheduler (`runAsync`), а он
   исполняет асинхронные таски на пуле потоков без гарантии, что второй
   вызов начнётся строго после завершения первого. На практике это значит,
   что `dbExecute("INSERT ...")` сразу за которым идёт `dbQuery("SELECT COUNT...")`
   мог иногда увидеть базу до применения INSERT. Завёл выделенный
   однопоточный `dbExecutor` (`Executors.newSingleThreadExecutor`) —
   все вызовы одного скрипта теперь строго последовательны, как и ожидается
   от типичного кода вида "записал → сразу прочитал".

6. **`persist(..., persistent = true)` блокировал вызывающий поток на записи**
   — `player-counter = value` в обработчике команды means синхронный JDBC-запрос
   прямо на главном потоке сервера. Запись переведена на тот же `dbExecutor`:
   `memory`-кеш обновляется мгновенно и синхронно (поэтому следующее чтение
   в том же тике видит новое значение), а сохранение на диск уходит в очередь
   асинхронно. При остановке сервера `dbExecutor` ждёт до 3 секунд, чтобы
   уже поставленные в очередь записи гарантированно долетели до БД.

## Точечная перезагрузка одного скрипта

`/ksl reload <имя_без_.kts>` — перезагружает только один файл, не трогая
остальные. Раньше правка одного скрипта из двадцати означала полный
`/ksl reload`: закрывались вообще все открытые GUI, отменялись вообще все
таски, снимались вообще все listener'ы — даже у скриптов, которые никто не
редактировал. Теперь `unloadAllScripts()` и точечная перезагрузка используют
один и тот же `unloadScript(scriptName)` — раньше эта логика была размазана
батчем по всем скриптам сразу, из-за чего точечную выгрузку было бы неоткуда
взять без дублирования кода. Tab-complete у `/ksl reload` подсказывает имена
файлов из `scripts/`.

```
/ksl reload           — все скрипты, как раньше
/ksl reload kd        — только kd.kts, остальные не тронуты
```

## `registerCommand` — permission и tabComplete

Раньше у команд скрипта не было способа задать проверку прав или своё
автодополнение — Bukkit просто фоллбэчился на список игроков онлайн. Теперь:

```kotlin
registerCommand(
    "skin",
    permission = "ksl.test.skin",
    tabComplete = { player, args ->
        if (args.size == 1) listOf("Notch", "Steve", "Alex").filter { it.startsWith(args[0], true) }
        else emptyList()
    }
) { player, args -> ... }
```

`permission` проверяется и перед `execute`, и перед `tabComplete` — игрок без
прав не увидит подсказки автодополнения для команды, которую всё равно не
может выполнить. Оба новых параметра опциональны с дефолтом `null`, старые
вызовы `registerCommand("name") { ... }` продолжают работать без изменений.
Ошибка внутри `tabComplete`-лямбды ловится так же, как и в самой команде —
не может уронить попытку игрока просто нажать Tab.

## YAML — сообщения с `@placeholder@` и авто-дефолты

Раньше для сообщения из конфига с подстановками писали руками:
`config.getString("welcome")!!.replace("%player%", player.name).replace('&', '§')`
в каждом месте, где нужно сообщение. Теперь:

```kotlin
broadcast(config.message("welcome", "player" to player.name, "rank" to rank))
```

- `config.message(path, vararg "key" to value)` — берёт строку, заменяет
  каждый `@key@` на `value.toString()`, красит `&` → `§`.
- `config.messageList(path, ...)` — то же самое, но для списка строк
  (`farewell-list: [...]` в YAML) — удобно для многострочных сообщений.
- `config.richMessage(path, ...)` — то же самое, но возвращает MiniMessage
  `Component` через уже существующий `parseMM`, если сообщение с тегами
  вида `<gold>`.
- `config.getOrSetDefault(path, default)` — если ключа в конфиге нет, ставит
  дефолт и сохраняет (`saveConfig()`) на сам конфиг скрипта; если ключ уже
  есть — просто возвращает текущее значение. Так конфиг скрипта постепенно
  сам себя документирует новыми ключами при обновлении скрипта, без ручного
  "если нет — добавь" в каждом месте.

**Важная деталь**: автосохранение в `getOrSetDefault` срабатывает только для
основного `config` скрипта — если вызвать этот же метод на файле, загруженном
через `loadYaml("other.yml")`, значение выставится в памяти, но не сохранится
автоматически на диск (у `loadYaml` нет привязки "как сохранить именно этот
файл обратно", в отличие от `config`/`saveConfig()`). Раньше в черновике этот
метод безусловно дёргал `saveConfig()` — это значило бы, что дефолт для
`other.yml` тихо запишется не в тот файл, в `config.yml` скрипта. Поймал
до релиза и ограничил автосохранение только настоящим `config`.

## DB — общая key-value таблица между скриптами (`table()`)

`persist()` — состояние ОДНОГО скрипта. `table()` — общая таблица, которую
может читать и писать любой скрипт, если оба обратились к одному имени, и
она переживает не только `/ksl reload`, но и полный рестарт сервера (SQLite,
таблица `ksl_table`).

```kotlin
val kills = table("player_stats")

kills.set(player.uniqueId.toString(), "kills", 5)
val count = kills.getInt(player.uniqueId.toString(), "kills", default = 0)
val total = kills.increment(player.uniqueId.toString(), "kills")
kills.delete(player.uniqueId.toString(), "kills")   // удалить одно значение
kills.delete(player.uniqueId.toString())            // удалить всю строку
val allColumns = kills.row(player.uniqueId.toString())  // Map<String, Any?>
```

Поддерживаемые типы значений — те же, что и у `persist`: `String`, `Int`,
`Long`, `Double`, `Boolean`, `null` (переиспользует уже протестированный
`KSLPersistCodec`, а не отдельную копию логики кодирования).

`set`/`delete` асинхронны и идут через тот же `dbExecutor` (см. раздел про
`dbExecute`/`dbQuery` выше) — гарантированный порядок операций для одной
таблицы. Чтения (`get*`, `row`) синхронны — единичный `SELECT` по индексу
достаточно быстрый, чтобы не городить асинхронщину там, где скрипту нужен
результат сразу. `increment` — особый случай: чтобы вернуть новое значение
сразу (а не заставлять скрипт делать `increment` + отдельный `get`), он тоже
идёт через `dbExecutor`, но синхронно дожидается результата — за счёт этого
конкурентные `increment` от разных скриптов/потоков не гонятся друг с другом
и не теряют инкременты (весь read-modify-write происходит на одном потоке).

## Расширенные `@placeholder@`

### Авто-плейсхолдеры от игрока

`message`/`messageList`/`richMessage` получили перегрузку, принимающую `Player`
вторым аргументом — она сама заполняет самые частые плейсхолдеры, не заставляя
передавать их вручную в каждом вызове:

```kotlin
broadcast(config.message("welcome", player, "rank" to rank))
```

Автоматически доступны: `@player@`, `@world@`, `@online@`, `@maxplayers@`,
`@health@`, `@ping@`. Свои плейсхолдеры (как `rank` выше) добавляются той же
vararg-парой, что и раньше — обе формы комбинируются в одном вызове.

### Прозрачная интеграция с PlaceholderAPI

Если передан `Player` и PlaceholderAPI установлен — после подстановки `@..@`
текст ещё и прогоняется через `PlaceholderAPI.setPlaceholders(player, text)`.
Значит в `config.yml` можно писать не только свои `@custom@`, но и любые
`%luckperms_prefix%`, `%vault_eco_balance%` и что угодно ещё, что понимает
PAPI — в одной и той же строке одновременно с собственными плейсхолдерами.
Ссылка на классы PAPI изолирована в отдельном `KSLPapiResolver` (по той же
схеме, что и остальные хуки Vault/LuckPerms/EssentialsX) — вызывается только
когда `papiEnabled == true`, поэтому отсутствие PAPI на сервере не мешает
компиляции/загрузке класса.

### Умное форматирование чисел

`"balance" to 15.0` раньше вставило бы в сообщение буквально `15.0`. Теперь
`Double` форматируется по-человечески: целые значения без `.0` (`15`, не
`15.0`), дробные — до двух знаков (`15.5`, не `15.500000000000002`).

### Предупреждение о забытом/опечатанном плейсхолдере

Реальная проблема, которую эта фича ловит сама: если написать в `config.yml`
`@playr@` вместо `@player@` — раньше сообщение просто отправлялось с буквальным
текстом `@playr@` внутри, без единого намёка, что что-то не так. Теперь после
всех подстановок текст сканируется на предмет оставшихся `@..@`-паттернов —
если что-то не заменилось, в лог уходит явный warning с именем скрипта, путём
в конфиге и самим незаменённым плейсхолдером. Предупреждение выводится один
раз на комбинацию (скрипт, путь, плейсхолдер) за сессию — не будет спамить
консоль при каждом вызове одного и того же `broadcast` в цикле.

## 1.2.0 — движок эффектов (`playEffect`)

```kotlin
player.location.playEffect(Particle.FLAME) {
    circle(radius = 2.0, stepDegrees = 15.0)
    durationTicks = 60L
    intervalTicks = 2L
}

player.location.playEffect(Particle.SOUL_FIRE_FLAME) {
    helix(radius = 1.5, turns = 3.0, height = 3.0)
    durationTicks = 60L
}

player.eyeLocation.playEffect(Particle.END_ROD) {
    lineTo({ target.location }, points = 25)
    durationTicks = 40L
}

boss.playEffect(Particle.FLAME, follow = true) {
    circle(radius = 2.0, stepDegrees = 15.0)
    durationTicks = 20L * 60 * 5
}
```

Вся математика (`sin`/`cos`) живёт в `KSLEffectShapes` — скомпилированном ядре
плагина, не в `.kts`. Скрипт передаёт только конфигурацию: форму, длительность,
интервал между кадрами. Анимация крутится через `Bukkit.getScheduler().runTaskTimer`,
никакого `Thread.sleep` — сервер не подвисает даже если несколько игроков
активируют эффекты одновременно.

Три формы "из коробки": `circle(radius, stepDegrees)`, `helix(radius, turns, height)`
(двойная спираль, как ДНК), `lineTo(target: () -> Location, points)` (луч/линия
до подвижной цели — `target` вызывается каждый тик, так что линия следует за
целью, если она движется).

`Entity.playEffect(particle, follow = true) { }` — эффект следует за игроком/мобом
каждый тик вместо фиксированной точки. Follow-эффект автоматически
останавливается, если сущность, за которой он следует, умерла или стала
невалидной — не будет крутиться вокруг трупа до конца `durationTicks`.

Таски эффектов регистрируются через тот же `plugin.trackTask`, что и `every()`/
`delay()` из 1.1.0 — значит `/ksl reload <script>` отменяет их автоматически,
без единой строчки нового кода для очистки специально под эффекты.

## 1.2.0 — кастомные сущности (`spawnCustomEntity` + `behavior`)

```kotlin
val boss = spawnCustomEntity(
    player.location,
    """TYPE:ZOMBIE NAME:"<red><bold>Огненный Босс</bold></red>" HP:200 AI:TRUE"""
) {
    equip("HAND:DIAMOND_SWORD HELMET:NETHERITE_HELMET")
}

boss.behavior {
    onTick(period = 20L) { entity ->
        entity.getNearbyEntities(3.0, 3.0, 3.0)
            .filterIsInstance<Player>()
            .forEach { it.damage(2.0, entity) }
    }

    onDeath { entity, killer ->
        broadcastMM("<gold>${entity.name} повержен игроком ${killer?.name}!")
    }
}
```

Строковый шаблон парсится через `KSLTemplateParser` — тот же парсер, что и у
`equip(...)`, не два разных с разной логикой. Поддерживает значения в кавычках
для текста с пробелами: `NAME:"<red>Big Boss</red>"`. Понимаемые ключи:
`TYPE` (EntityType), `NAME` (через MiniMessage, автоматически ставит
`isCustomNameVisible = true`), `HP` (максимальное здоровье + хилит до него),
`AI` (`setAI(true/false)`), `BABY` (для `Ageable`-мобов). `equip(...)` — свои
ключи: `HAND`, `OFFHAND`, `HELMET`, `CHESTPLATE`, `LEGGINGS`, `BOOTS`.

**Главный капкан таких движков — привязка логики к скрипту при reload — решён
на двух уровнях:**

1. `onTick` внутри `behavior { }` регистрируется через тот же `plugin.trackTask`,
   что и таски эффектов и `every()`/`delay()` — отменяется автоматически при
   `/ksl reload <script>`, без знания о сущностях вообще на уровне общего
   механизма очистки.
2. `onDeath` — **не** через `onEvent<EntityDeathEvent>` (это означало бы
   отдельный глобальный обработчик на каждого заспавненного моба — та же
   ошибка масштабирования, которую разбирали в разделе про GUI 1.1.0).
   Вместо этого `KSLEntityManager` — один-единственный `EntityDeathEvent`
   listener на весь плагин, диспетчеризация по `UUID` сущности через
   `ConcurrentHashMap`, O(1) независимо от количества мобов в мире.

Владелец записывается прямо в моба через `PersistentDataContainer`
(`ksl_owner = "boss.kts"`) — не только для внутренней очистки, но и чтобы
пережить рестарт сервера в виде обычных данных моба. При `/ksl reload boss`
все мобы этого скрипта автоматически теряют привязанное поведение (`onDeath`
снимается, `onTick`-таски отменяются) и по умолчанию удаляются из мира —
это настраивается через `spawnCustomEntity(..., removeOnReload = false)`,
если моб должен просто "замолчать" (потерять логику), но остаться в мире.

**Ловушка с порядком вызовов, пойманная при проектировании.** `helix()` должен
знать финальное значение `durationTicks`, чтобы спираль поднялась на всю высоту
ровно за время жизни эффекта — но `durationTicks = ...` в блоке может быть
написан ПОСЛЕ вызова `helix(...)`. Форма поэтому не строится сразу внутри
`circle`/`helix`/`lineTo`, а материализуется один раз в момент запуска эффекта,
уже после того как весь конфигурационный блок полностью выполнился —
порядок вызовов внутри `{ }` не имеет значения.
