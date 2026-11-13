package ru.privateserver.ksl

import org.bukkit.Bukkit
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Reflection bridge keeps optional integrations out of KSL's hard runtime dependencies. */
internal object KSLHookReflection {
    fun loadApi(pluginName: String, className: String): Any? = runCatching {
        val provider = Bukkit.getPluginManager().getPlugin(pluginName) ?: return@runCatching null
        val apiClass = provider.javaClass.classLoader.loadClass(className)
        invoke(apiClass, "getInstance") ?: return@runCatching null
    }.getOrNull()

    fun call(target: Any?, methodName: String, vararg args: Any?): Any? {
        if (target == null) return null
        val method = findMethod(target, methodName, args) ?: return null
        return method.invoke(if (target is Class<*>) null else target, *args)
    }

    fun callFirst(target: Any?, methodNames: List<String>, vararg args: Any?): Any? {
        if (target == null) return null
        for (name in methodNames) {
            val method = findMethod(target, name, args) ?: continue
            return method.invoke(if (target is Class<*>) null else target, *args)
        }
        return null
    }

    fun invokeIfPresent(target: Any?, methodName: String, vararg args: Any?): Boolean {
        if (target == null) return false
        val method = findMethod(target, methodName, args) ?: return false
        method.invoke(if (target is Class<*>) null else target, *args)
        return true
    }

    private fun findMethod(target: Any, name: String, args: Array<out Any?>): Method? {
        val type = if (target is Class<*>) target else target.javaClass
        return type.methods.firstOrNull { method ->
            method.name == name && method.parameterCount == args.size &&
                (target !is Class<*> || Modifier.isStatic(method.modifiers)) &&
                method.parameterTypes.zip(args).all { (parameter, argument) ->
                    if (argument == null) !parameter.isPrimitive
                    else boxed(parameter).isAssignableFrom(argument.javaClass)
                }
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }
}
