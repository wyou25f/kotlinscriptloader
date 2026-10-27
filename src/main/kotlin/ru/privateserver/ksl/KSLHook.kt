package ru.privateserver.ksl

// Integration with another plugin (economy, claims, ...). Implement it in an addon, register it with
// api.registerHook(), and scripts get it through hook<YourHook>("id"). Keep third-party class references
// inside the hook so scripts never touch them directly.
interface KSLHook {
    val hookId: String
    val pluginName: String
}
