# KSL — Production Server Operations

A guide to installation, upgrades, secrets, backups, monitoring, and recovery.

## 1. Before startup

- Use compatible versions of Paper and Java 21.
- Download KSL from a trusted release or CI artifact.
- Keep a copy of the current JAR and plugins/KotlinScriptLoader/.
- Check free disk space, database availability, and network rules.
- Allow only trusted administrators to write to scripts/.
- Test scripts on a staging server with the same Paper/Java versions.

## 2. Secrets and access

Never commit passwords to Git. KSL can read the password from the KSL_DB_PASSWORD environment variable when database.password is empty.

~~~sh
export KSL_DB_PASSWORD='[DB_PASSWORD]'
~~~

In production, define the variable in a restricted systemd/Docker/hosting-panel configuration rather than a shared startup script. Create a separate database user for KSL; do not use root. Restrict access by IP and enable TLS with certificate verification for remote databases when supported.

Do not put secrets in .kts source files, YAML, TOML, JSON, changelogs, or issues. If a secret appears in a log or commit, treat it as exposed and rotate it.

## 3. Backups

Back up:

- plugins/KotlinScriptLoader/config.yml;
- plugins/KotlinScriptLoader/scripts/;
- script YAML configs in the KSL directory root;
- ksl.db when SQLite is used;
- a MySQL/MariaDB dump when using a remote database;
- required addon JARs and configs.

Do not copy an active SQLite file as an ordinary file without a coordinated snapshot or server shutdown. For a safe simple backup, stop the server or use a supported SQLite backup mechanism.

A backup is not verified until you perform a test restore on a staging server. Keep several generations, with at least one copy stored separately from the server host.

## 4. Updating KSL

1. Read the CHANGELOG and release notes.
2. Back up files and the database.
3. Check Java/Paper compatibility.
4. Update the JAR while the server is stopped.
5. Start a staging server and inspect the startup log.
6. Run /ksl doctor.
7. Check scripts, commands, placeholders, and integrations.
8. Review database migration messages.
9. If a problem occurs, save the logs and restore the previous JAR/backup using a tested procedure.

Do not delete plugins/KotlinScriptLoader/ for a “clean install”: it may contain user scripts and data.

## 5. After startup

- /ksl doctor — subsystem status.
- /ksl runtime — runtime/heap/worker telemetry.
- /ksl scripts — script status, if available in the current build.
- /ksl errors — recent errors; /ksl errors list — error catalog.
- /ksl error <code> — explanation of a code and what to check.
- /ksl watchdog — path to the latest early dump, if the monitor has triggered.
- dumps/ — multi-snapshot thread dumps with lock and JVM state analysis.

The watchdog monitor helps collect diagnostics, but does not replace Paper Watchdog or fix the cause of a stall.

## 6. Reloading scripts

Before reloading:

- Do not edit the same file simultaneously in multiple editors.
- Check syntax and dependencies.
- Remember that moving a script may change its ID and persistent-data namespace.
- Ensure that external tasks/resources have an owner and are closed.

If the new version does not compile, use KSL errors to fix it. Do not delete the working version or its data without a backup.

## 7. Incident: server lag

1. Do not increase every pool immediately.
2. Capture /ksl runtime and /ksl doctor.
3. Inspect database read/write queues.
4. Find long-running SQL and check indexes.
5. Check worker queue and per-script limits.
6. If the main thread stalls, save a thread dump.
7. Match the Server thread stack to the active KSL operation.
8. If the stack points to another plugin, isolate it on a staging server.

## 8. Incident: database unavailable

Check host/port, credentials, grants, SSL mode, firewall, and connection limits. Ensure the queue does not continue growing after recovery. Some unfinished operations may be lost during shutdown; inspect KSL warnings and verify data integrity. Do not automatically switch the source of truth to another database.

## 9. Permissions and trust

Scripts execute inside the server JVM. Sandbox is not a security boundary for hostile code. Do not allow unknown users to upload .kts files, addons, or JARs. A backup is not a substitute for access control.

## 10. Operations checklist

- [ ] Java 21 and a compatible Paper version.
- [ ] KSL JAR from a trusted source.
- [ ] Scripts and addons reviewed.
- [ ] Database password is not in Git.
- [ ] Database user is not root; permissions are restricted.
- [ ] Remote database is protected by firewall/TLS.
- [ ] Backup created and restore tested.
- [ ] /ksl doctor reports no errors.
- [ ] Queues and worker pool are not overloaded.
- [ ] Thread dumps and logs are available to administrators.
