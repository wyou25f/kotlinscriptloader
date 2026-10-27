package ru.privateserver.ksl.dsl

// Kept free of Bukkit imports so plain tests can use it without a server classpath.
val Int.sec: Long get() = this * 20L
val Long.sec: Long get() = this * 20L
val Int.mins: Long get() = this * 1200L
