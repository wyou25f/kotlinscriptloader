package ru.privateserver.ksl

/**
 * Keeps a table operation's ordering marker and database-queue submission indivisible
 * with respect to other operations on the same table.
 */
internal class KSLTableOperationGate {
    private val monitor = Any()

    fun <T> withLock(action: () -> T): T = synchronized(monitor, action)
}
