# Книга III. Данные, базы данных и конфигурация KSL

**Практическое руководство по SQLite, MySQL, MariaDB, PostgreSQL, phpMyAdmin, SQL, YAML, TOML и JSON**

Книга объясняет, где KSL хранит данные, когда нужна внешняя база, как безопасно создать пользователя в phpMyAdmin, настроить SSL, писать SQL-запросы и не потерять данные при миграции.

Все примеры адаптируйте под свой сервер. Не публикуйте пароли и не открывайте БД всему интернету.

## Содержание

1. Общая модель хранения
2. SQLite, MySQL/MariaDB или PostgreSQL
3. Установка MySQL/MariaDB
4. phpMyAdmin: база и пользователь
5. Настройка config.yml
6. Проверка соединения
7. persist и table
8. Пользовательские SQL-таблицы
9. SQL из YAML
10. Асинхронные запросы
11. Миграция SQLite → MySQL/MariaDB/PostgreSQL
12. Кэширование, очереди и несколько серверов
13. Резервное копирование и восстановление
14. YAML для скриптов и аддонов
15. TOML
16. JSON
17. Пути файлов и безопасность
18. Частые ошибки
19. Production checklist
20. PostgreSQL
21. Аккаунты аддонов (KSL Accounts)
22. PostemberDB — экспериментальная интеграция

---

## Глава 1. Общая модель хранения

KSL предоставляет несколько способов хранения. Не нужно писать SQL для каждой мелочи.

| Задача | Инструмент |
|---|---|
| Простые persistent значения скрипта | persist(...) |
| Табличные значения key/value | table(...) |
| Полноценные реляционные таблицы | SQL через execute/query |
| Настройки скрипта | YAML |
| Структурированные локальные файлы | TOML или JSON |
| Состояние между скриптами | MesLib2 |
| Общее бизнес-состояние между аддонами | Service из Public API |

### 1.1 Встроенные таблицы KSL

KSL использует внутренние таблицы для собственных механизмов хранения:

| Таблица | Назначение |
|---|---|
| ksl_persist | Значения persist |
| ksl_table | Значения table |
| ksl_meta | Метаданные схемы |
| ksl_scripts | Зарезервированная таблица |

Не переименовывайте эти таблицы и не меняйте их структуру вручную. Для собственных приложений создавайте отдельные таблицы со своим префиксом.

### 1.2 Где хранится SQLite

При database.type: sqlite KSL использует файл:

    plugins/KotlinScriptLoader/ksl.db

Это локальный файл базы данных. Для одиночного сервера он часто является самым простым решением.

---

## Глава 2. SQLite, MySQL/MariaDB или PostgreSQL

| Характеристика | SQLite | MySQL / MariaDB | PostgreSQL |
|---|---|---|---|
| Настройка | По умолчанию | Нужен database server | Нужен PostgreSQL server |
| Хранение | Файл ksl.db | Отдельный сервер БД | Отдельный сервер БД |
| Несколько Minecraft серверов | Не подходит как общее хранилище | Подходит | Подходит |
| Инструменты управления | SQLite viewer | phpMyAdmin и SQL-клиенты | pgAdmin и SQL-клиенты |
| Бэкап | Копия файла при остановленном сервере | SQL dump или экспорт | pg_dump или SQL export |
| Лучший сценарий | Один небольшой сервер | Несколько серверов, хостинг БД, централизованное управление | Несколько серверов, PostgreSQL-инфраструктура и централизованное управление |

### 2.1 Когда SQLite достаточно

Выбирайте SQLite, если сервер один, данных немного, не нужна общая БД между несколькими Minecraft-серверами и нет причины поддерживать отдельную СУБД.

### 2.2 Когда нужна внешняя база

Внешняя БД (MySQL, MariaDB или PostgreSQL) полезна, когда несколько серверов используют одно хранилище, нужна централизованная администрируемость или хостинг уже предоставил database credentials.

### 2.3 Не путайте phpMyAdmin и сервер БД

phpMyAdmin — веб-интерфейс управления. Это не СУБД и не часть соединения KSL.

    Minecraft Server
         |
         | JDBC connection
         v
    MySQL / MariaDB server
         ^
         |
    phpMyAdmin (admin web interface)

KSL подключается напрямую к MySQL/MariaDB через JDBC. Наличие phpMyAdmin не обязательно для работы KSL.

---

## Глава 3. Установка MySQL/MariaDB

В существующем руководстве проекта указаны MySQL 5.7+ или MariaDB 10.2+. Для production предпочтительнее актуальный поддерживаемый релиз выбранной СУБД с исправлениями безопасности.

### 3.1 Если база уже предоставлена хостингом

Хостинг обычно выдаёт:

- host;
- port;
- database name;
- username;
- password;
- иногда SSL certificate и требования к соединению.

В таком случае не устанавливайте второй MySQL сервер без необходимости. Сразу переходите к созданию/проверке базы и конфигурации KSL.

### 3.2 Linux Debian/Ubuntu

~~~bash
sudo apt update
sudo apt install mariadb-server
sudo mysql_secure_installation
~~~

После установки проверьте, что служба запущена, настройте административный доступ и создайте отдельную базу и пользователя KSL.

phpMyAdmin можно установить отдельно, если действительно нужен браузерный интерфейс. Для небольшого сервера использовать CLI-клиент часто проще и безопаснее.

### 3.3 Docker для локальной разработки

Пример для локальной машины, где MariaDB нужна только этому компьютеру:

~~~bash
docker run -d --name ksl-db \
  -p 127.0.0.1:3306:3306 \
  -e MARIADB_ROOT_PASSWORD=CHANGE_THIS_ROOT_PASSWORD \
  -e MARIADB_DATABASE=ksl \
  -e MARIADB_USER=ksl \
  -e MARIADB_PASSWORD=CHANGE_THIS_KSL_PASSWORD \
  mariadb:11
~~~

Порт намеренно привязан к 127.0.0.1:3306. Не публикуйте database port на все сетевые интерфейсы, если удалённое подключение не требуется.

Это пример для локальной разработки, а не универсальная production-схема. Для production используйте секреты среды/менеджера секретов, сетевые ограничения, TLS и резервные копии.

### 3.4 Windows

Для локальной разработки можно использовать установленный MySQL/MariaDB service или локальную среду, в которой есть СУБД и phpMyAdmin. Пункты меню и названия экранов зависят от конкретной версии панели.

Не держите тестовую локальную базу открытой в интернет. Локальная MariaDB на ПК разработчика не становится автоматически доступной удалённому Minecraft-хостингу.

---

## Глава 4. phpMyAdmin: создать базу и пользователя

Эта глава описывает обычный процесс. В разных версиях phpMyAdmin расположение вкладок может отличаться.

### 4.1 Войти в phpMyAdmin

Откройте адрес панели, который выдал хостинг или локальная установка. Войдите административной учётной записью базы.

Не публикуйте скриншоты, на которых видны пароль, session token или полный внешний адрес административной панели.

### 4.2 Создать базу

1. Откройте Databases.
2. Создайте базу с именем ksl.
3. Выберите кодировку utf8mb4 и подходящую collation, например utf8mb4_general_ci.
4. Подтвердите создание.

utf8mb4 поддерживает русский язык и emoji. Имя базы должно соответствовать допустимому формату KSL: буквы, цифры, подчёркивание, точка, дефис и знак $. Обычно используйте простое имя ksl.

### 4.3 Создать отдельного пользователя

Не подключайте KSL под root. Создайте пользователя ksl с отдельным сильным паролем.

В разделе User Accounts выберите Add user account:

- Username: ksl;
- Host: localhost, если Minecraft и база находятся на одной машине;
- если Minecraft сервер удалённый — ограничьте host IP адресом Minecraft-хоста или узким сетевым правилом;
- задайте уникальный пароль;
- не выдавайте global privileges;
- не выбирайте бездумно Grant all privileges on all databases.

Если панель хостинга уже создала базу и пользователя, используйте выданные данные и не создавайте второй аккаунт.

### 4.4 Выдать права только на базу KSL

KSL создаёт собственные таблицы и выполняет изменения схемы. Поэтому пользователю нужна возможность работать внутри базы ksl, включая создание и изменение таблиц KSL.

SQL-эквивалент для локального случая:

~~~sql
CREATE DATABASE ksl
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

CREATE USER 'ksl'@'localhost'
  IDENTIFIED BY 'REPLACE_WITH_A_LONG_UNIQUE_PASSWORD';

GRANT ALL PRIVILEGES ON ksl.*
  TO 'ksl'@'localhost';

FLUSH PRIVILEGES;
~~~

Это пример первоначальной настройки. Не запускайте повторный CREATE USER, если пользователь уже создан. Используйте существующую учётную запись и проверьте её права.

### 4.5 Если Minecraft на другом сервере

Вместо localhost в host-поле укажите разрешённый IP Minecraft-хоста, например:

~~~sql
CREATE USER 'ksl'@'203.0.113.20'
  IDENTIFIED BY 'REPLACE_WITH_A_LONG_UNIQUE_PASSWORD';

GRANT ALL PRIVILEGES ON ksl.*
  TO 'ksl'@'203.0.113.20';

FLUSH PRIVILEGES;
~~~

203.0.113.20 — документационный пример, не реальный адрес. Замените его на фактический IP сервера.

Не используйте 'ksl'@'%' без необходимости и сетевой защиты. Не открывайте 3306 для всех адресов. Ограничивайте firewall источником и защищайте удалённые соединения TLS.

### 4.6 Проверка прав

В phpMyAdmin откройте учётную запись и убедитесь, что права применяются только к базе ksl. KSL не должен использовать root credentials.

---

## Глава 5. Настройка config.yml

Файл:

    plugins/KotlinScriptLoader/config.yml

Пример внешней БД:

~~~yaml
database:
  type: mariadb
  host: localhost
  port: 3306
  name: ksl
  user: ksl
  password: ""
  ssl-mode: verify-full
  allow-insecure-transport: false
  pool-size: 4
  query-timeout-seconds: 10
  query-max-rows: 10000
  max-batch-rows: 1000
~~~

Для MySQL 8 указывайте:

~~~yaml
database:
  type: mysql
~~~

KSL использует MariaDB Connector/J и для MySQL, и для MariaDB, но для MySQL 8 формирует JDBC-параметры с учётом механизма аутентификации MySQL.

Для удалённой БД используйте `verify-full`: драйвер проверяет цепочку сертификата и имя сервера. Убедитесь, что хост указан в SAN сертификата и сервер предоставляет доверенную цепочку. `verify-ca` проверяет сертификат без проверки имени хоста. Режимы `disable` и `trust` считаются небезопасными и требуют явного `database.allow-insecure-transport: true`; используйте их только в изолированной тестовой сети. Старые конфиги с удалённой БД и `ssl-mode: disable` после обновления потребуют осознанно включить этот флаг либо настроить TLS.


### 5.1 Параметры

| Ключ | Значение |
|---|---|
| type | sqlite, mysql или mariadb |
| host | Имя хоста или IP базы |
| port | Обычно 3306; диапазон 1–65535 |
| name | Имя базы |
| user | Пользователь БД; для remote DB не может быть пустым |
| password | Пароль пользователя |
| ssl-mode | disable, trust, verify-ca или verify-full; удалённая БД по умолчанию требует verify-full |
| allow-insecure-transport | Явное подтверждение для disable/trust; по умолчанию false |
| pool-size | Размер пула, KSL ограничивает его 1–32 |
| query-timeout-seconds | Таймаут JDBC-запроса, 1–120 секунд |
| query-max-rows | Максимум строк результата, 1–100000 |
| max-batch-rows | Максимум строк одной batch-операции, 1–5000 |

### 5.2 Пароль через переменную окружения

Пароль в config.yml хранится открытым текстом. Не отправляйте этот файл в публичный Git и не показывайте пароль на скриншотах.

Можно оставить password пустым и задать переменную окружения процесса:

~~~bash
KSL_DB_PASSWORD=YOUR_SECRET_VALUE
~~~

В production задавайте переменную через service manager, Docker environment или менеджер секретов. Не записывайте пароль в общедоступный startup script.

### 5.3 SSL mode

| Режим | Значение |
|---|---|
| disable | Без TLS |
| trust | TLS без полноценной проверки сертификата |
| verify-ca | Проверить цепочку CA |
| verify-full | Проверить цепочку и соответствие имени хоста |

Для локальной базы, не доступной извне, обычно используют disable. Для удалённой базы предпочтительнее verify-ca или verify-full с правильно настроенными сертификатами.

trust не равен полноценной проверке личности сервера. Не используйте его как эквивалент verify-full.

### 5.4 Перезапуск

Тип базы нельзя безопасно переключить на лету. После изменения database.type остановите и запустите сервер. Не меняйте dialect при работающих операциях записи.

---

## Глава 6. Проверка соединения и диагностика

### 6.1 Проверка после запуска

1. Найдите в консоли сообщение об инициализации БД.
2. Убедитесь, что KSL применил schema migrations.
3. Запустите /ksl doctor.
4. Откройте базу ksl в phpMyAdmin.
5. Проверьте наличие таблиц KSL.
6. Проверьте тестовую запись из скрипта.

### 6.2 Тест persist/table

~~~kotlin
var coins by persist("coins", persistent = true) { 0L }

val stats = table("player_stats")
stats.set("player-uuid", "kills", 5)
~~~

Сверяйте overload table.get/set с версией KSL, которую используете. Не смешивайте пользовательские таблицы с внутренними ksl_ таблицами.

### 6.3 Типовые ошибки

| Ошибка | Причина | Проверка |
|---|---|---|
| Access denied | Пароль или host пользователя не совпадает | Проверьте user, password и host |
| Unknown database | База отсутствует или ошибочно названа | Создайте базу и проверьте имя |
| Connection refused | Сервис выключен, порт/host неверный | Проверьте службу и bind-address |
| Timeout | Firewall или сеть | Разрешите доступ только с Minecraft host |
| RSA public key unavailable | MySQL 8 задан как MariaDB | Установите type: mysql |
| Key too long | Слишком старая СУБД/длина ключа | Обновите СУБД и сократите ключ |
| CREATE denied | Недостаточно прав | Дайте права на ksl.* |
| Too many connections | Пул выше лимита БД | Проверьте pool-size и лимиты |
| SSL / PKIX error | Сертификат или SSL mode неверный | Проверьте CA и имя хоста |

KSL не должен незаметно переключать данные обратно в SQLite при недоступности MySQL. Автоматический fallback мог бы разделить данные на две базы. Исправьте конфигурацию и перезапустите сервер.

---

## Глава 7. persist и table

### 7.1 persist

persist подходит для отдельных persistent значений скрипта:

~~~kotlin
var coins by persist("coins", persistent = true) { 0L }

coins += 10L
log("Баланс: " + coins)
~~~

Default используется, когда значение ещё не существует. После загрузки значение может кэшироваться в памяти текущего экземпляра скрипта.

### 7.2 table

table подходит для набора значений, организованных в логическую таблицу:

~~~kotlin
val stats = table("player_stats")

stats.set(player.uniqueId.toString(), "kills", 10)
val kills = stats.get(player.uniqueId.toString(), "kills")
~~~

Сверяйте overload конкретного get/set с текущим API. Не смешивайте пользовательские имена таблиц с внутренними ksl_ таблицами.

### 7.3 Типовые префиксы

Внутренние значения KSL могут храниться с типовым префиксом:

| Префикс | Тип |
|---|---|
| i: | Int |
| l: | Long |
| d: | Decimal |
| b: | Boolean |
| s: | String |
| n: | Null |

Не редактируйте строки внутренних таблиц вручную, если не понимаете формат и последствия для cache.

---

## Глава 8. Пользовательские SQL-таблицы

Если нужно хранить сложные реляционные данные, создавайте собственные таблицы. Имена таблиц лучше начинать с имени подсистемы: shop_orders, guild_members, economy_ledger.

### 8.1 Создание таблицы

~~~kotlin
execute("""
    CREATE TABLE IF NOT EXISTS player_notes (
        uuid VARCHAR(36) NOT NULL,
        note VARCHAR(255) NOT NULL,
        created_at BIGINT NOT NULL,
        PRIMARY KEY (uuid, created_at)
    )
""".trimIndent())
~~~

### 8.2 Параметризованный запрос

~~~kotlin
execute(
    "INSERT INTO player_notes (uuid, note, created_at) VALUES (?, ?, ?)",
    player.uniqueId.toString(),
    note,
    System.currentTimeMillis()
)
~~~

Знак ? — placeholder значения для prepared statement. Не вставляйте пользовательские значения через конкатенацию SQL.

Плохо:

~~~kotlin
execute("SELECT * FROM users WHERE name = '" + input + "'")
~~~

Правильно:

~~~kotlin
query("SELECT * FROM users WHERE name = ?", input) {
    // Чтение результата.
}
~~~

Параметры защищают значения. Для динамического имени таблицы/колонки placeholders обычно не работают — идентификатор нужно проверять по allowlist и безопасному шаблону.

### 8.3 Переносимый SQL

KSL поддерживает SQLite, MySQL/MariaDB и PostgreSQL. Для переносимых запросов:

- не используйте SQLite-only PRAGMA;
- избегайте AUTOINCREMENT, если нужен общий SQL для разных dialect;
- не используйте INSERT OR REPLACE;
- используйте типы и синтаксис, поддерживаемые всеми целевыми backend-ами;
- проверяйте зарезервированные слова;
- применяйте собственные префиксы таблиц.

---

## Глава 9. SQL из YAML

Для каждого скрипта может использоваться YAML-файл с тем же базовым именем. Он лежит в корне папки данных KSL рядом с scripts/.

    plugins/KotlinScriptLoader/
    ├── money.yml
    └── scripts/
        └── money.kts

### 9.1 Файл money.yml

~~~yaml
database:
  queries:
    create-coins: |
      CREATE TABLE IF NOT EXISTS coins (
        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
        balance BIGINT NOT NULL DEFAULT 0
      )
    read-coins: "SELECT balance FROM coins WHERE uuid = ?"
    add-coins: "UPDATE coins SET balance = balance + ? WHERE uuid = ?"
~~~

### 9.2 Вызов из скрипта

~~~kotlin
executeYaml("database.queries.create-coins")

queryYaml("database.queries.read-coins", player.uniqueId.toString()) {
    if (next()) {
        val balance = getLong("balance")
        log("Баланс: " + balance)
    }
}
~~~

После изменения YAML перезагрузите скрипт. Если запрос не найден, проверьте имя companion-файла, YAML indentation и полный путь ключа.

### 9.3 Почему это удобно

SQL отделён от логики. Его легче проверять и менять без длинных Kotlin строк. Но SQL всё равно должен быть параметризованным, переносимым и покрытым тестом/миграцией.

---

## Глава 10. Асинхронные запросы и ResultSet

Методы KSL database API выполняют SQL через фоновые пулы. Не переносите работу с live Bukkit объектами внутрь SQL callback.

### 10.1 Query callback

~~~kotlin
query(
    "SELECT balance FROM coins WHERE uuid = ?",
    player.uniqueId.toString()
) {
    if (next()) {
        val value = getLong("balance")
        runSync {
            player.sendMessage("Баланс: " + value)
        }
    }
}
~~~

### 10.2 CompletableFuture

~~~kotlin
queryAsync(
    "SELECT COUNT(*) AS total FROM player_notes WHERE uuid = ?",
    player.uniqueId.toString(),
    mapper = { rs ->
        if (rs.next()) rs.getInt("total") else 0
    }
).whenComplete { total, error ->
    if (error != null) {
        logWarn("Ошибка SQL: " + error.message)
    } else {
        runSync {
            player.sendMessage("Записей: " + total)
        }
    }
}
~~~

Mapper должен вернуть обычное Kotlin-значение. JDBC ResultSet и соединение закрываются до завершения Future. Не возвращайте ResultSet наружу.

### 10.3 Обработка ошибок

Используйте whenComplete/exceptionally для обработки отказов. Не вызывайте join() на главном серверном потоке. Очереди ограничены; если очередь заполнена, KSL может отклонить новую операцию, вместо того чтобы бесконечно расти в памяти.

### 10.4 Ограничения и транзакции

KSL применяет к скриптовым SQL-запросам лимит длины текста SQL, таймаут JDBC и максимальное число возвращаемых строк. Значения задаются в `database.query-timeout-seconds`, `database.query-max-rows` и `database.max-batch-rows`. Пределы сверху ограничены самим KSL, поэтому чрезмерные значения в конфиге будут приведены к допустимому диапазону. Длина SQL-текста ограничена 65 536 символами.

`transactionAsync { ... }` выполняет блок в упорядоченной очереди записи. Внутри доступны `execute`, `queryOne`, `queryList` и ограниченный `batch`. Блок коммитится при нормальном завершении; исключение приводит к rollback. Результаты запросов нужно копировать в обычные Kotlin-значения, не сохранять JDBC-объекты и не использовать сессию после завершения транзакции.

~~~kotlin
transactionAsync {
    execute("UPDATE accounts SET balance = balance - ? WHERE uuid = ?", 10, fromId)
    execute("UPDATE accounts SET balance = balance + ? WHERE uuid = ?", 10, toId)
    queryOne("SELECT balance FROM accounts WHERE uuid = ?", toId) {
        getLong("balance")
    }
}
~~~

Sandboxed scripts по умолчанию не могут вызывать SQL API или `table()`. Для конкретного доверенного скрипта можно выдать точную capability через `security.sandbox-database-allowlist`. Это разрешает только ограниченный KSL SQL API; raw `database` / JDBC pool остаётся доступен только trusted-скриптам. Sandbox работает внутри JVM и не является защитной границей для враждебного кода.


### 10.5 Прямое подключение

Если используете низкоуровневый database.connection, закрывайте connection/statement/result set через use. Не удерживайте JDBC connection в глобальном объекте или Player session.

---

## Глава 11. Миграция SQLite → MySQL/MariaDB/PostgreSQL

KSL содержит команду миграции данных persist/table из старого файла SQLite в настроенную удалённую базу.

### 11.1 Подготовка

1. Сделайте резервную копию ksl.db.
2. Создайте базу и отдельного пользователя в MySQL, MariaDB или PostgreSQL.
3. Настройте database в config.yml.
4. Остановите сервер и измените database.type.
5. Запустите сервер и проверьте подключение.
6. Выполните preview.

### 11.2 Команды

~~~text
/ksl dbmigrate
/ksl dbmigrate confirm
~~~

Первая команда — preview. Вторая подтверждает перенос.

### 11.3 Что мигрируется

Команда переносит ksl_persist и ksl_table из файла ksl.db в удалённую базу. Исходный файл не изменяется и не удаляется.

Записи отправляются пакетами по 500 строк, но обе служебные таблицы импортируются внутри одной транзакции destination. Перенос коммитится только после успешной обработки обеих таблиц; если чтение, проверка ключа или запись завершается ошибкой, все изменения текущего запуска откатываются. Повторный запуск обновляет существующие ключи destination данными из SQLite, поэтому это не способ объединить две независимые истории без перезаписи. Для очень больших баз учитывайте, что одна транзакция будет дольше удерживать блокировки и журнальные ресурсы СУБД.

### 11.4 Ограничение длины ключей

Для удалённых SQL-backend-ов имена таблиц/скриптов/ключи KSL ограничены 190 символами в используемой схеме. Если слишком длинная запись найдена, миграция остановится с диагностикой. Сократите ключ в исходной SQLite базе и повторите preview.

Не запускайте confirm до проверки preview и резервной копии.

---

## Глава 12. Кэширование, очереди и несколько серверов

### 12.1 table cache

table() может хранить значения в памяти. По умолчанию кэш включён для SQLite и отключён для удалённых backend-ов (MySQL, MariaDB и PostgreSQL), поскольку БД могут менять другие серверы или администратор.

Настройка cache позволяет выбрать поведение явно. После ручного изменения table данных в phpMyAdmin перечитайте скрипты, чтобы обновить кэш.

### 12.2 Очереди

KSL использует ограниченные очереди фоновой записи и чтения. Записи проходят через последовательную очередь, чтобы сохранять порядок. Повторные записи одного ключа могут объединяться до фактической записи.

KSL дополнительно отслеживает тенденцию очередей: раннее предупреждение появляется, когда очередь уже заметно выросла и продолжает быстро расти; отдельное предупреждение остаётся для высокого порога. Чтобы не засорять консоль, повторное сообщение подавляется до тех пор, пока обе очереди не восстановятся. Монитор измеряет только размеры очередей и не выполняет SQL-запросы.

Это сигнал о возможной перегрузке, а не прогноз времени отказа: проверьте частоту вызовов, latency запросов, индексы, connection pool и лимиты сервера. Не увеличивайте очередь автоматически — это может лишь дольше удерживать работу в памяти и маскировать медленную БД.

### 12.3 Несколько Minecraft серверов

MySQL, MariaDB и PostgreSQL позволяют нескольким серверам видеть одну БД. Но это не гарантирует, что любая операция KSL автоматически становится распределённой транзакцией.

Например, конкурентный read-modify-write через table.increment может потерять обновления, если несколько серверов меняют одно значение. Для общего счётчика используйте атомарный SQL UPDATE:

~~~sql
UPDATE economy_counters
SET value = value + 1
WHERE counter_key = ?;
~~~

Для критичных данных проектируйте транзакции и уникальные ключи на стороне базы.

---

## Глава 13. Резервное копирование и восстановление

### 13.1 SQLite

Останавливайте сервер перед копированием ksl.db, чтобы не получить неполную или несогласованную копию. Сохраняйте дату и версию KSL рядом с backup.

### 13.2 Экспорт через phpMyAdmin

1. Откройте базу ksl.
2. Выберите Export.
3. Используйте формат SQL.
4. Сохраните файл в защищённое место.
5. Проверьте, что экспорт завершился без ошибок.

### 13.3 Экспорт через CLI

~~~bash
mysqldump -u ksl -p ksl > ksl-backup.sql
~~~

Восстановление:

~~~bash
mysql -u ksl -p ksl < ksl-backup.sql
~~~

Не вставляйте пароль в командную строку: -p без значения запросит его интерактивно.

### 13.4 До обновлений

Делайте backup перед обновлением KSL, изменением схемы, переключением dialect и /ksl dbmigrate confirm. Храните копию вне папки сервера.

### 13.5 Проверка восстановления

Наличие backup файла не доказывает, что его можно восстановить. Периодически проверяйте импорт на тестовой базе.

---

## Глава 14. YAML для скриптов и аддонов

YAML подходит для конфигурации, потому что поддерживает вложенные поля, списки, типы и комментарии.

### 14.1 Companion YAML

Для shop.kts файл shop.yml создаётся в data folder KSL рядом с scripts/.

~~~yaml
shop:
  enabled: true
  max-items: 24
  title: "<gold>Магазин</gold>"
  worlds:
    - world
    - world_nether
~~~

~~~kotlin
val enabled = config.getBoolean("shop.enabled", true)
val maxItems = config.getInt("shop.max-items", 24)
val title = config.getString("shop.title") ?: "Магазин"
val worlds = config.getStringList("shop.worlds")
~~~

### 14.2 Групповое редактирование

~~~kotlin
editConfig {
    set("shop.enabled", true)
    set("shop.max-items", 32)
}
~~~

Для произвольных YAML файлов используйте поддерживаемые методы editYaml/saveYaml в пределах разрешённых путей. Не передавайте путь с ../.

### 14.3 Schema validation

~~~kotlin
val issues = validateConfig {
    string("messages.prefix", default = "[Server]")
    int("shop.max-items", default = 24, min = 1, max = 54)
    bool("shop.enabled", default = true)
    strings("worlds.allowed", default = listOf("world"))
}

issues.forEach { issue ->
    logWarn("Config " + issue.path + ": " + issue.message)
}
~~~

Валидация помогает выявить отсутствующие и неверные значения до того, как они станут причиной ошибки в обработчике события.

### 14.4 Миграции YAML

~~~kotlin
migrateConfig(
    targetVersion = 2,
    migrations = mapOf(
        1 to {
            if (!contains("shop.max-items")) set("shop.max-items", 24)
        },
        2 to {
            if (!contains("shop.enabled")) set("shop.enabled", true)
        }
    )
)
~~~

Миграции идут последовательно. Не изменяйте старые шаги после выпуска конфигурации пользователям. Добавляйте следующий шаг.

---

## Глава 15. TOML

TOML подходит для конфигурации, когда нужны явные типы, вложенные таблицы и удобочитаемый синтаксис.

~~~toml
port = 25565
enabled = true
motd = "Welcome"

[database]
host = "localhost"
pool-size = 4

[[shops]]
id = "tools"

[shops.location]
world = "world"
x = 10

[[shops]]
id = "food"
~~~

### Чтение

~~~kotlin
val settings = loadToml("data/settings.toml")
val port = settings?.tomlInt("port", 25565) ?: 25565
val db = settings?.tomlTable("database")
val host = db?.tomlString("host", "localhost") ?: "localhost"
val shops = settings?.tomlList("shops").orEmpty()
~~~

### Запись

~~~kotlin
editToml("data/settings.toml") {
    this["port"] = 25566
    this["motd"] = "New server"
    this["database"] = mapOf(
        "host" to "localhost",
        "pool-size" to 6
    )
    this["shops"] = listOf(
        mapOf("id" to "tools", "enabled" to true),
        mapOf("id" to "food", "enabled" to false)
    )
}
~~~

Также доступны loadToml/saveToml/editToml и aliases readToml/writeToml/updateToml.

### Поддержка и ограничения

- Поддерживаются scalar values, обычные arrays, nested tables, inline tables и arrays of tables (например, [[name]]).
- Поддерживаются строки, Boolean, целые числа в диапазоне signed 64-bit, конечные Float/Double, BigDecimal, BigInteger в допустимом диапазоне и стандартные TOML date/time значения (LocalDate, LocalTime, LocalDateTime, OffsetDateTime).
- Null не существует в TOML. Кодек отклоняет null и неподдерживаемые типы, а не теряет данные молча.
- Ключи Map должны быть строками. Ключи с пробелами, точками или специальными символами автоматически заключаются в кавычки.
- BOM в начале UTF-8 файла принимается.

Парсер возвращает map/list представление. Комментарии, исходные отступы и точное форматирование не сохраняются; после editToml комментарии могут исчезнуть. Вложенные Map в таблицах записываются как tables, Map внутри массивов — как inline tables, а массивы Map — как arrays of tables.

---

## Глава 16. JSON

JSON подходит для обмена данными, API payloads и файлов, где важна совместимость с другими системами. Запись остаётся стандартным JSON, а чтение конфигурационных файлов дополнительно принимает распространённые JSONC-расширения.

### Создание и преобразование

~~~kotlin
val profile = jsonObjectOf(
    "uuid" to player.uniqueId.toString(),
    "name" to player.name,
    "online" to true,
    "coins" to 250L,
    "tags" to listOf("builder", "vip")
)

val tree = profile.toJsonString(pretty = true)
val values = parseJson(tree).toKotlinValue()
~~~

toKotlinValue преобразует JSON tree в обычные Kotlin Map/List и примитивы. Целые числа сохраняют точность как Long или BigInteger, дробные и экспоненциальные значения — как BigDecimal. Это удобно для обмена JSON и TOML; при переносе нужно помнить, что TOML не поддерживает null.

### Чтение

~~~kotlin
val data = loadJsonObject("data/profile.json")
val coins = data?.long("coins", 0L) ?: 0L
val name = data?.string("name", "Unknown") ?: "Unknown"

val compatible = parseJsonCompatible(
    """
    {
      // comments and trailing commas are accepted on input
      "enabled": true,
    }
    """.trimIndent()
)
~~~

parseJson проверяет строгий JSON. parseJsonCompatible и файловый loadJson также принимают // / block comments и trailing commas. parseJsonCompatibleOrNull возвращает null при ошибке. Неquoted keys и произвольный JSON5 не поддерживаются. При сохранении комментарии и завершающие запятые не выводятся.

### Типы, лимиты и пути

В STD доступны string/int/long/double/bool, objectValue, arrayValue, hasKey, requireString/requireInt/requireLong/requireDouble/requireBoolean, accessor методы для JsonArray, atPath() и RFC 6901 atJsonPointer().

toJsonElement поддерживает Kotlin primitives, enum, maps, collections, sequences, массивы, BigInteger/BigDecimal, UUID и java.time values. Ключи Map обязаны быть String. NaN и Infinity не являются допустимыми JSON числами. Циклические object graphs отклоняются.

Для обычного toJsonElement применяются защитные лимиты: глубина 64, 100 000 узлов, 25 000 элементов коллекции и 1 MiB на строку/ключ. Для специальных сценариев можно передать KSLJsonLimits с собственными ограничениями.

Ошибочный файл при чтении не перезаписывается: KSL сохраняет резервную копию с суффиксом .broken-* и сообщает причину. JSON/TOML сохраняются через уникальный временный файл и атомарную замену; одновременные записи в один путь сериализуются локальным lock.

---

## Глава 17. Пути файлов и безопасность

### Где лежат разные файлы

| Файл | Где хранится |
|---|---|
| config.yml KSL | plugins/KotlinScriptLoader/config.yml |
| shop.yml для shop.kts | plugins/KotlinScriptLoader/shop.yml |
| JSON/TOML script data | Внутри plugins/KotlinScriptLoader/scripts/ |
| SQLite database | plugins/KotlinScriptLoader/ksl.db |
| Addon config | Data folder конкретного addon |

Не смешивайте companion YAML с JSON/TOML script data: у этих механизмов разная политика путей.

### Секреты

- Не коммитьте пароли.
- Не публикуйте config.yml со значением database.password.
- Используйте KSL_DB_PASSWORD через окружение, где это возможно.
- Не помещайте Discord webhook URL в публичные примеры.
- Не храните пароли пользователей в обычном виде.
- Не используйте Base64 как шифрование.
- Не используйте SHA-256 как password hashing.

### База данных не должна быть открыта всему интернету

- Разрешайте подключение только с IP Minecraft host.
- Не открывайте 3306 для всех.
- Используйте отдельного DB user.
- Включайте TLS для удалённого соединения.
- Ограничьте доступ к phpMyAdmin.
- Не используйте root account в KSL.
- Не публикуйте административную web-панель без ограничения доступа.

---

## Глава 18. Частые ошибки

**Access denied.** Проверьте database user, password, host binding и права только на нужную базу.

**Unknown database.** База не создана или имя в config.yml не совпадает.

**Connection refused.** СУБД остановлена, неправильный host/port или сервер слушает только localhost.

**Connection timeout.** Firewall или сетевое правило не пропускает запрос от Minecraft host.

**SSL error.** Сертификат не соответствует имени хоста, отсутствует CA или неправильно выбран SSL mode.

**Данные в phpMyAdmin не совпадают с игрой.** Проверьте cache setting и перечитайте скрипты. persist может быть уже загружен в память текущего экземпляра скрипта.

**Миграция не проходит.** Запустите preview, найдите длинные ключи и проверьте destination schema.

**SQL блокирует тик.** Проверьте, не выполняется ли блокирующий вызов на main thread; используйте async query APIs.

---

## Глава 19. Production checklist

### База данных

- [ ] Выбрана SQLite или внешняя СУБД осознанно.
- [ ] Для удалённой БД создан отдельный user.
- [ ] Пользователь имеет права только на нужную базу.
- [ ] 3306 не открыт всему интернету.
- [ ] SSL настроен для удалённого подключения.
- [ ] Пароль не хранится в публичном файле.
- [ ] pool-size соответствует лимиту СУБД.
- [ ] Backup создан и проверено восстановление.
- [ ] Миграция прошла preview перед confirm.

### Файлы

- [ ] Companion YAML лежит в правильной папке.
- [ ] JSON/TOML пути не выходят за scripts/.
- [ ] Критичные настройки валидируются.
- [ ] Миграции конфигурации добавляются последовательно.
- [ ] Секреты не попадают в логи.

### SQL

- [ ] Пользовательский ввод передаётся параметрами.
- [ ] Индексы и ключи соответствуют запросам.
- [ ] Таблицы имеют собственные имена.
- [ ] Не используется SQL, специфичный для SQLite, при работе с MySQL/MariaDB/PostgreSQL.
- [ ] Async callbacks возвращают обычные значения, не ResultSet.
- [ ] JDBC ресурсы закрываются.

**Конец книги III.** Следующая книга — коллекция практических рецептов.


## Глава 20. PostgreSQL (KSL 1.2.2)

PostgreSQL — полноценный удалённый backend наряду с MySQL и MariaDB. KSL использует PostgreSQL JDBC driver, отдельный SQL dialect, PostgreSQL-native `ON CONFLICT ... DO UPDATE`, двойные кавычки для идентификаторов и те же внутренние таблицы `ksl_*`.

Пример `config.yml`:

```yaml
database:
  type: postgresql
  host: 127.0.0.1
  port: 5432
  name: ksl
  user: ksl_app
  password: ""
  ssl-mode: verify-full
  allow-insecure-transport: false
  pool-size: 4
```

Для пароля рекомендуется переменная окружения `KSL_DB_PASSWORD`. По умолчанию PostgreSQL JDBC driver проверяет сертификат и имя сервера через `verify-full`. Режимы `disable`, `allow`, `prefer` и `require` не проверяют серверную идентичность и требуют явного `allow-insecure-transport: true`.

Если `database.port: 0`, KSL выбирает стандартный порт backend: 3306 для MySQL/MariaDB и 5432 для PostgreSQL. Если в старом конфиге уже записан `3306`, при переходе на PostgreSQL укажи `5432` или `0`. Схема `ksl_*` и `ksl_meta` создаются автоматически; SQLite → PostgreSQL перенос сохраняет исходный `ksl.db`.


## Безопасность паролей и криптографические функции

KSL предоставляет KSLSecurity и функции стандартной библиотеки для контрольных сумм, HMAC, токенов и проверки паролей.

### Контрольные суммы

~~~kotlin
val sha256 = KSLSecurity.sha256("payload")
val sha512 = KSLSecurity.sha512("payload")
val fileHash = KSLSecurity.sha256File(file)
val fileHash512 = KSLSecurity.sha512File(file)

check(KSLSecurity.verifySha256("payload", sha256))
check(KSLSecurity.verifySha512("payload", sha512))
~~~

SHA-256 и SHA-512 подходят для контрольных сумм и идентификации содержимого, но не для хранения паролей.

### Пароли

~~~kotlin
val encodedHash = KSLSecurity.hashPassword(password)
val valid = KSLSecurity.verifyPassword(candidate, encodedHash)

if (valid && KSLSecurity.needsPasswordRehash(encodedHash)) {
    val upgradedHash = KSLSecurity.hashPassword(candidate)
    // Сохрани upgradedHash вместо старого значения в своей таблице.
}
~~~

Текущая реализация использует PBKDF2-HMAC-SHA256 с уникальной случайной солью и ограниченным диапазоном work factor. Формат содержит алгоритм, число итераций, соль и результат. Исходный пароль восстановить из него нельзя. Хеширование и проверку выполняйте асинхронно, не на основном потоке Paper.

Храните только encoded hash. Не записывайте пароль или токены в логи. При успешной проверке старого хеша можно обновить его до текущего work factor.

### HMAC и токены

~~~kotlin
val signature = KSLSecurity.hmacSha256(payload, secret)
val authentic = KSLSecurity.verifyHmacSha256(payload, secret, signature)
val token = KSLSecurity.randomToken(32)
~~~

HMAC предназначен для проверки подлинности сообщения при наличии общего секрета. Сам секрет должен храниться отдельно и не попадать в исходники или логи.

Base64 — только кодирование, а XOR — обратимая побитовая операция. Ни Base64, ни XOR не обеспечивают конфиденциальность или защиту паролей. Для паролей используйте только password hashing API.


---

## Глава 22. PostemberDB — экспериментальная интеграция

[PostemberDB](https://github.com/forgejourney-labs/postemberdb) — отдельный SQL-подобный движок на Rust. KSL подключается к уже запущенному PostemberDB Server через его текущий TCP-протокол. Исходный репозиторий PostemberDB не изменяется, а Rust-код не копируется в KSL.

**Важный статус:** upstream сейчас находится на ранней стадии. Его README указывает, что данные хранятся только в памяти и теряются после остановки процесса; поддерживается небольшой набор SQL-команд, а серверный протокол не предназначен для недоверенных сетей. Поэтому эта интеграция экспериментальная: она не заменяет SQLite, MySQL/MariaDB или PostgreSQL и не подходит для production, аккаунтов, платежей или единственного хранения важных данных.

### Запуск

Соберите и запустите сервер по инструкции самого PostemberDB. Для KSL используйте только loopback bind, например:

~~~bash
cargo run -- server 127.0.0.1:7432
~~~

Не публикуйте порт 7432 в интернет. В текущем протоколе PostemberDB нет аутентификации и TLS. KSL-клиент дополнительно запрещает подключение к адресам, не являющимся loopback.

### Использование из аддона

~~~kotlin
import ru.privateserver.ksl.api.*

class ExampleAddon : KSLAddon {
    override val addonId = "example-postember"
    override val addonVersion = "1.0.0"

    private var postember: KSLPostemberClient? = null

    override fun onLoad(api: KSLAPI) {
        postember = api.createPostemberClient()
        // Выполняйте запросы к PostemberDB из фонового потока, не из Bukkit main thread.
    }

    fun loadUsersOffThread(api: KSLAPI) {
        val db = postember ?: return
        Thread {
            val users = db.query("SELECT * FROM users")
            users.rows.forEach { row ->
                api.kslPlugin.logger.info("Postember user: id=${row[0]}, name=${row[1]}")
            }
        }.start()
    }

    override fun onUnload() {
        postember?.close()
        postember = null
    }
}
~~~

Клиент открывает отдельное TCP-соединение для каждого запроса; экземпляр можно безопасно использовать из нескольких потоков. Выполняйте сетевые запросы не на основном потоке Bukkit. `close()` запрещает дальнейшее использование клиента.

### Ограничения протокола

- Поддерживается только текущий протокол PostemberDB: одна SQL-команда на строку.
- Для выборки сервер сейчас возвращает только `SELECT *`; результат представлен именами колонок и строковыми значениями.
- Параметризованные запросы/JDBC, транзакции, connection pool, миграции, индексы, durable storage, auth и TLS этой интеграцией не добавляются.
- Значения с табуляцией или переводами строки не имеют полноценного escaping в upstream-протоколе.
- KSL ограничивает SQL-команду 16 KiB, ответ 1 MiB по умолчанию и число строк 10 000; таймауты соединения и чтения ограничены.
- Ошибки SQL сервера выбрасываются как `KSLPostemberException`; сетевые ошибки остаются `IOException`.

Для долговременных данных продолжайте использовать поддерживаемые KSL JDBC-бэкенды. Когда PostemberDB получит стабильный storage format, транзакции, защищённый протокол и библиотечный API, интеграцию можно будет расширить, не меняя сам upstream-проект.

---

## Глава 21. Аккаунты аддонов (KSL Accounts)

KSL предоставляет необязательный JDBC-модуль `KSLAccountStore`. Он подходит для собственных систем регистрации и входа аддонов: профили, проверка пароля и серверные сессии. Модуль не добавляет команды или готовые GUI — интерфейс регистрации остаётся ответственностью аддона.

### 21.1 Подключение

Класс доступен напрямую из `ru.privateserver.ksl.accounts` и через `ru.privateserver.ksl.api.*`. Удобная фабрика `KSLAPI.createAccountStore(owner, dataSource)` автоматически назначает стабильный SQL-префикс на основе имени Bukkit-плагина. Для собственного префикса создайте `KSLAccountStore(dataSource, tablePrefix = "my_addon")` напрямую. Владелец DataSource отвечает за пул соединений и его закрытие.

```kotlin
import ru.privateserver.ksl.api.*
import com.zaxxer.hikari.HikariDataSource

val accountStore = api.createAccountStore(this, dataSource)
accountStore.initialize()
```

`initialize()` безопасно создавать таблицы при запуске. Хранилище использует таблицы с префиксом. Конструктор по умолчанию сохраняет прежние имена `ksl_accounts` и `ksl_account_sessions`; для разных аддонов задавайте отдельный префикс. Не меняйте структуру таблиц вручную.

### 21.2 Регистрация и вход

```kotlin
val account = accountStore.register("player_name", password.toCharArray())

val session = accountStore.authenticate(
    "player_name",
    password.toCharArray()
)

if (session != null) {
    // Показывайте token пользователю/клиенту только по защищённому каналу.
    // Не записывайте token в логи.
}
```

Имена приводятся к нижнему регистру и ограничены ASCII-буквами, цифрами, точкой, дефисом и подчёркиванием (3–32 символа). Пароль должен содержать 10–1024 символа. Повторная регистрация нормализованного имени выдаёт `KSLAccountAlreadyExistsException`. Неверные учётные данные возвращают `null`.

### 21.3 Пароли и сессии

- Пароли сохраняются как salted PBKDF2-HMAC-SHA256; исходный пароль не хранится.
- При успешном входе старый поддерживаемый хеш автоматически повышается до настроенного количества итераций.
- Случайный bearer token выдаётся один раз. В БД сохраняется только SHA-256 digest токена.
- `findSession(token)` возвращает профиль только для действующей, неистёкшей сессии включённого аккаунта.
- `revokeSession(token)` отзывает одну сессию; `revokeAllSessions(accountId)` отзывает все сессии аккаунта.
- По умолчанию сессия действует 7 дней. Допустимый срок — от 1 минуты до 30 дней.
- Неизвестный username проходит dummy PBKDF2-проверку с тем же work factor, что и существующая учётная запись: это уменьшает утечку существования аккаунта через время ответа.
- `setAccountEnabled(accountId, false)` отключает аккаунт и отзывает все его сессии в одной транзакции. Повторное включение не восстанавливает старые bearer tokens.

`KSLAccount` намеренно не содержит парольный хеш. Не помещайте bearer token в обычные логи, публичные метаданные или URL.

### 21.4 Ограничение попыток входа

Для endpoint регистрации/входа используйте `KSLAuthAttemptLimiter` до выполнения PBKDF2. Один вызов `tryAcquire(key)` расходует одну попытку; при успешной аутентификации вызовите `recordSuccess(key)`, чтобы очистить окно.

```kotlin
import java.util.Locale

val accountLimiter = api.createAuthAttemptLimiter(maxAttempts = 5)
val addressLimiter = api.createAuthAttemptLimiter(maxAttempts = 20)

fun login(username: String, password: CharArray, remoteAddress: String): KSLAccountSession? {
    val accountKey = username.trim().lowercase(Locale.ROOT)
    if (!addressLimiter.tryAcquire(remoteAddress)) return null
    if (!accountLimiter.tryAcquire(accountKey)) return null

    val session = accounts.authenticate(username, password)
    if (session != null) {
        accountLimiter.recordSuccess(accountKey)
        addressLimiter.recordSuccess(remoteAddress)
    }
    return session
}
```

Передавайте в limiter только нормализованный идентификатор и адрес клиента, полученный из доверенного сетевого слоя, а не из произвольного поля запроса. При отказе из-за лимита не запускайте PBKDF2. Настройки по умолчанию: 5 попыток за 60 секунд, блокировка на 5 минут и максимум 10 000 отслеживаемых ключей. Таблица ограничена по памяти и при заполнении временно запрещает новые ключи до очистки истёкших записей.

Limiter хранит в памяти SHA-256 ключей, действует только внутри одного процесса и сбрасывается при перезапуске. Для нескольких Paper-серверов или публичного сервиса добавьте общий rate limit на gateway/edge или в общем хранилище. Для защиты от перебора используйте отдельные лимитеры по аккаунту и по адресу; один составной ключ сам по себе позволяет обходить лимит сменой имени пользователя.

### 21.5 Потоки и жизненный цикл

PBKDF2 требует заметной вычислительной работы. Не вызывайте `register` и `authenticate` на главном потоке Bukkit. Используйте собственный executor/задачу аддона и возвращайте только необходимые игровые изменения на главный поток.

Хранилище не владеет переданным `DataSource`: закройте его при выгрузке аддона. Для SQLite, PostgreSQL и MySQL можно использовать общий DataSource, но каждому аддону требуется собственный tablePrefix. Фабрика KSLAPI назначает его автоматически; при прямом создании хранилища задайте префикс явно. Не подключайте KSL под root. Для production настройте резервное копирование и ограниченные права SQL-пользователя.

Это базовый слой аккаунтов, а не готовая система восстановления паролей, MFA, CAPTCHA, rate limiting или управления ролями. Такие функции должны быть добавлены отдельно с учётом модели угроз вашего сервера.


## Карта данных и хранилищ

Книга объединяет SQL и JDBC, SQLite, MySQL/MariaDB, PostgreSQL, PostEmberDB integration client, Accounts, транзакции, миграции, YAML/TOML/JSON, CSV, NBT и schematic-файлы. События и игровые DSL находятся в [Книге I](01-scripts.md), создание аддонов — в [Книге II](02-addons.md).


---

## Дополнительная глава: NBT, vanilla components и бинарные .nbt-файлы

KSL bundles and relocates Item-NBT-API, so a separate NBTAPI server plugin is not required.

## Custom item data

```kotlin
val item = ItemStack(Material.DIAMOND_SWORD)
item.modifyNBT {
    setString("owner", player.uniqueId.toString())
    setInt("level", 3)
    getOrCreateCompound("stats").setDouble("damage", 12.5)
}
item.readNBT {
    val owner = string("owner")
    val level = int("level") ?: 0
}
```

On Minecraft 1.20.5+, item `modifyNBT/readNBT` access the `custom_data` component, not vanilla item components. Avoid re-applying an `ItemMeta` snapshot captured before the NBT edit: that older snapshot can overwrite the newer item data. Make NBT edits before obtaining/editing ItemMeta, or perform the item-meta work first and then the NBT edit.

## Vanilla item components

```kotlin
item.modifyComponents {
    setString("minecraft:custom_name", """{"text":"Example"}""")
}
item.readComponents {
    val customName = string("minecraft:custom_name")
}
```

Component values must use the format expected by the corresponding Minecraft component. Component editing is a low-level operation: malformed data can make the item invalid or produce unexpected in-game behavior, so validate values against the target Minecraft version.

## Entities and block entities

```kotlin
entity.modifyNBT { setBoolean("Silent", true) }
entity.modifyPersistentNBT { setString("owner", player.uniqueId.toString()) }

val state = block.state
if (state is TileState) {
    state.modifyNBT { setBoolean("Lock", true) }
}
```

Entity `modifyNBT/readNBT` work with vanilla entity NBT. `PersistentNBT` methods work with the persistent custom-data compound. Vanilla block NBT exists only on block entities (chests, furnaces, signs, etc.), not ordinary blocks.

## Compound operations

Supported values: string, byte, short, int, long, float, double, boolean, byte/int/long arrays, nested compounds, key enumeration, existence checks, removal, SNBT parsing, and compound merging.

NBT operations must run on the server primary thread. Use `runSync { ... }` when calling them from asynchronous work.

The legacy `ItemStack.setTag/getTag/hasTag` and typed PDC tag APIs remain unchanged.

## Detached NBT document engine

Для подготовки и преобразования SNBT без изменения живого предмета, сущности или блока используйте detached-документ. Он хранит собственный снимок NBT и публикует изменения только после успешного завершения edit-блока.

```kotlin
val document = KSLNBTEngine.parse("""{owner:"Alex",stats:{level:3}}""")

document.edit {
    setStringAt("owner", "Riley")
    setIntAt("stats.level", 4)
}

val level = document.read { intAt("stats.level") }
val snbt = document.snbt()
val backup = document.copy()
```

`KSLNBTEngine.empty()` создаёт пустой документ. `parse()` проверяет размер SNBT, глубину контейнеров и число элементов до передачи текста парсеру Item-NBT-API. Стандартные лимиты — 1 MiB текста, 64 уровня вложенности и 65 536 элементов; их можно настроить через `KSLNBTLimits`.

`edit { ... }` работает с временной копией. Если блок выбрасывает исключение или итоговый SNBT не проходит проверку, исходный документ остаётся неизменным. Значения, возвращённые из `read { ... }`, следует считать снимком; callback получает только read-only view.

Detached engine не привязан к Bukkit и не изменяет live-объекты. Применение результата к ItemStack, Entity или TileState по-прежнему выполняйте через соответствующие `modifyNBT` методы на главном потоке сервера. Самостоятельная проверка SNBT не гарантирует, что произвольные поля допустимы для конкретной версии Minecraft.


## Бинарные файлы .nbt

Для бинарных NBT-файлов в папке `scripts/` используйте `loadNBT`, `saveNBT` и `editNBT`. Это не SNBT-текстовый файл: KSL читает и пишет настоящий бинарный NBT через встроенный Item-NBT-API.

```kotlin
val document = loadNBT("data/player.nbt")
    ?: KSLNBTEngine.empty()

document.edit {
    setString("owner", player.uniqueId.toString())
    setIntAt("stats.level", 12)
}

check(saveNBT("data/player.nbt", document))
```

`loadNBT` возвращает `null`, если файла ещё нет. `editNBT` сам создаёт пустой документ, если файла нет, а затем атомарно сохраняет его. Путь остаётся внутри `scripts/`, расширение должно быть `.nbt`. Стандартный лимит входного файла — 16 MiB; лимиты SNBT по умолчанию остаются 1 MiB текста, 64 уровня и 65 536 элементов. Ошибка записи не заменяет ранее существующий файл.


## Реальные Paper-интеграционные проверки

Бинарные load/save используют внутренний NBT runtime Minecraft и поэтому не проверяются через MockBukkit. Для них в CI запускается отдельный тестовый плагин внутри настоящего Paper 1.21.1. Он проверяет бинарный round-trip, повторное сохранение, независимость detached-документа и сохранность существующего файла при отклонённой записи.

MockBukkit остаётся основной средой быстрых unit-тестов; реальный Paper используется только для интеграционной проверки NMS-зависимого пути.


---

## Дополнительная глава: CSV: таблицы, парсер, схемы и нормализация

KSL 1.2.2 includes a bounded CSV parser/writer and an editable table model. Script files live inside `scripts/`; the standalone `KslCsvFiles` API also accepts a Java `File` for addons.

## Build a table in memory

```kotlin
val users = csvTable("id", "name", "coins", "active") {
    addRow(1, "Alice", 120, true)
    addRow(2, "Bob", 75, false)
    addRow(
        "id" to 3,
        "name" to "Riley",
        "coins" to 90,
        "active" to true
    )
}

users.addColumn("rank", "new")
users[0]["coins"] = 150
users.sortBy("coins", descending = true)

val activeUsers = users.filterRows { getBoolean("active") }
val publicView = activeUsers.select("id", "name", "rank")
```

## Parse and encode text

```kotlin
val table = csvText.parseCsv()
val encoded = table.toCsv()

val semicolon = csvText.parseCsv(
    KslCsvOptions(
        delimiter = ';',
        hasHeader = true,
        trimUnquoted = true
    )
)
```

The parser follows the common RFC 4180 quoting rules: delimiters, quotes and line breaks inside quoted fields are preserved; embedded quotes are escaped by doubling them. It accepts CRLF and LF records and a UTF-8 BOM. It rejects malformed quotes, duplicate/blank headers and rows wider than the header. Short rows are padded with empty cells.

## Read, write and edit script files

Files are stored under `scripts/`. Paths are canonicalized so a script cannot escape that directory through `../` or a symbolic link.

```kotlin
val rows = loadCsv("data/users.csv")
    ?: csvTable("id", "name", "active")

val updated = editCsv("data/users.csv", listOf("id", "name", "active")) {
    addRow(42, "Example", true)
    val index = column("id").indexOf("42")
    if (index >= 0) this[index]["active"] = false
}

check(saveCsv("data/users.csv", updated))
```

## Typed row access

```kotlin
val user = rows.first()
val id = user.requireInt("id")
val name = user.getString("name", default = "Unknown")
val active = user.getBoolean("active")
val score = user.getDouble("score", default = 0.0)
val record: Map<String, String> = user.asMap()
```

Accessors include `getString`, `getInt`, `requireInt`, `getLong`, `requireLong`, `getDouble`, `requireDouble`, `getBoolean` and `requireBoolean`. Table helpers include `append`, `addRow`, `insertRow`, `removeRow`, `addColumn`, `removeColumn`, `column`, `findFirst`, `filterRows`, `select`, `mapRows`, `sortBy`, `toMaps` and `copy`.

## Limits and options

`KslCsvOptions` defaults to comma delimiter, double-quote escaping, a header row, CRLF output and skipping physically empty lines. Default safety limits are 100,000 data rows, 256 columns, 1 MiB per field and 16 Mi characters total input/output. Script file reads are limited to 32 MiB. Limits can be lowered or raised explicitly within the hard caps.

## Public API and addons

`ru.privateserver.ksl.std.csv.*` is available to KSL scripts by default. Addons can import `ru.privateserver.ksl.api.*` and use `KSLCSVTable`, `KSLCSVRow`, `KSLCSVOptions`, plus the `KSLAPI.csvTable/parseCsv/readCsv/writeCsv` extensions. The CSV exports were introduced in API v15; the current development API is v19.


## Typed schemas and normalization

A CSV file stores text cells, so define the expected columns and types with a schema. Validation reports structural and value errors; normalization returns a new table with schema columns ordered first, configured defaults applied, and optional trimming.

```kotlin
val schema = csvSchema {
    int("id", required = true)
    text("name", required = true, trim = true)
    decimal("balance", defaultValue = BigDecimal.ZERO)
    bool("active", defaultValue = true)
    uuid("owner")
    date("joined")
    instant("updatedAt")
}
val result = schema.normalize(loadCsv("data/users.csv") ?: csvTable())
if (!result.isValid) {
    result.validation.issues.forEach { issue ->
        scriptLogger("csv").warn("CSV row " + (issue.rowNumber?.toString() ?: "-") +
            ", column " + issue.column + ": " + issue.message)
    }
}
val normalized = result.table
```

Supported schema types are `TEXT`, `INT`, `LONG`, `DECIMAL`, `DOUBLE`, `BOOLEAN`, `UUID`, `DATE` and `INSTANT`. Required values, defaults, trimming, allowed values, and a bounded issue count are supported. Addons can also call `KSLAPI.csvSchema { ... }`.

Additional table operations include `upsertBy`, `distinctBy`, `groupRowsBy`, `findAll`, `updateRows`, `renameColumn`, `takeRows`, `dropRows` and `sortedBy`. Typed row access now includes Decimal, UUID, LocalDate and Instant getters.


---

## Дополнительная глава: Schematic clipboard и файлы .schem

KSL 1.2.2 exposes optional WorldEdit clipboard support. WorldEdit must be installed and enabled for schematic operations; the rest of KSL continues to work without it.

Files are stored under `plugins/KotlinScriptLoader/schematics/`. Only `.schem` files are accepted. File paths are canonicalized and cannot leave that directory. Loaded/captured clipboards are limited to 2,000,000 blocks and input files to 32 MiB.

```kotlin
// Copy the current WorldEdit selection and store it as Sponge schematic.
val clipboard = copySchematicSelection(player)
if (clipboard != null) {
    logger.info("Captured ${clipboard.width}x${clipboard.height}x${clipboard.length}")
    saveSchematic("buildings/spawn.schem", clipboard)
}

// Load a saved schematic and paste it at a location.
val saved = loadSchematic("buildings/spawn.schem")
if (saved != null) {
    pasteSchematic(player, saved, player.location, ignoreAirBlocks = true)
}

// One-call convenience function.
saveSchematicSelection(player, "backups/selection.schem")
```

Available operations:

- `copySchematicSelection(player)` — capture a WorldEdit selection into `KSLClipboard`.
- `loadSchematic(fileName)` — read a Sponge `.schem`; missing files return `null`.
- `saveSchematic(fileName, clipboard)` — atomically write the clipboard.
- `saveSchematicSelection(player, fileName)` — capture and save in one call.
- `pasteSchematic(player, clipboard, location, ignoreAirBlocks)` — paste and record the operation in WorldEdit undo history.

The API uses WorldEdit's public clipboard readers/writers and paste operations. Schematic operations must run on the Bukkit primary thread. Clipboard blocks are copied; entity copying is not enabled by this first version.