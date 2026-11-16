# Библиотека документации KSL

Основная пользовательская документация KSL объединена в четыре большие книги. Каждая книга оформлена главами и рассчитана на чтение подряд или по отдельным разделам.

## Четыре книги

| № | Книга | Содержание |
|---|---|---|
| I | [Разработка скриптов](01-scripts.md) | Kotlin, .kts, события, команды, потоки, жизненный цикл, YAML, STD и Runtime |
| II | [Плагины, аддоны и библиотеки](02-addons.md) | Gradle, KSLAPI, сервисы, imports, classloader, hooks, modules, KSLAddonScope и Java |
| III | [Данные и базы данных](03-data-and-databases.md) | persist/table, SQL, SQLite, MySQL, MariaDB, phpMyAdmin, миграции, YAML, TOML и JSON |
| IV | [Практические рецепты](04-cookbook.md) | Примеры команд, игровых механик, инвентаря, БД, кэша и интеграций |

## Дополнительные справочники

- [NBT API](05-nbt-api.md) — пользовательские NBT-данные предметов, сущностей и блоков, а также ванильные компоненты предметов.
- [Предметы и зачарования](06-items-and-enchantments.md) — item-builder, короткие алиасы и KSL Custom Enchantments.
- [Звук, частицы и мобы](07-sound-particles-and-mobs.md) — звуковые последовательности, повтор, формы частиц, конструктор мобов и поведение сущностей.
- [STD, сообщения и миксины](08-standard-library-mixins-and-messages.md) — цветные сообщения, короткие методы, KSL Mixins, обновление движка и защитные зачарования.
- [API Reference и Developer Guide](09-api-reference.md) — публичные контракты, lifecycle аддонов, сервисы, hooks, kslshu и IDE workflow.
- [Надёжная разработка скриптов и аддонов](10-authoring-reliability.md) — validation, 50+ диагностик, watchdog-aware подходы и безопасный async reload.

## Офлайн-сайт

Можно читать книги не через GitHub, а в локальной читалке:

1. Скачайте папку [site](site/) целиком.
2. Сохраните все файлы внутри папки.
3. Откройте `index.html` на компьютере.

Читалка работает без интернета и сервера. В ней есть верхняя панель, выбор книг и глав, поиск по всему тексту, оглавление главы, кнопки копирования кода и печать.

Основные файлы сайта:
- `site/index.html` — страница читателя;
- `site/style.css` — бумажное оформление;
- `site/app.js` — навигация, поиск и Markdown renderer;
- `site/books-data.js` — тексты всех четырёх книг;
- `site/README.txt` — инструкция по запуску.

## Для контрибьюторов и AI coding agents

Служебные инструкции остаются отдельно: [AI Assistant Guide](AI-ASSISTANT-GUIDE.md) и [AGENTS.md](../AGENTS.md). Они не являются пятой пользовательской книгой.

## Проект

- [GitHub repository](https://github.com/wyou25f/kotlinscriptloader)
- [Корневой README](../README.md)
- [Примеры](../examples/)
- [IntelliJ IDEA integration](../ide/README.md)
- [CHANGELOG](../CHANGELOG.md)
