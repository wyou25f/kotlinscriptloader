# Разработка аддонов для KotlinScriptLoader (KSL API)

Это руководство для тех, кто хочет написать свой Bukkit/Paper-плагин, который расширяет KotlinScriptLoader: добавляет сервисы, доступные из `.kts`-скриптов, свои импорты и хуки в жизненный цикл скрипта.

Аддон KSL — это обычный Paper-плагин, который дополнительно реализует интерфейс `KSLAddon` и регистрируется в KSL через статический объект `KSL`.

---

## 1. Как это устроено

Три сущности составляют публичный API:

| Сущность | Назначение |
|---|---|
| `KSL` | Точка входа. Статический `object`, живёт пока жив плагин KSL. |
| `KSLAPI` | Интерфейс, через который аддон регистрирует свои возможности. |
| `KSLAddon` | Интерфейс, который реализует твой плагин. |
| `KSLContextExtension` | Опционально — хук на создание/уничтожение контекста каждого скрипта. |

Схема потока:

```
Твой плагин onEnable()
        │
        ▼
KSL.isAvailable ? ──нет──► лог ошибки, isEnabled = false
        │ да
        ▼
KSL.api.registerAddon(this)
        │
        ▼
KSLAddonManager вызывает твой onLoad(api: KSLAPI)
        │
        ▼
Внутри onLoad ты вызываешь:
  api.registerService("ключ", instance)
  api.addDefaultImports("com.example.*")
  api.registerContextExtension(...)
```

Дальше любой `.kts`-скрипт на сервере может получить твой сервис через:

```kotlin
val myService = service<MyService>("ключ") ?: error("аддон не установлен")
```

---

## 2. Требования к проекту

- Kotlin 2.0+, JVM target 21 (как и сам KSL — иначе будет `Cannot inline bytecode built with JVM target 21 into bytecode built with JVM target 1.8`, если ты добавляешь свои inline-функции в скрипты).
- Paper API 1.21.1+.
- `KotlinScriptLoader.jar` как `compileOnly` зависимость (сам класс не шейдится в твой jar — он должен быть уже загружен как отдельный плагин на сервере).
- `depend: [KotlinScriptLoader]` в `plugin.yml` — гарантирует, что KSL загрузится раньше твоего аддона.

### build.gradle.kts

```kotlin
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
    compileOnly(files("libs/KotlinScriptLoader-1.0.0.jar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
```

Путь `files("libs/KotlinScriptLoader-1.0.0.jar")` — положи собранный jar основного плагина в `libs/` своего проекта (или подключи как `compileOnly project(":KotlinScriptLoader")`, если это модуль одного мультипроекта).

### plugin.yml

```yaml
name: MyKSLAddon
version: ${version}
main: com.example.MyAddonPlugin
api-version: '1.21'
depend: [KotlinScriptLoader]
```

`depend`, а не `softdepend` — твой аддон без KSL не имеет смысла, и явная зависимость гарантирует правильный порядок загрузки.

---

## 3. Интерфейс `KSLAddon`

```kotlin
interface KSLAddon {
    val addonId: String
    val addonVersion: String
    val addonDescription: String get() = ""
    fun onLoad(api: KSLAPI)
    fun onUnload()
}
```

- `addonId` — уникальный ключ, под которым аддон виден в `/ksl addons`. Используй kebab-case: `my-addon`, а не название плагина с большой буквы.
- `addonVersion` — просто для отображения в логах и `/ksl addons`.
- `addonDescription` — опционально, тоже только для отображения.
- `onLoad(api)` — вызывается один раз при регистрации. Здесь регистрируй сервисы, импорты, расширения контекста.
- `onUnload()` — вызывается при `unregisterAddon`. Обычно вызывается из твоего же `onDisable()`.

---

## 4. Интерфейс `KSLAPI`

Это то, что тебе доступно внутри `onLoad`:

```kotlin
interface KSLAPI {
    val kslPlugin: JavaPlugin
    fun registerAddon(addon: KSLAddon)
    fun unregisterAddon(addonId: String)
    fun registerService(key: String, instance: Any)
    fun unregisterService(key: String)
    fun getService(key: String): Any?
    fun addDefaultImports(vararg packages: String)
    fun registerContextExtension(extension: KSLContextExtension)
    fun unregisterContextExtension(extensionId: String)
    fun registeredAddons(): List<KSLAddon>
}
```

### `registerService(key, instance)`

Регистрирует произвольный объект под строковым ключом. Скрипты достают его через `service<T>(key)`. Ключ — то, что видит пользователь скрипта, выбирай осмысленное имя (`"coins"`, `"gui"`, `"warps"`).

### `addDefaultImports(vararg packages)`

Добавляет пакеты в `defaultImports` компиляции скриптов — так пользователю не нужно писать `import com.example.CoinService` в каждом `.kts`. Принимает то же, что и обычный Kotlin `import`: `"com.example.CoinService"` или `"com.example.*"`.

### `registerContextExtension(extension)`

Регистрирует хук, который получает уведомление при создании и уничтожении контекста каждого скрипта — полезно, если твоему сервису нужно держать состояние на скрипт (например, свою мини-БД для конкретного `.kts` файла).

### `getService` / `unregisterService` / `unregisterAddon` / `registeredAddons`

Симметричные операции — обычно нужны только если ты пишешь что-то вроде мета-аддона, который сам работает с другими аддонами.

---

## 5. Минимальный рабочий пример

Разберём на примере аддона с внутриигровой валютой.

### CoinService.kt — сам сервис

```kotlin
package com.example

import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

class CoinService {

    private val coins = ConcurrentHashMap<String, Long>()

    fun get(player: Player): Long = coins.getOrDefault(player.name, 0L)

    fun give(player: Player, amount: Long) {
        coins.merge(player.name, amount, Long::plus)
    }

    fun take(player: Player, amount: Long): Boolean {
        val current = get(player)
        if (current < amount) return false
        coins[player.name] = current - amount
        return true
    }

    fun set(player: Player, amount: Long) {
        coins[player.name] = amount
    }
}
```

Ничего специфичного для KSL здесь нет — обычный Kotlin-класс. Используй `ConcurrentHashMap`, а не `HashMap`, если к сервису могут одновременно обращаться скрипты с разных потоков (PAPI, асинхронные таски и т.д.).

### MyAddonPlugin.kt — точка входа плагина

```kotlin
package com.example

import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon

class MyAddonPlugin : JavaPlugin(), KSLAddon {

    override val addonId = "coins-addon"
    override val addonVersion = "1.0.0"
    override val addonDescription = "Внутриигровая валюта для скриптов"

    private val coinService = CoinService()

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader не найден!")
            isEnabled = false
            return
        }
        KSL.api.registerAddon(this)
    }

    override fun onDisable() {
        if (KSL.isAvailable) {
            KSL.api.unregisterAddon(addonId)
        }
    }

    override fun onLoad(api: KSLAPI) {
        api.registerService("coins", coinService)
        api.addDefaultImports("com.example.CoinService")
    }

    override fun onUnload() {
    }
}
```

Обрати внимание:

- Проверка `KSL.isAvailable` в `onEnable` обязательна. Если по какой-то причине KSL не загрузился (например, ошибка инициализации БД), твой плагин не должен пытаться дёргать `KSL.api` — это кинет исключение (`KSL.api` бросает `error(...)`, если `_api == null`). Вместо этого мягко выключаем себя через `isEnabled = false`.
- `onUnload()` может быть пустым, если сервису нечего освобождать. Если сервис держит соединения, потоки или файлы — закрывай их здесь.

### Использование в скрипте

```kotlin
val coins = service<com.example.CoinService>("coins")
    ?: error("CoinsAddon не установлен")

registerCommand("coins") { player, args ->
    when (args.getOrNull(0)) {
        "give" -> {
            val amount = args.getOrNull(1)?.toLongOrNull() ?: 0L
            coins.give(player, amount)
            player.sendRichMessage("<green>+$amount монет. Итого: ${coins.get(player)}")
        }
        "take" -> {
            val amount = args.getOrNull(1)?.toLongOrNull() ?: 0L
            if (coins.take(player, amount)) {
                player.sendRichMessage("<red>-$amount монет. Итого: ${coins.get(player)}")
            } else {
                player.sendRichMessage("<red>Недостаточно монет!")
            }
        }
        else -> player.sendRichMessage("<yellow>Монеты: <white>${coins.get(player)}")
    }
}
```

Если ты вызвал `addDefaultImports("com.example.CoinService")` в `onLoad`, то в скрипте можно писать просто `service<CoinService>("coins")` без полного пути.

---

## 6. `KSLContextExtension` — состояние на каждый скрипт

Если сервису нужно хранить что-то отдельно для каждого `.kts`-файла (а не глобально), используй `KSLContextExtension`:

```kotlin
interface KSLContextExtension {
    val extensionId: String
    fun onContextCreated(context: BukkitScriptContext)
    fun onContextDestroyed(scriptName: String)
}
```

- `onContextCreated(context)` вызывается при загрузке каждого скрипта (включая `/ksl reload`). `context.scriptName` — имя скрипта без `.kts`.
- `onContextDestroyed(scriptName)` вызывается при выгрузке скрипта — самое подходящее место, чтобы почистить состояние, привязанное к этому скрипту.

Пример — сервис, который считает, сколько раз каждый скрипт вызвал определённое действие:

```kotlin
package com.example

import ru.privateserver.ksl.BukkitScriptContext
import ru.privateserver.ksl.KSLContextExtension
import java.util.concurrent.ConcurrentHashMap

class CoinContextExtension(private val coinService: CoinService) : KSLContextExtension {

    override val extensionId = "coins-context"

    override fun onContextCreated(context: BukkitScriptContext) {
    }

    override fun onContextDestroyed(scriptName: String) {
    }
}
```

Зарегистрируй его вместе с сервисом в `onLoad`:

```kotlin
override fun onLoad(api: KSLAPI) {
    api.registerService("coins", coinService)
    api.registerContextExtension(CoinContextExtension(coinService))
    api.addDefaultImports("com.example.CoinService")
}
```

Если тебе не нужно состояние на скрипт — просто не регистрируй `KSLContextExtension`, он не обязателен.

---

## 7. Жизненный цикл и порядок загрузки

1. Сервер запускается. Paper грузит плагины в порядке зависимостей — раньше всех то, от чего зависят остальные.
2. KSL включается (`onEnable`), инициализирует БД, интеграции, вызывает `KSL.init(addonManager)` — с этого момента `KSL.isAvailable == true`.
3. Твой аддон включается (позже, благодаря `depend: [KotlinScriptLoader]`), вызывает `KSL.api.registerAddon(this)`.
4. `KSLAddonManager.registerAddon` вызывает твой `onLoad(api)` — здесь ты регистрируешь сервисы, импорты, расширения.
5. KSL загружает `.kts`-скрипты из `scripts/` — уже с учётом твоих сервисов и импортов.

При `/ksl reload`:

- Скрипты выгружаются и загружаются заново.
- Аддоны **не** перезагружаются — твой плагин продолжает жить, сервисы остаются зарегистрированными.
- Новые скрипты сразу видят твои сервисы и импорты.

При остановке сервера:

- KSL вызывает `unregisterAddon` для всех аддонов в своём `onDisable`, но по-хорошему твой плагин должен и сам вызвать `unregisterAddon` в своём `onDisable` — Paper не гарантирует порядок остановки плагинов настолько строго, насколько порядок запуска.

---

## 8. Команды для проверки

- `/ksl addons` — список загруженных аддонов с версиями и описанием.
- `/ksl services` — список зарегистрированных ключей сервисов.

Если твой аддон не появляется в `/ksl addons` — скорее всего `onEnable` упал раньше `registerAddon` (смотри лог на предмет ошибки инициализации) либо `depend` в `plugin.yml` не указан и твой плагин пытался вызвать `KSL.api` до того, как KSL успел проинициализироваться.

---

## 9. Частые ошибки

**`error("KotlinScriptLoader не установлен или ещё не инициализирован")` при старте сервера**
Значит `KSL.api` вызван до `KSL.init(...)`. Проверь `depend: [KotlinScriptLoader]` в своём `plugin.yml` — без него порядок загрузки не гарантирован.

**Скрипт компилируется, но `service<T>(key)` возвращает `null`**
Ключ в `service<T>("ключ")` должен точно совпадать со строкой в `registerService("ключ", ...)`. Также проверь `/ksl services` — если ключа там нет, значит `onLoad` либо не был вызван, либо упал с исключением (смотри лог, `KSLAddonManager` ловит исключения из `onLoad` и логирует их, не давая упасть всему серверу).

**`Cannot inline bytecode built with JVM target 21 into bytecode built with JVM target 1.8`**
Возникает, если ты регистрируешь в `addDefaultImports` пакет с inline-функциями, которые сам не собирал под `-jvm-target 21`. Собирай свой аддон под Java 21 (см. `build.gradle.kts` выше) — этого обычно достаточно, конфигурация компиляции скриптов уже выставляет `-jvm-target 21` на стороне KSL.

**`ClassNotFoundException` на классы твоего сервиса при выполнении скрипта**
Обычно значит, что твой jar не оказался в classpath, из которого KSL собирает скрипты. Проверь, что твой плагин действительно загружен (`/plugins`) и что `addDefaultImports` указывает реальный путь к классу.

---

## 10. Чеклист перед публикацией аддона

- [ ] `addonId` уникален и в kebab-case.
- [ ] `depend: [KotlinScriptLoader]` в `plugin.yml`.
- [ ] `onEnable` проверяет `KSL.isAvailable` перед обращением к `KSL.api`.
- [ ] `onDisable` вызывает `KSL.api.unregisterAddon(addonId)`.
- [ ] Сервисы, к которым могут одновременно обращаться разные скрипты/потоки, используют потокобезопасные коллекции.
- [ ] Собран под JVM target 21.
- [ ] Есть хотя бы один пример `.kts`-скрипта в описании/README аддона — так пользователям проще понять, как им пользоваться.
