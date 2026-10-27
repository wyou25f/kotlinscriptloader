package ru.privateserver.ksl

import ru.privateserver.ksl.dsl.mins
import ru.privateserver.ksl.dsl.sec
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
    check("zero-second cooldown never blocks", store.tryUse("z", 0) && store.tryUse("z", 0))
    store.clearScript("s1")
    store.tryUse("s1:a:p", 5)
    store.clearScript("s1")
    check("clearScript drops that script's keys", store.tryUse("s1:a:p", 5))
}

private fun testSandboxRules() {
    println("KSLSandboxRules")
    fun hits(src: String) = KSLSandboxRules.scanSource(src).map { it.pattern }

    check("plain call is flagged", "Runtime.getRuntime" in hits("Runtime.getRuntime().exec(\"ls\")"))
    check("code after a leading block comment is still scanned", hits("/* hi */ Runtime.getRuntime()").isNotEmpty())
    check("backticked member is flagged", hits("Runtime.`getRuntime`()").isNotEmpty())
    check("call split over lines is flagged", hits("Runtime\n    .getRuntime()").isNotEmpty())
    check("lowercase classLoader is flagged", hits("val l = javaClass.classLoader").isNotEmpty())
    check("'//' inside a string does not hide the rest of the line", hits("val u = \"http://x\"; System.exit(0)").isNotEmpty())
    check("code inside a string template is flagged", hits("val s = \"\${Runtime.getRuntime()}\"").isNotEmpty())
    check("line comment is ignored", hits("// Runtime.getRuntime()").isEmpty())
    check("block comment is ignored", hits("/* System.exit(0) */\nval x = 1").isEmpty())
    check("nested block comment is ignored", hits("/* a /* b */ System.exit(0) */").isEmpty())
    check("code after a closed nested comment is scanned", hits("/* a /* b */ c */ System.exit(0)").isNotEmpty())
    check("clean script has no violations", hits("on<PlayerJoinEvent> { player.sendMessage(\"hi\") }").isEmpty())
    val line = KSLSandboxRules.scanSource("val a = 1\n// note\nSystem.exit(0)\n").firstOrNull()?.line
    check("violation reports the right line", line == 3)
}

private fun testEventBus() {
    println("KSLEventBus")
    val errors = mutableListOf<String>()
    val bus = KSLEventBus { topic, _ -> errors += topic }
    val seen = mutableListOf<Any?>()

    check("publish without subscribers delivers 0", bus.publish("none", 1) == 0)
    val sub = bus.subscribe("addon-a", "t") { seen += it }
    check("subscriber receives payload", bus.publish("t", "x") == 1 && seen == listOf<Any?>("x"))
    bus.subscribe("addon-b", "t") { throw IllegalStateException("boom") }
    check("a throwing handler does not block the others", bus.publish("t", "y") == 1 && seen.size == 2 && errors == listOf("t"))
    sub.close()
    bus.publish("t", "z")
    check("closed subscription gets nothing", seen.size == 2)
    bus.clearOwner("addon-b")
    check("clearOwner removes that owner's handlers", bus.publish("t", "w") == 0)
    bus.subscribe(null, "u") { seen += it }
    bus.clearOwner("addon-a")
    check("ownerless subscription survives clearOwner", bus.publish("u", 1) == 1)
}

private fun testDslTime() {
    println("dsl time helpers")
    check("5.sec is 100 ticks", 5.sec == 100L)
    check("2.mins is 2400 ticks", 2.mins == 2400L)
}

private fun testOwnedRegistry() {
    println("KSLOwnedRegistry")
    val registry = KSLOwnedRegistry<String>()
    registry.put("a", "1", "addon-x")
    registry.put("b", "2", "addon-x")
    registry.put("c", "3", null)
    check("get returns the stored value", registry.get("a") == "1" && registry.ownerOf("a") == "addon-x")
    registry.put("a", "9", null)
    check("an ownerless overwrite clears the stale owner", registry.ownerOf("a") == null && registry.get("a") == "9")
    check("releaseOwner drops only that owner's entries", registry.releaseOwner("addon-x") == 1 && registry.get("b") == null)
    check("ownerless entries survive releaseOwner", registry.get("a") == "9" && registry.get("c") == "3")
    registry.remove("c")
    check("remove deletes the entry", registry.get("c") == null && registry.keys() == setOf("a"))
}

private fun testShapeParams() {
    println("shape params")
    val params = mapOf<String, Any?>("radius" to 2, "height" to 1.5, "name" to "x")
    check("num reads an Int as Double", params.num("radius", 0.0) == 2.0)
    check("num reads a Double", params.num("height", 0.0) == 1.5)
    check("num falls back for a missing key", params.num("missing", 7.0) == 7.0)
    check("num falls back for a non-number", params.num("name", 3.0) == 3.0)
    check("int truncates a number", params.int("height", 0) == 1)
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
    testSandboxRules()
    testEventBus()
    testOwnedRegistry()
    testShapeParams()
    testDslTime()
    testScriptOrder()
    println()
    println("TOTAL: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
