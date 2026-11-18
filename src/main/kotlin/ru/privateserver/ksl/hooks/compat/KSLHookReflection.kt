package ru.privateserver.ksl

/**
 * Compatibility facade for hook implementations that historically used the root package bridge.
 * The reflection implementation is kept with the hook module.
 */
internal object KSLHookReflection {
    fun loadClass(pluginName: String, className: String): Class<*>? =
        ru.privateserver.ksl.hooks.KSLHookReflectionCore.loadClass(pluginName, className)

    fun loadApi(pluginName: String, className: String): Any? =
        ru.privateserver.ksl.hooks.KSLHookReflectionCore.loadApi(pluginName, className)

    fun call(target: Any?, methodName: String, vararg args: Any?): Any? =
        ru.privateserver.ksl.hooks.KSLHookReflectionCore.call(target, methodName, *args)

    fun callFirst(target: Any?, methodNames: List<String>, vararg args: Any?): Any? =
        ru.privateserver.ksl.hooks.KSLHookReflectionCore.callFirst(target, methodNames, *args)

    fun invokeIfPresent(target: Any?, methodName: String, vararg args: Any?): Boolean =
        ru.privateserver.ksl.hooks.KSLHookReflectionCore.invokeIfPresent(target, methodName, *args)
}
