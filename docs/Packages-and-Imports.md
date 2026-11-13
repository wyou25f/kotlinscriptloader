# Пакеты, импорты и локальные библиотеки в KSL

Это руководство объясняет, как организовать собственный Kotlin-код для KotlinScriptLoader (KSL), подключить его к .kts и сделать библиотеку для нескольких скриптов.

> Коротко: KSL компилирует .kts как Kotlin Script. Обычные Kotlin-файлы аддона (.kt) компилируются Gradle в классы внутри JAR аддона. KSL добавляет classloader загруженного аддона в classpath компиляции и выполнения скриптов. Поэтому скрипт может импортировать публичные классы и top-level-функции аддона.

## 1. Четыре понятия, которые важно не путать

| Понятие | Что означает | Где объявляется |
|---|---|---|
| package | Пространство имён Kotlin-кода | В начале файла .kt |
| import | Подключение класса или объявления из пакета | В .kt и .kts |
| defaultImports | Импорты, которые KSL автоматически добавляет при компиляции каждого скрипта | В onLoad аддона |
| classpath | Классы и библиотеки, которые компилятор и скрипт могут найти | JAR аддонов и доступные им зависимости |

**Важно:** addDefaultImports("com.example.tools.*") добавляет импорт, но не загружает библиотеку и не компилирует исходники. Чтобы импорт работал, пакет должен находиться в classpath — например, в JAR загруженного аддона.

## 2. Рекомендуемая структура аддона

Для примера возьмём аддон ServerToolkit:

~~~text
ServerToolkit/
├── build.gradle.kts
├── settings.gradle.kts
└── src/
    └── main/
        ├── kotlin/
        │   └── com/example/servertoolkit/
        │       ├── ServerToolkitPlugin.kt
        │       ├── service/MessageService.kt
        │       ├── player/PlayerExtensions.kt
        │       └── text/TextExtensions.kt
        └── resources/
            └── plugin.yml
~~~

Структура каталогов обычно повторяет имя пакета, но для Kotlin решающей является строка package внутри файла.

PlayerExtensions.kt:

~~~kotlin
package com.example.servertoolkit.player

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

fun Player.sendToolkitMessage(message: String) {
    sendMessage(Component.text("[Toolkit] $message"))
}

fun Player.toolkitLabel(): String = "Игрок: $name"
~~~

Это обычные Kotlin extension-функции. Специальный KSL DSL для них не нужен.

Другой файл может использовать их как обычный Kotlin-код:

~~~kotlin
package com.example.servertoolkit.service

import com.example.servertoolkit.player.sendToolkitMessage
import org.bukkit.entity.Player

class MessageService {
    fun welcome(player: Player) {
        player.sendToolkitMessage("Добро пожаловать!")
    }
}
~~~

## 3. Настройка Gradle

Пример минимального build.gradle.kts:

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

Положи используемый для компиляции KSL JAR в libs/ либо подключи KSL как Gradle project dependency в мультипроекте. Версии Kotlin, Paper и JVM должны соответствовать версии KSL и сервера.

Не добавляй KSL API и Paper API в итоговый JAR аддона: они уже предоставляются сервером. Для внешних runtime-библиотек выбери поддерживаемый твоей сборкой способ доставки — например, включение зависимостей в JAR через Shadow. Важно, чтобы классы этих библиотек были доступны classloader'у аддона на сервере. Не включай вторую копию API другого плагина, если он предоставляет общие типы и сервисы.

## 4. plugin.yml

~~~yaml
name: ServerToolkit
version: 1.0.0
main: com.example.servertoolkit.ServerToolkitPlugin
api-version: '1.21'
depend:
  - KotlinScriptLoader
~~~

depend нужен, если аддон не может работать без KSL: Bukkit загрузит KSL раньше него. Если интеграция действительно необязательна, можно выбрать другую модель зависимости, но тогда проверка доступности KSL обязательна.

## 5. Регистрация аддона и default imports

Точка входа:

~~~kotlin
package com.example.servertoolkit

import com.example.servertoolkit.service.MessageService
import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon

class ServerToolkitPlugin : JavaPlugin(), KSLAddon {
    override val addonId = "server-toolkit"
    override val addonVersion = "1.0.0"
    override val addonDescription = "Общие функции для KSL-скриптов"

    private val messages = MessageService()

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader недоступен")
            server.pluginManager.disablePlugin(this)
            return
        }
        KSL.api.registerAddon(this)
    }

    override fun onLoad(api: KSLAPI) {
        api.registerService("toolkit.messages", messages)
        api.addDefaultImports(
            "com.example.servertoolkit.service.MessageService",
            "com.example.servertoolkit.player.*"
        )
    }

    override fun onUnload() {
        // Закрой здесь собственные ресурсы, если они есть.
    }

    override fun onDisable() {
        if (KSL.isAvailable) KSL.api.unregisterAddon(addonId)
    }
}
~~~

Что делают вызовы:

- registerService публикует объект, который скрипт может получить через service<T>(key).
- addDefaultImports добавляет импорты в компиляцию KSL-скриптов. Скриптам не придётся повторять их вручную.
- KSL связывает регистрации с аддоном. При его выгрузке зарегистрированные через API импорты и сервисы автоматически снимаются.

Регистрируй импорты в onLoad(api): так KSL знает владельца регистрации и может корректно очистить её при выгрузке.

## 6. Явные import внутри .kts

После загрузки аддона его classloader доступен скриптовому компилятору и загрузчику классов. Поэтому в .kts можно написать обычные Kotlin-импорты:

~~~kotlin
import com.example.servertoolkit.service.MessageService
import com.example.servertoolkit.player.toolkitLabel
import com.example.servertoolkit.player.sendToolkitMessage

val messages = service<MessageService>("toolkit.messages")
    ?: error("ServerToolkit не загружен")

registerCommand("toolkit") { player, _ ->
    player.sendToolkitMessage(player.toolkitLabel())
}
~~~

Если аддон зарегистрировал пакет через addDefaultImports, эти строки можно опустить.

Синтаксис импортов — обычный Kotlin:

~~~kotlin
// Один класс
import com.example.servertoolkit.service.MessageService

// Все доступные объявления конкретного пакета
import com.example.servertoolkit.player.*

// Отдельная top-level функция
import com.example.servertoolkit.player.toolkitLabel

// Псевдоним, если имена конфликтуют
import com.example.servertoolkit.text.format as toolkitFormat
~~~

Wildcard импорт не рекурсивный: com.example.servertoolkit.* не означает автоматический импорт из подпакетов player и text. Добавляй каждый нужный пакет отдельно.

## 7. Можно ли объявить package прямо в .kts?

Для обычного KSL-скрипта используй его как script-файл, а не как Kotlin source-файл с объявлением пакета. Организуй повторно используемый код в обычных .kt-файлах аддона:

- .kt содержит package, классы, объекты и top-level-функции;
- Gradle компилирует их в классы аддона;
- .kts импортирует эти объявления через обычный import или получает их через defaultImports.

KSL не сканирует папку scripts/ и не компилирует произвольные соседние .kt-файлы как отдельные исходники. Просто положить Utils.kt рядом с my-script.kts недостаточно, чтобы его пакет стал доступен.

## 8. Своя библиотека: когда нужен сервис, а когда extension-функции

Используй extension-функции для небольших удобных операций:

~~~kotlin
package com.example.servertoolkit.text

fun String.withPrefix(): String = "[Server] $this"
~~~

Используй класс-сервис, когда есть состояние, конфигурация, доступ к БД, кэш или жизненный цикл:

~~~kotlin
package com.example.servertoolkit.service

class MessageService {
    fun prefix(message: String): String = "[Server] $message"
}
~~~

Сервис регистрируется один раз в onLoad, а скрипты получают его через service<T>(key). Не создавай отдельную копию сервиса в каждом скрипте, если состояние должно быть общим.

### Несколько библиотек и аддонов

Можно разделить функциональность на несколько аддонов: server-text для форматирования, server-economy для баланса и server-gui для меню. Каждый аддон публикует только свои пакеты и сервисы.

Для зависимости от другого KSL-аддона используй api.onAddonReady("addon-id") { ... } (или onAddonReady напрямую), а не предполагай, что сервис уже зарегистрирован. dependsOn описывает зависимость KSL-аддонов и помогает диагностировать её отсутствие; для отложенной привязки предназначен callback onAddonReady.

## 9. Что происходит с classpath и выгрузкой

При компиляции KSL собирает текущие default imports и classloader'ы зарегистрированных аддонов. Их классы становятся доступны компилятору. При выполнении скрипта используется addon-aware classloader, чтобы те же классы были доступны и в runtime.

Практические следствия:

1. Аддон должен быть включён до компиляции скрипта, который импортирует его классы.
2. После установки, обновления или выгрузки аддона перезагрузи затронутые скрипты.
3. Не оставляй загруженные скрипты, которые продолжают использовать классы уже выгруженного аддона.
4. Не упаковывай KSL API внутрь аддона: дублирование типов между classloader'ами может привести к несовместимости сервисов.
5. Уникальные package names и ключи сервисов снижают риск конфликтов.

## 10. Потоки и безопасность

- Bukkit API для игроков, миров, инвентарей и сущностей используй в основном потоке сервера, если документация конкретного API не говорит обратное.
- Не выполняй блокирующий SQL, сетевые запросы и тяжёлые операции в обработчиках событий на основном потоке.
- Старайся делать API аддона небольшим и предсказуемым.
- Не считай KSL sandbox заменой проверке доверия к стороннему JAR-аддону: аддон — полноценный Bukkit-плагин с правами кода плагина.
- Не публикуй изменяемые коллекции наружу без необходимости; продумывай синхронизацию, если сервис вызывается из нескольких потоков.

## 11. Проверка, если import не работает

1. Убедись, что аддон включён и отображается в /ksl addons.
2. Проверь точное имя пакета в строке package исходного .kt.
3. Убедись, что импорт указывает на пакет, в котором действительно лежит функция или класс.
4. Проверь, что функция или класс имеют public-видимость (это значение по умолчанию в Kotlin), а не private или internal.
5. Убедись, что нужная зависимость доступна classloader'у аддона на сервере.
6. Если используешь defaultImports, проверь, что регистрация сделана в onLoad и что пакет написан без опечаток.
7. После изменения JAR перезапусти сервер или корректно перезагрузи аддон и затем скрипты. Не полагайся на горячую замену классов уже загруженного classloader'а.

## 12. Связанные руководства

- [Разработка аддонов](Addon-Development-Guide.md) — API, lifecycle, сервисы, события и context extensions.
- [Своя Standard Library](Custom-Standard-Library.md) — проектирование библиотеки расширений.
- [Публичный API KSL](Public-API.md) — стабильные точки интеграции.
- [Архитектура Standard Library](Standard-Library-Architecture.md) — встроенные пакеты и соглашения.
