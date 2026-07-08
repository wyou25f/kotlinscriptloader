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
