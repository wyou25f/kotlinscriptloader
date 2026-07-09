package ru.privateserver.ksl

import java.io.File
import java.util.logging.Logger

private var passed = 0
private var failed = 0

private fun check(name: String, condition: Boolean) {
    if (condition) {
        passed++
        println("  OK   $name")
    } else {
        failed++
        println("  FAIL $name")
    }
}

private fun testPersistCodec() {
    println("KSLPersistCodec")
    check("encode/decode String", KSLPersistCodec.decode(KSLPersistCodec.encode("hello")) == "hello")
    check("encode/decode Int", KSLPersistCodec.decode(KSLPersistCodec.encode(42)) == 42)
    check("encode/decode negative Int", KSLPersistCodec.decode(KSLPersistCodec.encode(-17)) == -17)
    check("encode/decode Long", KSLPersistCodec.decode(KSLPersistCodec.encode(123456789012345L)) == 123456789012345L)
    check("encode/decode Double", KSLPersistCodec.decode(KSLPersistCodec.encode(3.14)) == 3.14)
    check("encode/decode Boolean true", KSLPersistCodec.decode(KSLPersistCodec.encode(true)) == true)
    check("encode/decode Boolean false", KSLPersistCodec.decode(KSLPersistCodec.encode(false)) == false)
    check("encode/decode null", KSLPersistCodec.decode(KSLPersistCodec.encode(null)) == null)
    check("encode/decode empty String", KSLPersistCodec.decode(KSLPersistCodec.encode("")) == "")
    check("encode/decode String with colon", KSLPersistCodec.decode(KSLPersistCodec.encode("a:b:c")) == "a:b:c")
    check("encode unsupported type (List) returns null", KSLPersistCodec.encode(listOf(1, 2, 3)) == null)
    check("decode null raw returns null", KSLPersistCodec.decode(null) == null)
    check("decode garbage returns null", KSLPersistCodec.decode("garbage-no-colon") == null)
    check("decode empty string returns null", KSLPersistCodec.decode("") == null)
    check("decode corrupted int body returns null", KSLPersistCodec.decode("i:not_a_number") == null)
    check("decode unknown type tag returns null", KSLPersistCodec.decode("x:123") == null)
}

private fun testCooldownStore() {
    println("KSLCooldownStore")
    val store = KSLCooldownStore()
    check("first use is allowed", store.tryUse("k1", 5))
    check("immediate second use is blocked", !store.tryUse("k1", 5))
    check("remaining is > 0 right after use", store.remaining("k1", 5) > 0)
    check("remaining is <= seconds", store.remaining("k1", 5) <= 5)
    check("different key is independent", store.tryUse("k2", 5))
    store.reset("k1")
    check("reset allows immediate reuse", store.tryUse("k1", 5))
    check("remaining for never-used key is 0", store.remaining("brand-new-key", 5) == 0L)
}

private fun testScriptOrder() {
    println("KSLScriptOrder")
    val logger = Logger.getLogger("test")
    val tmp = java.nio.file.Files.createTempDirectory("ksltest").toFile()

    fun script(name: String, requires: String? = null): File {
        val f = File(tmp, "$name.kts")
        f.writeText(if (requires != null) "// @requires: $requires\nprintln(\"$name\")" else "println(\"$name\")")
        return f
    }

    run {
        val a = script("a")
        val b = script("b", requires = "a")
        val c = script("c", requires = "b")
        val ordered = KSLScriptOrder.sort(arrayOf(c, b, a), logger).map { it.nameWithoutExtension }
        check("simple chain a->b->c orders correctly", ordered == listOf("a", "b", "c"))
    }

    run {
        val x = script("x")
        val y = script("y")
        val ordered = KSLScriptOrder.sort(arrayOf(x, y), logger).map { it.nameWithoutExtension }
        check("no dependencies keeps original order", ordered == listOf("x", "y"))
    }

    run {
        val p = script("p", requires = "q")
        val q = script("q", requires = "p")
        val ordered = KSLScriptOrder.sort(arrayOf(p, q), logger).map { it.nameWithoutExtension }.toSet()
        check("cycle does not crash and returns all scripts", ordered == setOf("p", "q"))
    }

    run {
        val onlyOne = script("onlyone", requires = "missing_dependency")
        val ordered = KSLScriptOrder.sort(arrayOf(onlyOne), logger).map { it.nameWithoutExtension }
        check("missing dependency is ignored, script still loads", ordered == listOf("onlyone"))
    }

    run {
        val base = script("base")
        val dependent = script("dependent", requires = "base")
        val independent = script("independent")
        val ordered = KSLScriptOrder.sort(arrayOf(dependent, independent, base), logger).map { it.nameWithoutExtension }
        check("base loads before dependent even if listed after", ordered.indexOf("base") < ordered.indexOf("dependent"))
    }

    tmp.deleteRecursively()
}

fun main() {
    testPersistCodec()
    testCooldownStore()
    testScriptOrder()
    println()
    println("TOTAL: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
