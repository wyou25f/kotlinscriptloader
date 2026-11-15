# Книга III. Данные, базы данных и конфигурация KSL

**Практическое руководство по SQLite, MySQL, MariaDB, phpMyAdmin, SQL, YAML, TOML и JSON**

Книга объясняет, где KSL хранит данные, когда нужна внешняя база, как безопасно создать пользователя в phpMyAdmin, настроить SSL, писать SQL-запросы и не потерять данные при миграции.

Все примеры адаптируйте под свой сервер. Не публикуйте пароли и не открывайте БД всему интернету.

## Содержание

1. Общая модель хранения
2. SQLite или MySQL/MariaDB
3. Установка MySQL/MariaDB
4. phpMyAdmin: база и пользователь
5. Настройка config.yml
6. Проверка соединения
7. persist и table
8. Пользовательские SQL-таблицы
9. SQL из YAML
10. Асинхронные запросы
11. Миграция SQLite → MySQL/MariaDB
12. Кэширование, очереди и несколько серверов
13. Резервное копирование и восстановление
14. YAML для скриптов и аддонов
15. Schema validation и миграции YAML
16. TOML
17. JSON
18. Пути файлов и безопасность
19. Частые ошибки
20. Production checklist

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

## Глава 2. SQLite или MySQL/MariaDB

| Характеристика | SQLite | MySQL / MariaDB |
|---|---|---|
| Настройка | По умолчанию | Нужен database server |
| Хранение | Файл ksl.db | Отдельный сервер БД |
| Несколько Minecraft серверов | Не подходит как общий сервер хранения | Подходит |
| phpMyAdmin | Нет, нужен SQLite viewer | Да |
| Бэкап | Копия файла при остановленном сервере | SQL dump или экспорт |
| Лучший сценарий | Один небольшой сервер | Несколько серверов, хостинг БД, централизованное управление |

### 2.1 Когда SQLite достаточно

Выбирайте SQLite, если сервер один, данных немного, не нужна общая БД между несколькими Minecraft-серверами и нет причины поддерживать отдельную СУБД.

### 2.2 Когда нужна внешняя база

MySQL/MariaDB полезны, когда несколько серверов используют одно хранилище, нужна централизованная администрируемость или хостинг уже предоставил database credentials.

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
  ssl-mode: disable
  pool-size: 4
~~~

Для MySQL 8 указывайте:

~~~yaml
database:
  type: mysql
~~~

KSL использует MariaDB Connector/J и для MySQL, и для MariaDB, но для MySQL 8 формирует JDBC-параметры с учётом механизма аутентификации MySQL.

### 5.1 Параметры

| Ключ | Значение |
|---|---|
| type | sqlite, mysql или mariadb |
| host | Имя хоста или IP базы |
| port | Обычно 3306; диапазон 1–65535 |
| name | Имя базы |
| user | Пользователь БД; для remote DB не может быть пустым |
| password | Пароль пользователя |
| ssl-mode | disable, trust, verify-ca или verify-full |
| pool-size | Размер пула, KSL ограничивает его 1–32 |

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

KSL поддерживает SQLite и MySQL/MariaDB. Для общей совместимости:

- не используйте SQLite-only PRAGMA;
- избегайте AUTOINCREMENT, если нужен общий SQL для разных dialect;
- не используйте INSERT OR REPLACE;
- используйте portable types и синтаксис, поддерживаемый обоими диалектами;
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

### 10.4 Прямое подключение

Если используете низкоуровневый database.connection, закрывайте connection/statement/result set через use. Не удерживайте JDBC connection в глобальном объекте или Player session.

---

## Глава 11. Миграция SQLite → MySQL/MariaDB

KSL содержит команду миграции данных persist/table из старого файла SQLite в настроенную удалённую базу.

### 11.1 Подготовка

1. Сделайте резервную копию ksl.db.
2. Создайте MySQL/MariaDB database и dedicated user.
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

Записи выполняются пакетами. Повторный запуск обновляет существующие ключи destination данными из SQLite, поэтому это не способ объединить две независимые истории без перезаписи.

### 11.4 Ограничение длины ключей

Для MySQL/MariaDB имена таблиц/скриптов/ключи KSL ограничены 190 символами в используемой схеме. Если слишком длинная запись найдена, миграция остановится с диагностикой. Сократите ключ в исходной SQLite базе и повторите preview.

Не запускайте confirm до проверки preview и резервной копии.

---

## Глава 12. Кэширование, очереди и несколько серверов

### 12.1 table cache

table() может хранить значения в памяти. По умолчанию кэш включён для SQLite и отключён для MySQL/MariaDB, поскольку удалённую БД могут менять другие серверы или администратор через phpMyAdmin.

Настройка cache позволяет выбрать поведение явно. После ручного изменения table данных в phpMyAdmin перечитайте скрипты, чтобы обновить кэш.

### 12.2 Очереди

KSL использует ограниченные очереди фоновой записи и чтения. Записи проходят через последовательную очередь, чтобы сохранять порядок. Повторные записи одного ключа могут объединяться до фактической записи.

Если очереди постоянно растут, база не успевает за нагрузкой. Проверьте частоту вызовов, latency запросов, индексы, connection pool и лимиты сервера.

### 12.3 Несколько Minecraft серверов

MySQL/MariaDB позволяет нескольким серверам видеть одну БД. Но это не гарантирует, что любая операция KSL автоматически становится распределённой транзакцией.

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

TOML подходит для настроек, когда нужны явные типы и таблицы.

~~~toml
port = 25565
enabled = true
motd = "Welcome"

[database]
host = "localhost"
pool-size = 4

[features]
shop = true
~~~

### Чтение

~~~kotlin
val settings = loadToml("data/settings.toml")
val port = settings?.tomlInt("port", 25565) ?: 25565
val db = settings?.tomlTable("database")
val host = db?.tomlString("host", "localhost") ?: "localhost"
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
}
~~~

Также доступны loadToml/saveToml/editToml и aliases readToml/writeToml/updateToml.

### Ограничения кодека

TOML codec KSL работает с обычными Kotlin Maps и списками. Поддерживаются scalar values, arrays и nested tables. Null не поддерживается; arrays of tables и неподдерживаемые объекты должны считаться ошибкой.

Парсер преобразует документ в map, поэтому комментарии, исходные отступы и форматирование не сохраняются как отдельные метаданные. После повторной сериализации комментарии могут исчезнуть.

---

## Глава 16. JSON

JSON подходит для обмена данными, API payloads и файлов, где важна совместимость с другими системами. Стандартный JSON не поддерживает комментарии.

### Создание объекта

~~~kotlin
val profile = jsonObjectOf(
    "uuid" to player.uniqueId.toString(),
    "name" to player.name,
    "online" to true,
    "coins" to 250L,
    "tags" to listOf("builder", "vip")
)
~~~

### Чтение

~~~kotlin
val data = loadJsonObject("data/profile.json")
val coins = data?.long("coins", 0L) ?: 0L
val name = data?.string("name", "Unknown") ?: "Unknown"
~~~

В STD доступны string/int/long/double/bool, objectValue, arrayValue, hasKey, requireString/requireInt/requireLong/requireDouble/requireBoolean и безопасные accessor методы для JsonArray.

### Ограничения

toJsonElement поддерживает common Kotlin primitives, enum, maps, collections и массивы. Ключи Map обязаны быть String. NaN и Infinity не являются допустимыми JSON числами. Произвольный объект нужно сначала явно преобразовать в поддерживаемое представление.

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
- [ ] Не используется SQLite-only SQL при необходимости MySQL.
- [ ] Async callbacks возвращают обычные значения, не ResultSet.
- [ ] JDBC ресурсы закрываются.

**Конец книги III.** Следующая книга — коллекция практических рецептов.
