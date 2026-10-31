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
    check("long reads a number", params.long("radius", 0L) == 2L)
    check("bool falls back for a non-boolean", params.bool("name", true))
    check("bool reads a Boolean", mapOf<String, Any?>("on" to false).bool("on", true).not())
    check("str stringifies a value", params.str("radius", "?") == "2")
    check("str falls back for a missing key", params.str("missing", "d") == "d")
}

private fun testDialect() {
    println("KSLDialect / KSLDatabaseSettings / Migrations")
    val cols = listOf("script_name", "key", "value")
    val keys = listOf("script_name", "key")
    val sqlite = KSLDialect.SQLITE.upsert("ksl_persist", cols, keys)
    check("sqlite upsert uses ON CONFLICT", sqlite.contains("ON CONFLICT(`script_name`, `key`) DO UPDATE SET `value` = excluded.`value`"))
    val mysql = KSLDialect.MARIADB.upsert("ksl_persist", cols, keys)
    check("mariadb upsert uses ON DUPLICATE KEY", mysql.contains("ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)"))
    check("reserved word key is quoted in the column list", sqlite.contains("(`script_name`, `key`, `value`)") && mysql.contains("(`script_name`, `key`, `value`)"))
    check("one placeholder per column", sqlite.count { it == '?' } == 3)
    check("upsert rejects keys outside the columns", runCatching { KSLDialect.SQLITE.upsert("t", listOf("a", "b"), listOf("c")) }.isFailure)
    check("dialect names parse case-insensitively", KSLDialect.parse(" MariaDB ") == KSLDialect.MARIADB && KSLDialect.parse("postgres") == null)

    check("sqlite settings need no credentials", KSLDatabaseSettings.parse("sqlite").dialect == KSLDialect.SQLITE)
    val mysqlSettings = KSLDatabaseSettings.parse("mysql", host = "db.local", port = 3307, name = "ksl_prod", user = "ksl", password = "p@ss/word?")
    check("mysql url carries host, port, database and ssl mode", mysqlSettings.jdbcUrl() == "jdbc:mariadb://db.local:3307/ksl_prod?sslMode=disable&allowPublicKeyRetrieval=true")
    check("mariadb url has no MySQL-only option", KSLDatabaseSettings.parse("mariadb", user = "u").jdbcUrl() == "jdbc:mariadb://localhost:3306/ksl?sslMode=disable")
    check("the password never appears in describe()", !mysqlSettings.describe().contains("p@ss") && mysqlSettings.describe() == "MySQL ksl@db.local:3307/ksl_prod")
    check("remote settings require a user", runCatching { KSLDatabaseSettings.parse("mysql") }.isFailure)
    check("a database name with a URL separator is rejected", runCatching { KSLDatabaseSettings.parse("mysql", user = "u", name = "ksl?x=1") }.isFailure)
    check("an unknown ssl mode is rejected", runCatching { KSLDatabaseSettings.parse("mysql", user = "u", sslMode = "yes") }.isFailure)
    check("an unknown type is rejected", runCatching { KSLDatabaseSettings.parse("oracle") }.isFailure)
    check("pool size is clamped", KSLDatabaseSettings.parse("sqlite", poolSize = 500).poolSize == 32)

    val sqliteDdl = Migrations.statements(KSLDialect.SQLITE)
    val mysqlDdl = Migrations.statements(KSLDialect.MYSQL)
    check("same number of schema steps on every backend", sqliteDdl.size == mysqlDdl.size && sqliteDdl.size == 3)
    check("mysql DDL uses indexable binary-collated keys and InnoDB", mysqlDdl.all { it.contains("VARCHAR(190)") && it.contains("utf8mb4_bin") && it.contains("ENGINE=InnoDB") })
    check("sqlite DDL stays plain", sqliteDdl.none { it.contains("VARCHAR") || it.contains("ENGINE") })
    check("the reserved key column is quoted in DDL", mysqlDdl[1].contains("`key`") && sqliteDdl[1].contains("`key`"))
}

private fun testDiscordJson() {
    println("Discord JSON")
    check("quotes and backslashes are escaped", jsonString("a\"b\\c") == "\"a\\\"b\\\\c\"")
    check("newline, carriage return and tab are escaped", jsonString("1\n2\r3\t4") == "\"1\\n2\\r3\\t4\"")
    check("other control characters become \\u escapes", jsonString("a\u0001b\u001fc") == "\"a\\u0001b\\u001fc\"")
    check("non-ASCII text is kept as is", jsonString("Привет ✔") == "\"Привет ✔\"")
    check("an empty string is a pair of quotes", jsonString("") == "\"\"")
}

private fun testErrorCatalog() {
    println("KSLError catalog")
    val all = KSLError.entries
    val codes = all.map { it.code }
    check("codes are unique", codes.toSet().size == all.size)
    check("numbers are unique", all.map { it.number }.toSet().size == all.size)
    check("every code is four digits and a lowercase letter", codes.all { Regex("^[0-9]{4}[a-z]$").matches(it) })
    check("the letter is the category letter", all.all { it.code.last() == it.category.letter })
    check("category letters are unique", KSLErrorCategory.entries.map { it.letter }.toSet().size == KSLErrorCategory.entries.size)
    val numbers = all.map { it.number }.sorted()
    check("numbers are scattered: no two codes are neighbours", numbers.zipWithNext().all { (a, b) -> b - a >= 20 })
    check("numbers are not a 0001, 0002 sequence", numbers.first() >= 100)
    check("every code has a title and a hint", all.all { it.title.isNotBlank() && it.hint.isNotBlank() })
    val sample = KSLError.SCRIPT_COMPILE
    check("find accepts #, brackets and upper case", KSLError.find("#" + sample.code.uppercase()) == sample && KSLError.find("[" + sample.code + "]") == sample)
    check("find returns null for an unknown code", KSLError.find("0000z") == null)
    check("tag wraps the code in brackets", sample.tag == "[" + sample.code + "]")
}

private fun testScriptIds() {
    println("script ids, folders and requirements")
    val ids = listOf("main", "economy/shop", "economy/bank", "pvp/arena")
    check("an exact id resolves", KSLScriptOrder.resolve("economy/shop", ids) == "economy/shop")
    check("a bare name resolves when it is unique", KSLScriptOrder.resolve("arena", ids) == "pvp/arena")
    check("the .kts suffix and backslashes are tolerated", KSLScriptOrder.resolve("economy\\bank.kts", ids) == "economy/bank")
    check("an ambiguous bare name does not resolve", KSLScriptOrder.resolve("shop", listOf("a/shop", "b/shop")) == null)
    check("an unknown name does not resolve", KSLScriptOrder.resolve("nope", ids) == null)

    val warnings = mutableListOf<String>()
    val order = KSLScriptOrder.sortIds(listOf("b", "a", "c"), mapOf("a" to listOf("b")), { warnings += it })
    check("a requirement loads first, the rest keep their order", order == listOf("b", "a", "c") && warnings.isEmpty())
    val reversed = KSLScriptOrder.sortIds(listOf("a", "b", "c"), mapOf("a" to listOf("c")), { warnings += it })
    check("a dependent moves after its requirement", reversed == listOf("b", "c", "a"))
    val cyclic = KSLScriptOrder.sortIds(listOf("x", "y"), mapOf("x" to listOf("y"), "y" to listOf("x")), { warnings += it })
    check("a cycle keeps everything and warns with the catalog code", cyclic.toSet() == setOf("x", "y") && warnings.size == 1 && warnings[0].contains(KSLError.SCRIPT_REQUIRES_CYCLE.code))

    val tmp = java.nio.file.Files.createTempDirectory("kslfolders").toFile()
    File(tmp, "economy").mkdirs()
    val core = File(tmp, "core.kts").apply { writeText("println(1)") }
    val shop = File(tmp, "economy/shop.kts").apply { writeText("// @requires: core\nprintln(2)") }
    val idOf = { f: File -> f.relativeTo(tmp).invariantSeparatorsPath.removeSuffix(".kts") }
    val ordered = KSLScriptOrder.sort(listOf(shop, core), idOf, Logger.getLogger("test")).map(idOf)
    check("a script in a folder finds its requirement by bare name", ordered == listOf("core", "economy/shop"))
    tmp.deleteRecursively()
}

private fun testTextPlaceholders() {
    println("text placeholders")
    val values = mapOf("server" to "KSL", "rank" to "Admin")
    check("known tokens are replaced", KSLTextPlaceholders.apply("Welcome to @server@, @rank@!") { values[it] } == "Welcome to KSL, Admin!")
    check("tokens are case-insensitive", KSLTextPlaceholders.apply("@SERVER@") { values[it] } == "KSL")
    check("unknown tokens stay for the unmatched warning", KSLTextPlaceholders.apply("hi @nobody@") { values[it] } == "hi @nobody@")
    check("text without @ is returned as is", KSLTextPlaceholders.apply("plain text 100%") { "x" } == "plain text 100%")
    check("a value containing a dollar sign is inserted literally", KSLTextPlaceholders.apply("@a@") { "\$1" } == "\$1")
    check("built-in names are reserved", listOf("player", "world", "online", "maxplayers", "health", "ping").all { it in KSLTextPlaceholders.reserved })
}

private fun testValueCache() {
    println("KSLValueCache")
    val cache = KSLValueCache(3)
    check("an unread key is a miss", !cache.has("a"))
    cache.store("a", "i:1")
    check("a stored value is a hit", cache.has("a") && cache.value("a") == "i:1")
    cache.store("gone", null)
    check("a known-absent row is a hit with no value", cache.has("gone") && cache.value("gone") == null)
    val seen = cache.version()
    cache.store("a", "i:2")
    cache.fill("a", "i:1", seen)
    check("a read that started before a write cannot overwrite it", cache.value("a") == "i:2")
    cache.fill("b", "i:9", cache.version())
    check("an up-to-date read is cached", cache.value("b") == "i:9")
    cache.store("c", "i:3")
    check("the cache empties itself at the limit", cache.size() <= 3)
    cache.clear()
    check("clear removes everything", !cache.has("a") && cache.size() == 0)
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
    testDialect()
    testDiscordJson()
    testShapeParams()
    testDslTime()
    testScriptOrder()
    testErrorCatalog()
    testScriptIds()
    testTextPlaceholders()
    testValueCache()
    println()
    println("TOTAL: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
