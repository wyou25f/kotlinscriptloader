# Диагностика сборки KSL

В корне репозитория находится единая точка входа **build.ps1**. Она запускает Gradle через wrapper, сохраняет полный лог и формирует локальный HTML-отчёт.

## Требования

- JDK 21;
- PowerShell 7+ (pwsh);
- доступ к репозиториям Maven Central и PaperMC при первой сборке.

Плагин собирается для Paper 1.21.1 / Java 21. Сам Gradle устанавливается wrapper-скриптом автоматически.

## Быстрый старт

Из корня репозитория:

~~~powershell
./build.ps1 -Task Build
~~~

## Команды

| Команда | Что делает |
|---|---|
| ./build.ps1 -Task Build | Чистая полная сборка, тесты и проверка JAR |
| ./build.ps1 -Task Test | Чистый запуск тестов |
| ./build.ps1 -Task Package | Собирает JAR и проверяет его содержимое, не запускает тесты повторно |
| ./build.ps1 -Task Verify | Запускает проверки проекта и проверку JAR |
| ./build.ps1 -Task Diagnose | Проверяет Java, JAVA_HOME, Gradle wrapper, диск, Git и структуру проекта |
| ./build.ps1 -Task Ide | Проверяет companion-проект IntelliJ IDEA |
| ./build.ps1 -Task Clean | Удаляет Gradle build outputs |
| ./build.ps1 -Task Release | Выполняет полную сборку release-кандидата |

Дополнительные параметры:

- -NoDaemon — не оставлять Gradle daemon после запуска;
- -Offline — использовать только уже загруженные зависимости;
- -Detailed — включить подробный Gradle --info.

## Отдельные инструменты

Скрипты лежат в scripts/ и могут запускаться независимо:

| Скрипт | Назначение |
|---|---|
| scripts/Diagnose-Environment.ps1 | Диагностика локального окружения |
| scripts/Debug-Build.ps1 | Запуск Gradle-задачи с логом, stacktrace и отчётом |
| scripts/Test-Build.ps1 | Тесты плюс ссылки на HTML/JUnit-отчёты |
| scripts/Inspect-Jar.ps1 | Проверка plugin.yml, главного класса и обязательных классов в JAR |
| scripts/Build-Report.html | Шаблон HTML-отчёта |
| scripts/Build-Report.css | Оформление отчёта |

Примеры:

~~~powershell
./scripts/Diagnose-Environment.ps1 -Strict
./scripts/Debug-Build.ps1 -GradleTasks test -NoDaemon
./scripts/Test-Build.ps1 -Detailed -OpenReport
./scripts/Inspect-Jar.ps1
~~~

Для передачи отчёта приложи папку build-reports/ и полный лог из build-reports/logs/. Перед отправкой проверь логи на локальные пути, имена пользователей и другие приватные данные.

## Где искать результаты

- build-reports/index.html — визуальная сводка: статус, время, Gradle-задачи, ошибки, сводка тестов и окружение;
- build-reports/latest.json — структурированные данные отчёта;
- build-reports/logs/ — полный консольный вывод с датой и временем запуска;
- build/reports/tests/test/index.html — стандартный HTML-отчёт Gradle по тестам;
- build/test-results/test/ — JUnit XML для CI и анализаторов;
- build-reports/environment.json — результат проверки окружения;
- build-reports/jar-inspection.json — результат последней проверки JAR.

HTML-отчёт автономный: JSON встраивается в страницу при создании. CSS копируется рядом. Открой build-reports/index.html в современном браузере; локальный веб-сервер не нужен.

## Как читать ошибки

1. Сначала смотри красный статус и код выхода.
2. В секции «Этапы Gradle» найди первую задачу со статусом FAILED.
3. В секции «Ошибки» ищи первую содержательную строку e:, What went wrong, Caused by или AssertionError. Последующие ошибки часто являются следствием первой.
4. Если упали тесты, смотри build/reports/tests/test/index.html и JUnit XML. В stdout тестов KSL дополнительно выводит имена внутренних self-check проверок.
5. Если ошибка связана с зависимостями, приложи полный лог: в нём есть stacktrace и сообщение Gradle о координатах/репозитории.

Предупреждения deprecated сами по себе не означают, что сборка провалилась. Ищи первую реальную ошибку и итоговый exit code.

## CI

GitHub Actions запускает те же PowerShell-команды, что доступны локально. Диагностический artifact загружается с if: always(), поэтому отчёты тестов и логи сохраняются и при неуспешной сборке. JAR собирается отдельным этапом только после успешных тестов.
