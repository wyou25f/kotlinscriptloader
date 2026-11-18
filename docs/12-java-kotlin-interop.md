# Java и Kotlin API в KSL: interop без лишней рутины

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
