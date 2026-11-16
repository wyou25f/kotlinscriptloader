# KotlinScriptLoader 1.2.2

KSL 1.2.2 focuses on script observability, test quality, runtime profiling and safer script-to-script contracts.

## Highlights

- **Script Registry:** explicit LOADED, DEGRADED, FAILED and UNLOADED states, last failure details, last-attempt timestamp and source-file presence. A still-running previous version remains visible after a failed reload.
- **Testing:** assertAll runs independent assertions together and reports all failures in one test result.
- **Performance:** KSLProfiler now records minimum and most recent durations in addition to calls, failures, total, average and maximum timings.
- **Inter-script communication:** expanded MesLib2 contract coverage for typed function exports, exact version matching and owner cleanup; added a provider/consumer example using @requires.
- **API:** Public API version 10. The existing four-argument KSLScriptInfo constructor remains available.

## Compatibility

- Main release: Paper 1.21.x, Java 21.
- Compatibility builds remain separate; use the JAR built for the matching Minecraft/Paper line.
- This update does not change the compatibility branches.

## Upgrade notes

- Back up plugin data and scripts before upgrading.
- MesLib2 exports remain in-memory and are removed when their owning script unloads.
- The sandbox is not an operating-system or JVM security boundary; only run trusted scripts and addons.

Full details: https://github.com/wyou25f/kotlinscriptloader/blob/main/CHANGELOG.md
