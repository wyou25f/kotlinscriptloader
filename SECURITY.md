# Security Policy

## Scope and threat model

KotlinScriptLoader (KSL) is a Paper plugin that executes Kotlin scripts inside the Minecraft server JVM.

**KSL's sandbox is not an isolation boundary for hostile code.** Strict mode adds source preflight checks and a class-loading deny list, while capability checks restrict selected KSL features. These controls are defense in depth for trusted server-side scripts; they do not stop every possible interaction with the host JVM, Bukkit/Paper, other plugins, or server resources. They also do not provide CPU, memory, filesystem, or process isolation.

Only trusted administrators and developers should be able to:
- write to or replace files under the KSL `scripts/` directory;
- upload or install addon JARs;
- use `ksl.admin` or equivalent server-management permissions;
- change KSL security configuration.

Do not run scripts from untrusted users in the same JVM. For code that is not fully trusted, use a separately managed process/container with operating-system permissions and resource limits, and do not expose privileged server credentials to it.

## Security controls

The current development line includes layered controls. Their exact scope and configuration are documented in the four KSL books.

- Strict sandbox mode: static source preflight plus runtime class-deny checks. Per-script opt-outs and class allowlists should be treated as privileged configuration.
- Console command policy: dangerous administrative commands are blocked by default for KSL console dispatch; sandboxed scripts receive no console capability unless explicitly allowlisted.
- Database capabilities: sandboxed scripts do not receive raw JDBC access; bounded SQL access requires an exact script-id allowlist. SQL execution has timeouts and result/batch limits.
- Remote database transport: verified TLS is the default. Disabling certificate verification requires an explicit insecure-transport opt-in.
- Credentials: database passwords can be supplied through `KSL_DB_PASSWORD`; avoid committing secrets or putting them in logs.
- Account store: passwords use salted PBKDF2-HMAC-SHA256, session bearer tokens are stored as SHA-256 digests, and sessions can be revoked. Addons should apply per-account and per-client rate limiting.
- MCP endpoint: disabled by default and loopback-bound. A token in the server process environment is not a boundary against other code already running in that JVM.
- PostEmberDB adapter: experimental, loopback-only, and not a production persistence or authenticated transport.

## Secure deployment checklist

1. Keep write access to `plugins/KotlinScriptLoader/scripts/` and addon JAR locations limited to trusted administrators.
2. Run the Minecraft server under a dedicated, unprivileged operating-system account. Do not run it as root or an administrator.
3. Use a dedicated SQL user with only the permissions KSL needs. Restrict database network access to the server hosts.
4. Keep remote database certificate and hostname verification enabled. Store credentials in the service environment or a secrets manager.
5. Keep MCP disabled unless it is needed. Never expose its endpoint to a public interface.
6. Review `security.sandbox-trusted`, `security.class-deny-allowed`, `security.sandbox-allowed`, database allowlists, and console allowlists as privileged policy.
7. Keep backups and test restoration. Do not treat a successful database connection as proof that backups are recoverable.
8. Update KSL, Paper, Java, Gradle plugins and runtime libraries after reviewing compatibility and security advisories.

## Reporting a vulnerability

Please do not publish exploit details in a public issue.

Use GitHub's **Private vulnerability reporting** for this repository (Repository → Security → Advisories → Report a vulnerability) when that option is available. Include the affected commit or version, impact, a concise reproduction, and relevant logs with passwords, tokens, player data, IP addresses and other secrets removed.

If private vulnerability reporting is not enabled, contact the repository maintainer privately rather than posting exploit details publicly. This policy does not promise a fixed response or remediation timeline.

## Disclosure

Give the maintainer a reasonable opportunity to investigate and prepare a fix before public disclosure. Coordinate the publication of a security advisory and any reproduction details.
