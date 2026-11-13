package ru.privateserver.ksl.dsl

// Kept free of Bukkit imports so plain tests can use it without a server classpath.
// Math.multiplyExact prevents silent overflow when scripts pass unexpectedly large values.
val Int.sec: Long get() = Math.multiplyExact(toLong(), 20L)
val Long.sec: Long get() = Math.multiplyExact(this, 20L)
val Int.mins: Long get() = Math.multiplyExact(toLong(), 1_200L)
val Long.mins: Long get() = Math.multiplyExact(this, 1_200L)
val Int.hours: Long get() = Math.multiplyExact(toLong(), 72_000L)
val Long.hours: Long get() = Math.multiplyExact(this, 72_000L)
