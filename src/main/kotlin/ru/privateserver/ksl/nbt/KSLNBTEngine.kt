package ru.privateserver.ksl.nbt

import de.tr7zw.changeme.nbtapi.NBT

/** Resource limits applied before KSL asks Item-NBT-API to parse SNBT input. */
data class KSLNBTLimits(
    val maxSnbtCharacters: Int = 1_048_576,
    val maxNestingDepth: Int = 64,
    val maxEntries: Int = 65_536
) {
    init {
        require(maxSnbtCharacters in 1..8_388_608) { "maxSnbtCharacters must be in 1..8388608" }
        require(maxNestingDepth in 1..256) { "maxNestingDepth must be in 1..256" }
        require(maxEntries in 1..1_000_000) { "maxEntries must be in 1..1000000" }
    }

    companion object {
        val DEFAULT = KSLNBTLimits()
    }
}

/**
 * Conservative SNBT resource preflight. Item-NBT-API remains responsible for
 * syntax parsing; this scanner bounds input size, container depth and entry count.
 */
object KSLNBTSnbtPolicy {
    fun validate(snbt: String, limits: KSLNBTLimits = KSLNBTLimits.DEFAULT) {
        require(snbt.isNotBlank()) { "SNBT input must not be blank" }
        require(snbt.length <= limits.maxSnbtCharacters) {
            "SNBT input exceeds ${limits.maxSnbtCharacters} characters"
        }

        val expectedClosers = ArrayDeque<Char>()
        var quote: Char? = null
        var escaped = false
        var entries = 1

        snbt.forEachIndexed { index, char ->
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                return@forEachIndexed
            }

            when (char) {
                '"', '\'' -> quote = char
                '{' -> {
                    if (expectedClosers.isNotEmpty()) {
                        entries++
                        require(entries <= limits.maxEntries) {
                            "SNBT contains more than ${limits.maxEntries} entries"
                        }
                    }
                    expectedClosers.addLast('}')
                    require(expectedClosers.size <= limits.maxNestingDepth) {
                        "SNBT nesting exceeds ${limits.maxNestingDepth} levels near character $index"
                    }
                }
                '[' -> {
                    if (expectedClosers.isNotEmpty()) {
                        entries++
                        require(entries <= limits.maxEntries) {
                            "SNBT contains more than ${limits.maxEntries} entries"
                        }
                    }
                    expectedClosers.addLast(']')
                    require(expectedClosers.size <= limits.maxNestingDepth) {
                        "SNBT nesting exceeds ${limits.maxNestingDepth} levels near character $index"
                    }
                }
                '}', ']' -> {
                    require(expectedClosers.removeLastOrNull() == char) {
                        "SNBT contains an unexpected '$char' near character $index"
                    }
                }
                ',' -> if (expectedClosers.isNotEmpty()) {
                    entries++
                    require(entries <= limits.maxEntries) {
                        "SNBT contains more than ${limits.maxEntries} entries"
                    }
                }
            }
        }

        require(quote == null) { "SNBT contains an unterminated quoted string" }
        require(expectedClosers.isEmpty()) { "SNBT contains an unterminated compound or list" }
    }
}

/** Safe dot-path parser for nested compound access. Array/list indices use their explicit APIs. */
internal object KSLNBTPathPolicy {
    private val SEGMENT = Regex("""^[A-Za-z0-9_:/-]{1,128}$""")

    fun parse(path: String): List<String> {
        require(path.length in 1..512) { "NBT path must contain 1..512 characters" }
        val segments = path.split('.')
        require(segments.size <= 32 && segments.all(SEGMENT::matches)) {
            "Invalid NBT path '$path'. Use 1..32 dot-separated keys containing letters, digits, '_', ':', '/' or '-'."
        }
        return segments
    }
}

/**
 * Detached, copy-on-write NBT document for building and transforming compounds
 * without mutating a live item/entity/block. Failed edits leave the previous SNBT intact.
 */
class KSLNBTDocument private constructor(
    private var sourceSnbt: String,
    private val limits: KSLNBTLimits
) {
    @Synchronized
    fun snbt(): String = sourceSnbt

    @Synchronized
    fun copy(): KSLNBTDocument = KSLNBTEngine.parse(sourceSnbt, limits)

    /** The callback receives a detached read-only view. Do not retain it after the callback. */
    @Synchronized
    fun <T> read(block: KSLNBTView.() -> T): T =
        KSLNBTEngine.parseCompound(sourceSnbt, limits).asReadOnlyView().block()

    /**
     * Edits a detached working copy and publishes it only if the block completes.
     * An exception leaves this document unchanged.
     */
    @Synchronized
    fun <T> edit(block: KSLNBTCompound.() -> T): T {
        val working = KSLNBTEngine.parseCompound(sourceSnbt, limits)
        val result = working.block()
        val candidate = working.snbt()
        KSLNBTSnbtPolicy.validate(candidate, limits)
        sourceSnbt = candidate
        return result
    }

    @Synchronized
    fun merge(snbt: String) {
        KSLNBTSnbtPolicy.validate(snbt, limits)
        edit { mergeValidated(snbt, limits) }
    }

    companion object {
        internal fun fromCanonical(snbt: String, limits: KSLNBTLimits) =
            KSLNBTDocument(snbt, limits)
    }
}

/** Factory and parser for detached KSL NBT documents. */
object KSLNBTEngine {
    fun empty(limits: KSLNBTLimits = KSLNBTLimits.DEFAULT): KSLNBTDocument =
        KSLNBTDocument.fromCanonical("{}", limits)

    fun parse(snbt: String, limits: KSLNBTLimits = KSLNBTLimits.DEFAULT): KSLNBTDocument {
        val parsed = parseCompound(snbt, limits)
        return KSLNBTDocument.fromCanonical(parsed.snbt(), limits)
    }

    internal fun parseCompound(snbt: String, limits: KSLNBTLimits): KSLNBTCompound {
        KSLNBTSnbtPolicy.validate(snbt, limits)
        return KSLNBTCompound(NBT.parseNBT(snbt))
    }
}
