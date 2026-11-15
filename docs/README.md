# KotlinScriptLoader Documentation

Documentation for KSL users, script authors, addon developers, and contributors.

> KSL is actively evolving. When an older example differs from source code in the current branch, trust the source and tests. A roadmap or changelog entry does not prove that a feature exists in the current build.

## Start here

| Goal | Guide |
|---|---|
| Install KSL and write a first script | [Getting Started](Getting-Started.md) |
| Understand the whole platform and how it works | [KSL Developer Handbook](KSL-Developer-Handbook.md) |
| Learn the complete script DSL | [Script DSL Reference](Script-DSL-Reference.md) |
| Find practical script recipes | [Cookbook](Cookbook.md) |
| Understand the architecture | [KSL Encyclopedia](KSL-Encyclopedia.md) |

## Script author references

| Topic | Guide |
|---|---|
| All built-in STD packages, functions, behavior and limits | [STD Complete Reference](STD-Complete-Reference.md) |
| Standard Library design and custom modules | [Standard Library Architecture](Standard-Library-Architecture.md) |
| Short Standard Library examples | [Standard Library Guide](Standard-Library-Guide.md) |
| YAML, TOML, JSON and data migrations | [Data Formats](Data-Formats.md) |
| SQL and persistent storage | [Database Guide](Database-Guide.md) |
| Caches, profilers and worker pool | [Runtime API](Runtime-API.md) |
| Script lifecycle and hot reload | [Script Lifecycle and Resources](Script-Lifecycle-and-Resources.md) |
| Imports and local libraries | [Packages and Imports](Packages-and-Imports.md) |
| Security boundaries | [Security and Sandbox](Security-and-Sandbox.md) |

## Addon and API development

| Topic | Guide |
|---|---|
| Public API overview | [Public API](Public-API.md) |
| Complete Kotlin/Java addon API reference | [Public API Complete Reference](Public-API-Complete-Reference.md) |
| Build and register an addon | [Addon Development Guide](Addon-Development-Guide.md) |
| Publish custom extension packages | [Custom Standard Library](Custom-Standard-Library.md) |
| Resource ownership and cleanup | [Script Lifecycle and Resources](Script-Lifecycle-and-Resources.md) |

## Operations and troubleshooting

| Topic | Guide |
|---|---|
| Performance and scaling | [Performance and Scaling](Performance-and-Scaling.md) |
| Production operations | [Production Operations](Production-Operations.md) |
| Errors and diagnostics | [Troubleshooting](Troubleshooting.md) |
| Error code catalog | [Error Codes](Error-Codes.md) |
| Localization | [Localization](Localization.md) |
| Build and CI | [Build Diagnostics](Build-Diagnostics.md) |

## For contributors and AI assistants

When an assistant writes or reviews KSL code, start with [AGENTS.md](../AGENTS.md) and [AI Assistant Guide](AI-ASSISTANT-GUIDE.md). Verify exact signatures and behavior against the current branch's source and tests.

Before changing an API, inspect its implementation and tests. Keep the script DSL, addon Public API and internal implementation separate. Preserve reload behavior and resource cleanup.

Never share secrets, tokens, passwords, webhook URLs, database credentials or private server data.

## Project links

- [GitHub repository](https://github.com/wyou25f/kotlinscriptloader)
- [Main README](../README.md)
- [Examples](../examples/)
- [IntelliJ IDEA integration](../ide/README.md)
- [CHANGELOG](../CHANGELOG.md)
