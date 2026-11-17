# KSL Addon Starter

Готовый Kotlin/Paper-проект для разработки аддона под KotlinScriptLoader 1.2.1 builds.

## Требования

- JDK 21
- Gradle
- Paper API той версии, с которой запускается сервер
- JAR KSL той же сборки, которая установлена на сервере

## Открытие проекта

1. Скопируйте эту папку в отдельный каталог.
2. Откройте каталог в IntelliJ IDEA как Gradle project.
3. Укажите локальный JAR KSL:

    ./gradlew -PkslJar=/absolute/path/to/KotlinScriptLoader-1.2.1.jar compileKotlin

По умолчанию Gradle ожидает JAR в libs/KotlinScriptLoader-1.2.1.jar. Используйте JAR версии 1.2.1, установленный на тестовом сервере, и передавайте путь через `-PkslJar`, если он лежит не в `libs/`.

## Структура

- ExampleAddon.kt — регистрация KSLAddon, service и lifecycle.
- ExampleScheduler.kt — пример адаптера scheduler SPI.
- dsl/PlayerDsl.kt — собственные Kotlin extension-функции для KTS.
- scripts/example.kts — пример использования addon DSL и scriptLogger.
- ExampleAddon.kt — пример api.createLogger, уровней и дочерней категории.

## Рекомендуемый подход

- Используйте публичные типы из ru.privateserver.ksl.api.*, если они доступны.
- Создавайте KSLAddonScope в onLoad и закрывайте в onUnload.
- Используйте async reload для обычных addon callbacks.
- Не вызывайте Bukkit world/player/inventory API из worker threads.
- Добавляйте addon DSL в KTS через api.addDefaultImports("example.addon.dsl.*").
- Держите зависимости аддона в Gradle и проверьте, что они доступны на сервере.
- Для логов используйте api.createLogger(addonId, name, config) или api.logger(...) из Kotlin DSL.
- Администратор задаёт минимум logging.default-level и точечные logging.owner-levels в KSL config.yml; logger не может понизить серверный минимум.


## Сборка

    ./gradlew clean build

Собранный plugin JAR находится в build/libs/. Перед production проверьте его на staging Paper-сервере.
