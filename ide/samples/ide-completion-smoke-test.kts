import ru.privateserver.ksl.api.*

/*
 * Open this file in IntelliJ after syncing the ide/ Gradle project.
 * Completion smoke checks:
 * - kslOrNull. -> KSLAPI members
 * - api.service<...>(...) -> typed addon services
 * - BukkitScriptContext members such as onlinePlayers(), cmd(), onEvent<T>()
 * - default imports from ru.privateserver.ksl.std.*
 */

val api = kslOrNull
if (api != null) {
    api.scripts().forEach { script ->
        println("${script.id}: ${script.loaded}")
    }
}

val shortLabel = "KotlinScriptLoader".truncateCodePoints(12)
val ticks = 5.secondsToTicks()
