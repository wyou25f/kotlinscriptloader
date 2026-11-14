# Документация KotlinScriptLoader

Документы для пользователей KSL, авторов скриптов, разработчиков аддонов и контрибьюторов.

> KSL развивается. Если старый пример расходится с кодом текущей ветки, проверяй исходники и тесты. Упоминание функции в плане или changelog не доказывает, что она есть в текущей сборке.

## С чего начать

| Задача | Документ |
|---|---|
| Установка и первый скрипт | [Getting Started](Getting-Started.md) |
| Полный Script DSL | [Script DSL Reference](Script-DSL-Reference.md) |
| Практические рецепты | [Cookbook](Cookbook.md) |
| Установка, общий обзор и архитектура | [Энциклопедия KSL](KSL-Encyclopedia.md) |
| YAML, TOML, JSON и миграции | [Data Formats](Data-Formats.md) |
| Производительность, DB очереди и worker pool | [Performance and Scaling](Performance-and-Scaling.md) |
| Production, секреты, backup и recovery | [Production Operations](Production-Operations.md) |
| Встроенная библиотека, MesLib2 и runtime tools | [Standard Library](Standard-Library-Architecture.md) |
| Создание аддона | [Addon Development](Addon-Development-Guide.md) |
| API для Kotlin/Java аддонов | [Public API](Public-API.md) |
| Импорты и локальные библиотеки | [Packages and Imports](Packages-and-Imports.md) |
| MesLib2 и создание своих библиотек | [Custom Standard Library](Custom-Standard-Library.md) |
| SQLite, MySQL/MariaDB и async SQL | [Database Guide](Database-Guide.md) |
| Кэш и профилирование | [Runtime API](Runtime-API.md) |
| Жизненный цикл, hot-reload и владение ресурсами | [Script Lifecycle and Resources](Script-Lifecycle-and-Resources.md) |
| Собственный модуль stdlib | [Custom Standard Library](Custom-Standard-Library.md) |
| Ошибки и диагностика | [Troubleshooting](Troubleshooting.md) |
| Коды ошибок | [Error Codes](Error-Codes.md) |
| Sandbox и ограничения | [Security and Sandbox](Security-and-Sandbox.md) |

## Для ИИ-ассистентов

Если ChatGPT, Claude, Gemini, Copilot или другой помощник пишет или проверяет код KSL, начни с [AGENTS.md](../AGENTS.md) и [руководства по работе с ИИ](AI-ASSISTANT-GUIDE.md). Передавай ссылку на конкретный commit/branch или подключай репозиторий целиком, если платформа это поддерживает. Для точных ответов ИИ должен проверять исходники текущей ветки и тесты.

Не отправляй секреты, токены, пароли и приватные данные.

## Для разработчиков

- [Энциклопедия](KSL-Encyclopedia.md) — обзор архитектуры и DSL.
- [Public API](Public-API.md) — внешние контракты.
- [Standard Library architecture](Standard-Library-Architecture.md) — пакеты и модульная система.
- [Addon Development](Addon-Development-Guide.md) — жизненный цикл аддона.
- [Script Lifecycle and Resources](Script-Lifecycle-and-Resources.md) — владение ресурсами скриптов и безопасный hot-reload.
- [Troubleshooting](Troubleshooting.md) — диагностика.

Перед изменением API изучи реализации и тесты. Не делай внутренний класс публичным только ради удобства одного скрипта. Сохраняй hot-reload и корректное освобождение ресурсов.

## Ссылки

- [GitHub repository](https://github.com/wyou25f/kotlinscriptloader)
- [README](../README.md)
- [Примеры скриптов](../examples/)
- [IDE integration](../ide/README.md)
- [CHANGELOG](../CHANGELOG.md)
