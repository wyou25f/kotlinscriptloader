import example.addon.dsl.exampleWelcome

player?.exampleWelcome()

val api = kslOrNull
if (api != null) {
    api.scripts().forEach { script ->
        println("${script.id}: loaded=${script.loaded}")
    }
}
