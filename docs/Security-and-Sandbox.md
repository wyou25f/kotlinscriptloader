# Script Security and Sandbox

Look up console error codes with /ksl error <code> or in [Error-Codes.md](Error-Codes.md).

## 1. What can realistically be protected?

A KSL script executes **inside the server process and with the server's permissions**. The primary security rule is therefore: **only trusted people should be able to write or edit scripts** (access to scripts/ and the ksl.admin permission).

Sandbox is not intended to defend against an attacker who already has full KSL access. It is intended to:

- catch dangerous mistakes such as accidental Runtime.exec or System.exit calls;
- block some high-risk JVM access for scripts whose authors are not fully trusted;
- limit runaway resource registration that could damage server performance.

Sandbox is **not** a separate process or a full JVM isolation boundary. It does not restrict every capability exposed by Bukkit, the KSL DSL, or other plugins. Strict mode additionally blocks direct Java APIs for files and network, but similar operations may remain available through Bukkit objects or another plugin's API.

In a sandboxed context, direct access to plugin (the KotlinScriptLoaderPlugin instance) and database (the raw HikariDataSource) throws SecurityException. Use normal KSL context methods for ordinary tasks. This removes two broad escape hatches, but does not create per-script SQL permissions: available KSL SQL methods still use the configured database.

## 2. Three layers of protection

Enable the feature with sandbox: true at the root of config.yml.

### Layer 1: Source-text filter

Before compilation, the source is checked for dangerous fragments such as Runtime.getRuntime, System.exit, Class.forName, ProcessBuilder, and dispatchCommand(. Comments are stripped, and whitespace, line breaks, or backticks do not hide a detected call.

Advantage: the issue is reported immediately with a line number, and the script is not compiled.

Limitation: a name can be assembled dynamically from string fragments, so this is an early warning, not a complete defense.

### Layer 2: Class deny-list (security.sandbox-level: strict)

This is the default level. A sandboxed script uses its own classloader. When it first accesses a blocked class, the classloader denies the request and the script receives NoClassDefFoundError.

The default deny-list includes:

| Category | Classes/packages |
|---|---|
| Process/JVM control | Runtime, ProcessBuilder, Process, ProcessHandle, Thread, Timer, common executor pools |
| Loading external code | ClassLoader, URLClassLoader |
| Reflection | java.lang.reflect.*, invoke.MethodHandle, MethodHandles, MethodHandles$Lookup, VarHandle |
| Files and network | java.io.*, java.nio.file.*, java.nio.channels.*, java.net.* |
| Internals | sun.misc.*, jdk.internal.*, com.sun.*, javax.script.*, javax.tools.*, java.rmi.* |
| Kotlin reflection/compiler | kotlin.reflect.full.*, kotlin.reflect.jvm.*, org.jetbrains.kotlin.*, ASM, Javassist, ByteBuddy |

This is stronger than a text-only filter because the denial happens when the class is accessed, rather than based only on the source spelling. String concatenation, import aliases, Class.forName("java.lang." + "Runtime"), and reflection by name reach the same classloader restriction.

Normal lambdas and ordinary code continue to work. Compiler support classes used for Kotlin lambdas, such as LambdaMetafactory and MethodType, are not blocked.

Verify the current configuration on your server with /ksl doctor, under “Class isolation (sandbox)”. It runs a probe that attempts to load Runtime and checks raw access through the same classloader path as regular script execution. If the check fails, use security.sandbox-level: text.

When a script violates the deny-list, the console logs error code 0695a and the class name. For one script classloader, the first 128 distinct forbidden names are logged; later requests remain blocked but do not create more log entries. Add a specific class to security.class-deny-allowed to permit it. A package prefix ending in a dot, such as java.io., allows every class in that package.

### Layer 3: Console commands and resource limits

- plugin and database (raw access to the plugin and HikariCP) are available only to trusted/non-sandboxed scripts. Access from a sandboxed script throws SecurityException.
- executeConsole(...) runs commands **with console permissions**. Commands listed in security.console-command-blocklist (by default op, deop, stop, restart, reload, rl) are always blocked. Case, slash prefix, minecraft: namespace, and whitespace do not bypass the check.
- Console access is disabled by default for sandboxed scripts. Allow selected root commands through security.sandbox-console-allowlist (for example, [say, tell]). The global blocklist has priority over the allowlist.
- Scripts in security.sandbox-trusted are treated as trusted and retain the previous behavior.
- Attempts to execute a blocked command log code 5322c.
- Per-script limits default to 500 event listeners and 2,000 active tasks. Excess registrations are rejected with code 6752a or 3008a. This protects against scripts that register new handlers inside loops or recurring events.

## 3. Configuration

~~~yaml
sandbox: true

security:
  sandbox-level: strict          # strict | text
  sandbox-trusted: [admin/tools] # These scripts are exempt from sandbox checks
  sandbox-extra-forbidden: []    # Additional source fragments to reject
  sandbox-allowed: ["System.exit"] # Allow selected default text patterns
  class-deny-extra: [com.example.Secret, org.apache.commons.exec.]
  class-deny-allowed: [java.lang.Runtime]
  console-command-blocklist: [op, deop, stop, restart, reload, rl, ban]
  sandbox-console-allowlist: [say, tell] # Sandboxed scripts may run only these console commands
  limits:
    max-listeners-per-script: 500
    max-tasks-per-script: 2000
~~~

/ksl sandbox displays the current status, level, trusted scripts, and blocked commands. Apply changes with /ksl reload.

## 4. Production checklist

1. Grant ksl.admin only to people allowed to execute server code. Access to scripts/ is equivalent.
2. Run helper/third-party scripts with sandbox: true and strict mode. Check /ksl doctor.
3. Add your own trusted scripts to security.sandbox-trusted when they legitimately need reflection or process APIs.
4. Do not keep the database password in config.yml. Leave database.password empty and set KSL_DB_PASSWORD. Create a dedicated database user with access only to database ksl; see [Database Guide](Database-Guide.md).
5. Treat Discord webhooks as secrets. KSL avoids logging them, but do not publish config.yml.
6. Before running a script from the Internet, validate it with /ksl validate, inspect the source, and start with sandbox enabled.

## 5. Known limitations

- Class denial occurs when a class is accessed, which may be after the script has loaded (for example, inside an event handler). Layer 1 can catch some violations earlier with a line number.
- Legitimate operations may also be blocked. For example, Runtime.getRuntime().availableProcessors() will not work in sandbox mode. Add the script to sandbox-trusted or adjust class-deny-allowed if appropriate.
- Sandbox is not a security boundary around Bukkit or the KSL DSL. Some dangerous operations may remain available through Bukkit, integrations, or KSL methods. For example, an available SQL helper may execute DROP TABLE if the database user has permission. Restrict the database user's privileges and access to script execution.
- The deny-list works by class name. If an external library has a class name that matches a blocked package, add a narrow exception to class-deny-allowed. A package prefix ending in a dot removes protection for the entire package.
- For strong isolation requirements, run untrusted code in a separate process or container.
