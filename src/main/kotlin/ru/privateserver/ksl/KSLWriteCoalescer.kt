package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Merges repeated writes to the same key that are still waiting in the DB queue into a single write of the newest value.
// A counter updated every tick would otherwise queue twenty identical UPDATEs per second.
// barrier() separates writes that must not be merged across (a delete in between): the later ones get their own write.
class KSLWriteCoalescer {

    class Taken(val value: String?)

    private val pending = ConcurrentHashMap<String, Any>()
    private val epoch = AtomicInteger()

    private companion object {
        // ConcurrentHashMap cannot hold null, and a null value is a legitimate thing to write.
        val NULL = Any()
    }

    // Records the newest value. Returns a ticket when a write must be queued, null when one is already queued
    // for this key (it will pick the newest value up when it runs).
    fun offer(key: String, value: String?): String? {
        val ticket = "${epoch.get()}\u0000$key"
        return if (pending.put(ticket, value ?: NULL) == null) ticket else null
    }

    // Called by the queued write; null if nothing is left to write.
    fun take(ticket: String): Taken? {
        val value = pending.remove(ticket) ?: return null
        return Taken(if (value === NULL) null else value as String)
    }

    fun barrier() {
        epoch.incrementAndGet()
    }

    fun pendingCount(): Int = pending.size
}
