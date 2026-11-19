@file:Suppress("unused")

package ru.privateserver.ksl.api

import ru.privateserver.ksl.KSLAPI

/**
 * A named, runtime-checked event-bus topic.
 * Topic names are stable integration contracts. Prefer namespaced names and immutable payloads.
 */
class KSLTopic<T : Any> private constructor(
    val name: String,
    val payloadType: Class<T>
) {
    fun accepts(payload: Any?): Boolean = payload != null && payloadType.isInstance(payload)

    fun cast(payload: Any?): T {
        require(accepts(payload)) {
            "Payload for KSL topic '$name' must be ${payloadType.name}; got ${payload?.javaClass?.name ?: "null"}"
        }
        return payloadType.cast(payload)
    }

    override fun equals(other: Any?): Boolean =
        other is KSLTopic<*> && name == other.name && payloadType == other.payloadType

    override fun hashCode(): Int = 31 * name.hashCode() + payloadType.hashCode()
    override fun toString(): String = "KSLTopic(name='$name', payloadType=${payloadType.name})"

    companion object {
        @JvmStatic
        fun <T : Any> of(name: String, payloadType: Class<T>): KSLTopic<T> =
            KSLTopic(validateContractName(name, "topic"), payloadType)
    }
}

/** A typed service identifier with a runtime-checked service class. */
class KSLServiceKey<T : Any> private constructor(
    val name: String,
    val serviceType: Class<T>
) {
    fun cast(instance: Any?): T? =
        if (instance != null && serviceType.isInstance(instance)) serviceType.cast(instance) else null

    override fun equals(other: Any?): Boolean =
        other is KSLServiceKey<*> && name == other.name && serviceType == other.serviceType

    override fun hashCode(): Int = 31 * name.hashCode() + serviceType.hashCode()
    override fun toString(): String = "KSLServiceKey(name='$name', serviceType=${serviceType.name})"

    companion object {
        @JvmStatic
        fun <T : Any> of(name: String, serviceType: Class<T>): KSLServiceKey<T> =
            KSLServiceKey(validateContractName(name, "service key"), serviceType)
    }
}

private fun validateContractName(value: String, kind: String): String {
    require(value == value.trim() && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
        "Invalid KSL $kind name '$value'; use 1-128 letters, digits, '.', '_', ':' or '-'"
    }
    return value
}

/** Creates a typed topic using Kotlin reified type information. */
inline fun <reified T : Any> kslTopic(name: String): KSLTopic<T> =
    KSLTopic.of(name, T::class.java)

/** Creates a typed service key using Kotlin reified type information. */
inline fun <reified T : Any> kslServiceKey(name: String): KSLServiceKey<T> =
    KSLServiceKey.of(name, T::class.java)

/** Subscribes to a typed topic; wrong payloads published through the legacy bus are rejected. */
fun <T : Any> KSLAPI.subscribe(topic: KSLTopic<T>, handler: (T) -> Unit): AutoCloseable =
    subscribe(topic.name) { payload -> handler(topic.cast(payload)) }

/** Publishes a value to its typed topic while retaining the legacy bus semantics. */
fun <T : Any> KSLAPI.publish(topic: KSLTopic<T>, payload: T): Int =
    publish(topic.name, payload)

/** Registers a service against a typed key after validating its runtime type. */
fun <T : Any> KSLAPI.registerService(key: KSLServiceKey<T>, instance: T) {
    require(key.serviceType.isInstance(instance)) {
        "Service for '${key.name}' must be ${key.serviceType.name}"
    }
    registerService(key.name, instance)
}

/** Looks up a service through its runtime-checked type contract. */
fun <T : Any> KSLAPI.service(key: KSLServiceKey<T>): T? =
    getService(key.name, key.serviceType)

/** Returns a required typed service or reports the missing key and expected type. */
fun <T : Any> KSLAPI.requireService(key: KSLServiceKey<T>): T =
    service(key) ?: error("KSL service '${key.name}' (${key.serviceType.name}) is not registered")

/** Removes a service registered under the supplied typed key. */
fun KSLAPI.unregisterService(key: KSLServiceKey<*>) {
    unregisterService(key.name)
}

/** Subscribes to a typed topic and binds the subscription to this scope's lifetime. */
fun <T : Any> KSLAddonScope.subscribe(topic: KSLTopic<T>, handler: (T) -> Unit): AutoCloseable =
    subscribe(topic.name) { payload -> handler(topic.cast(payload)) }

/** Creates a child scope; the parent closes children in reverse creation order. */
fun KSLAddonScope.childScope(): KSLAddonScope = child()
