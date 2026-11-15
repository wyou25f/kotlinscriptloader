# KSL Database Guide

This guide explains how to store KSL data in MySQL or MariaDB and inspect it with phpMyAdmin. By default, KSL uses SQLite (the ksl.db file), which is sufficient for a small server.

Look up console error codes with /ksl error <code> or in [Error-Codes.md](Error-Codes.md).

## 1. What is stored, and when should you use MySQL?

KSL stores three categories of data:

| Data | Script API | Table |
|---|---|---|
| persist(...) values | var coins by persist("coins", persistent = true) { 0 } | ksl_persist |
| table(...) values | table("stats").set(uuid, "kills", 5) | ksl_table |
| Custom SQL data | database.connection, dbExecute, dbQuery | Your tables |

KSL also uses ksl_meta for schema metadata and reserves ksl_scripts.

| | SQLite (default) | MySQL / MariaDB |
|---|---|---|
| Setup | None | Database server required |
| Storage | plugins/KotlinScriptLoader/ksl.db | Database server |
| Manual inspection | DB Browser for SQLite | phpMyAdmin or any SQL client |
| Shared data across Minecraft servers | No | Yes |
| Backups | Copy file while server is stopped | mysqldump or phpMyAdmin export |
| Best fit | One server, small data volume | Hosting with a database, multiple servers, manual inspection |

## 2. Requirements

- MySQL 5.7+ or MariaDB 10.2+. Older versions may not be able to create KSL tables because of key-length limits.
- phpMyAdmin or another SQL client with permission to create databases and users.
- Network access from the Minecraft host to the database server (usually port 3306).

Minecraft hosting providers often supply the database host, port, database name, username, and password. When those are available, skip database installation and user creation and proceed to section 5.

## 3. Installing a database server

### Windows: XAMPP or OpenServer

1. Download and install XAMPP or OpenServer from its official source.
2. Start Apache and MySQL in the control panel.
3. Open phpMyAdmin at http://localhost/phpmyadmin.

### Linux (Ubuntu/Debian)

~~~bash
sudo apt update
sudo apt install mariadb-server
sudo mysql_secure_installation
# Install phpMyAdmin only if needed:
sudo apt install phpmyadmin
~~~

### Docker

~~~bash
docker run -d --name ksl-db -p 3306:3306 \
  -e MARIADB_ROOT_PASSWORD=CHANGE_THIS_PASSWORD \
  -e MARIADB_DATABASE=ksl -e MARIADB_USER=ksl -e MARIADB_PASSWORD=CHANGE_THIS_TOO \
  mariadb:11
~~~

This creates the database and user; continue with section 5.

## 4. Create a database and user in phpMyAdmin

Do not use the root account for KSL. Create a dedicated user with access only to the KSL database.

### Using the UI

1. Open phpMyAdmin and sign in as an administrator.
2. Open Databases, create a database named ksl, and choose utf8mb4_general_ci.
3. Open User Accounts and choose Add user account:
   - Username: ksl.
   - Host: localhost when Minecraft and the database run on the same machine. Otherwise specify the Minecraft host IP or use a restricted host pattern.
   - Set a strong password and store it securely.
   - Do not create a database with the same name as the user.
   - Do not grant global privileges.
4. Edit the user's privileges, choose database ksl, and grant the permissions required for this database.

### Using SQL

~~~sql
CREATE DATABASE ksl CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'ksl'@'localhost' IDENTIFIED BY 'YOUR_PASSWORD';
GRANT ALL PRIVILEGES ON ksl.* TO 'ksl'@'localhost';
FLUSH PRIVILEGES;
~~~

When Minecraft runs on another host, replace localhost with that host's IP address. Avoid unrestricted remote access.

## 5. Configure KSL

Open plugins/KotlinScriptLoader/config.yml. If the database: section is missing in an older config, add it manually; KSL does not append every new key to existing files automatically.

~~~yaml
database:
  type: mariadb          # sqlite | mysql | mariadb
  host: localhost
  port: 3306
  name: ksl
  user: ksl
  password: "YOUR_PASSWORD"
  ssl-mode: disable      # disable | trust | verify-ca | verify-full
  pool-size: 4
  # cache: true
~~~

| Setting | Description |
|---|---|
| type | sqlite, mysql, or mariadb. For MySQL 8, use mysql so the appropriate authentication support is enabled. |
| host, port | Database host and port; the default port is 3306. |
| name | Database name. On Linux, name case may matter. |
| user, password | Credentials created in section 4. Quote the password when it contains special characters. |
| ssl-mode | Use disable for a local database. For remote connections, prefer verify-ca or verify-full with certificates. trust encrypts without certificate validation. |
| pool-size | Number of connections (1–32). Four is enough for many servers. |

**The password is stored as plain text in config.yml.** Do not publish the file or show it in screenshots. To avoid storing it in the file, leave password empty and set KSL_DB_PASSWORD in the server process environment (for example, in the service manager or Docker environment).

Restart the server. The database type cannot be switched live.

## 6. Verify the connection

1. The startup log should identify the database type, database name, host, and port and report applied migrations.
2. Run /ksl doctor. The database and database-table checks should pass and identify MySQL or MariaDB.
3. Open database ksl in phpMyAdmin. The KSL tables should include ksl_scripts, ksl_persist, ksl_table, and ksl_meta.
4. Test a write from a script: table("test").set("hello", "world", 42). The ksl_table value should be stored with its type prefix, such as i:42.

KSL table values use type prefixes: i: integer, l: long, d: decimal, b: boolean, s: string, and n: null. Preserve this format when editing values manually.

## 7. Migrate data from SQLite

If you already use SQLite and have data to keep:

1. Leave ksl.db in the plugin directory.
2. Change database.type to mysql or mariadb and restart the server.
3. Run /ksl dbmigrate. This is a preview and does not modify data.
4. Run /ksl dbmigrate confirm to copy persist and table data.

Important details:

- Migration can be repeated. Existing destination keys are overwritten with values from ksl.db.
- ksl.db is not modified or deleted; keep it as a backup.
- KSL table names, script names, and keys in MySQL/MariaDB are limited to 190 characters. KSL validates lengths before queueing writes.
- If a key exceeds 190 characters, migration stops and identifies it (error code 7377b). Shorten the key in ksl.db and retry.
- Migration runs in the background and does not block server ticks.

## 8. Troubleshooting connections

Find the KSL error code in the console and run /ksl error <code>.

| Message | Cause | What to check |
|---|---|---|
| Access denied for user | Incorrect password or user host mismatch | Check credentials and the host assigned to the user. |
| Unknown database | Database does not exist or name is wrong | Create the database and check the name/case. |
| Connection refused | DB service is stopped, host/port is wrong, or it listens only on localhost | Start MySQL/MariaDB and check bind-address and port. |
| Connection timed out | Firewall/network restriction | Allow database access from the Minecraft host. |
| RSA public key is not available | MySQL 8 configured as MariaDB | Set type: mysql. |
| Key too long (767 bytes) | Old MySQL/MariaDB version | Upgrade to a supported version. |
| Data too long for column | Key exceeds the supported limit | Shorten the key. |
| CREATE command denied | User lacks schema privileges | Grant required privileges on the KSL database. |
| Too many connections | Database connection limit reached | Reduce pool-size or adjust database server limits. |
| SSL / PKIX certificate error | SSL mode or certificate configuration is incorrect | Configure trusted certificates; use disable only for a local connection. |

KSL does not crash when the database is unavailable. Scripts continue to run, and persist values remain in memory until restart. KSL logs the reason. A default used after a failed persist read is cached until the script unloads, so every property access does not repeat the failed query. Reading is attempted again after a script reload.

KSL deliberately does not fall back to SQLite automatically: doing so could split data across two stores. Correct the configuration and restart the server.

## 9. Performance and caching

- table() values are cached in memory. Cache is enabled for SQLite and disabled by default for MySQL/MariaDB because another server or an administrator may modify the same database. Set cache: true or cache: false explicitly.
- /ksl reload clears the cache. Reload scripts after editing values directly in phpMyAdmin.
- Writes use a separate sequential background queue (up to 10,000 pending operations), preserving update order.
- dbQuery/query use a separate reader pool sized with the connection pool, so slow SELECT statements do not block the write queue.
- Read and write queues are bounded. When saturated, new operations are rejected with a warning rather than growing memory without limit. Inspect both queues with /ksl doctor.
- table() reads remain synchronized with the write queue so a read after set(...) sees the latest value.
- set(), delete(), increment(), and synchronous reads share a table lock so barriers and increments cannot overtake an already queued write.
- Repeated writes to the same key are coalesced when possible; the queue stores the newest value instead of every intermediate update.
- If queues grow continuously, the database is slower than the scripts (error 1579b).
- increment() checks Long overflow and fails rather than silently wrapping around.
- With multiple Minecraft servers writing to one database, concurrent increment() operations may lose updates. Use a single writer or an atomic SQL update such as UPDATE ... SET x = x + 1 through dbExecute for shared counters.

## 10. Custom SQL from scripts

You do not need SQL for table() or persist. Custom tables and queries use database, dbExecute, dbQuery, or the shorter execute and query aliases.

### Where query YAML files belong

Each script has a config file with the same base name. The file lives in the **KSL plugin directory root**, next to scripts/, not inside scripts/.

For scripts/money.kts, the layout is:

~~~text
plugins/
└── KotlinScriptLoader/
    ├── money.yml
    └── scripts/
        └── money.kts
~~~

Example money.yml:

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

Reload scripts after changing the file with /ksl reload. If KSL cannot find a query, check the config filename, database.queries.* path, and YAML indentation.

### SQL methods and aliases

Write portable SQL that works on SQLite and MySQL/MariaDB:

- Avoid PRAGMA, AUTOINCREMENT, and INSERT OR REPLACE.
- Prefix custom table names with the script name, such as shop_orders.
- Quote reserved words such as key, order, and group using the database's identifier quoting rules.

Existing methods remain available:

- dbExecute(sql, ...args) — execute without returning rows.
- dbQuery(sql, ...args) { rs -> ... } — read results through a ResultSet.
- dbExecuteYaml(path, ...args) and dbQueryYaml(path, ...args) { rs -> ... } — query text comes from YAML.

Short aliases are also available:

- execute(sql, ...args) and query(sql, ...args) { ... }.
- executeYaml(path, ...args) and queryYaml(path, ...args) { ... }.
- executeAsync(...), queryAsync(..., mapper = { ... }), executeYamlAsync(...), and queryYamlAsync(..., mapper = { ... }) return CompletableFuture.

Future-based methods are useful when chaining operations or handling errors without blocking the caller. A queryAsync mapper must return ordinary Kotlin values/collections: the JDBC ResultSet and connection are closed before the future completes. If the bounded queue is full, the future completes exceptionally with RejectedExecutionException rather than adding unlimited work.

In query { ... } and queryYaml { ... }, ResultSet is the block receiver, so next() and getLong("balance") can be called directly:

~~~kotlin
execute("CREATE TABLE IF NOT EXISTS coins (uuid VARCHAR(36) PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0)")

query("SELECT balance FROM coins WHERE uuid = ?", player.uniqueId.toString()) {
    if (next()) {
        val balance = getLong("balance")
        println("Balance: $balance")
    }
}
~~~

The older callback form is also supported:

~~~kotlin
dbQuery("SELECT balance FROM coins WHERE uuid = ?", player.uniqueId.toString()) { rs ->
    if (rs.next()) println(rs.getLong("balance"))
}
~~~

**Threading:** database methods execute asynchronously on the DB pool. Do not call Bukkit APIs for players/worlds directly from a query block. Use runSync { ... } for Bukkit changes. Do not leave JDBC connections open; when using database.connection directly, wrap it in use { }. Handle Future failures with exceptionally { ... } or whenComplete { value, error -> ... }; never call join() on the server main thread.

## 11. Security and backups

- Use a dedicated database account with permissions limited to the ksl database; do not use root.
- For a local database, restrict access to localhost and do not expose port 3306 publicly.
- For a remote database, restrict firewall access to the Minecraft server IP and enable SSL.
- Do not publish config.yml when it contains a password.
- In phpMyAdmin, select database ksl and use Export with SQL format. From the shell, use mysqldump -u ksl -p ksl > ksl-backup.sql. Restore with mysql -u ksl -p ksl < ksl-backup.sql.
- Back up before KSL upgrades and before /ksl dbmigrate confirm.

## 12. Frequently asked questions

**Can I switch back to SQLite?** Yes. Set type: sqlite and restart. MySQL data is not copied back automatically.

**Does phpMyAdmin need to run on the Minecraft server?** No. It is only a management interface. KSL connects directly to the database server.

**What happens if the database restarts while the server is running?** The connection pool attempts to recover. Writes that have not reached the database may be lost during shutdown. KSL logs a code (4180b) and the number of lost records when this happens.

**Can I edit data in phpMyAdmin?** Yes. Reload scripts after editing table values so scripts can see refreshed data. Persist values already loaded in memory are refreshed after restart.

**What character encoding is used?** utf8mb4 supports Russian text and emoji. Keys are case-sensitive (Foo and foo are different), as in SQLite.
