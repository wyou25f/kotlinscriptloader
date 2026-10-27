package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class KSLEventBus(private val onError: (topic: String, error: Throwable) -> Unit) {

    private class Subscription(val owner: String?, val handler: (Any?) -> Unit)

    private val topics = ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>>()

    fun subscribe(owner: String?, topic: String, handler: (Any?) -> Unit): AutoCloseable {
        val subscription = Subscription(owner, handler)
        topics.computeIfAbsent(topic) { CopyOnWriteArrayList() }.add(subscription)
        return AutoCloseable { remove(topic) { it === subscription } }
    }

    fun publish(topic: String, payload: Any?): Int {
        val subscriptions = topics[topic] ?: return 0
        var delivered = 0
        for (subscription in subscriptions) {
            try {
                subscription.handler(payload)
                delivered++
            } catch (ex: Throwable) {
                onError(topic, ex)
            }
        }
        return delivered
    }

    fun clearOwner(owner: String) {
        topics.keys.toList().forEach { topic -> remove(topic) { it.owner == owner } }
    }

    fun clear() {
        topics.clear()
    }

    // Drops empty topics so one-off topic names do not accumulate.
    private fun remove(topic: String, predicate: (Subscription) -> Boolean) {
        topics.computeIfPresent(topic) { _, list ->
            list.removeIf(predicate)
            if (list.isEmpty()) null else list
        }
    }
}
