val scriptLog = scriptLogger("starter.example") {
    level = KSLLogLevel.INFO
    prefix = "STARTER"
}
scriptLog.info("Starter script started")

player?.exampleWelcome()

val api = kslOrNull
if (api != null) {
    api.scripts().forEach { script ->
        scriptLog.debug("Script state", "id" to script.id, "loaded" to script.loaded)
    }
}
