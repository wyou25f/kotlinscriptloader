# KotlinScriptLoader Documentation

Documentation for KSL users, script authors, addon developers, and contributors.

> KSL is actively evolving. When an older example differs from source code in the current branch, trust the source and tests. A roadmap or changelog entry does not prove that a feature exists in the current build.

## Getting started

| Goal | Guide |
|---|---|
| Installation and first script | [Getting Started](Getting-Started.md) |
| Complete script DSL | [Script DSL Reference](Script-DSL-Reference.md) |
| Practical recipes | [Cookbook](Cookbook.md) |
| Architecture and overview | [KSL Encyclopedia](KSL-Encyclopedia.md) |
| YAML, TOML, JSON, migrations | [Data Formats](Data-Formats.md) |
| Performance and scaling | [Performance and Scaling](Performance-and-Scaling.md) |
| Production operations | [Production Operations](Production-Operations.md) |
| Built-in library and runtime tools | [Standard Library Architecture](Standard-Library-Architecture.md) |
| Addon development | [Addon Development](Addon-Development-Guide.md) |
| Kotlin/Java addon API | [Public API](Public-API.md) |
| Imports and local libraries | [Packages and Imports](Packages-and-Imports.md) |
| Custom standard library | [Custom Standard Library](Custom-Standard-Library.md) |
| SQL and databases | [Database Guide](Database-Guide.md) |
| Caching and profiling | [Runtime API](Runtime-API.md) |
| Script lifecycle and hot reload | [Script Lifecycle and Resources](Script-Lifecycle-and-Resources.md) |
| Errors and diagnostics | [Troubleshooting](Troubleshooting.md) |
| Error code catalog | [Error Codes](Error-Codes.md) |
| Sandbox limitations | [Security and Sandbox](Security-and-Sandbox.md) |
| Build and CI | [Build Diagnostics](Build-Diagnostics.md) |

## For AI assistants

When an AI assistant writes or reviews KSL code, start with [AGENTS.md](../AGENTS.md) and [AI Assistant Guide](AI-ASSISTANT-GUIDE.md). Provide the exact commit/branch or connect the repository. The assistant must verify APIs against the source and tests in the current branch.

Never share secrets, tokens, passwords, or private data.

## For developers

- [KSL Encyclopedia](KSL-Encyclopedia.md) — architecture and DSL.
- [Public API](Public-API.md) — external contracts.
- [Standard Library Architecture](Standard-Library-Architecture.md) — packages and modules.
- [Addon Development](Addon-Development-Guide.md) — addon lifecycle.
- [Script Lifecycle and Resources](Script-Lifecycle-and-Resources.md) — ownership and cleanup.
- [Troubleshooting](Troubleshooting.md) — diagnostics.

Before changing an API, inspect its implementation and tests. Do not expose an internal class just to simplify one script. Preserve hot reload and resource cleanup.

## Project links

- [GitHub repository](https://github.com/wyou25f/kotlinscriptloader)
- [Main README](../README.md)
- [Examples](../examples/)
- [IntelliJ IDEA integration](../ide/README.md)
- [CHANGELOG](../CHANGELOG.md)
