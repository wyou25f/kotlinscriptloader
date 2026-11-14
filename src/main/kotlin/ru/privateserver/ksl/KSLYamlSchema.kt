package ru.privateserver.ksl

import java.math.BigInteger

import org.bukkit.configuration.file.YamlConfiguration

/** A single validation result. corrected is true when applyDefaults replaced the value. */
data class KSLYamlValidationIssue(
    val path: String,
    val message: String,
    val corrected: Boolean = false
)

/** A compact schema DSL for script YAML. Invalid values are reported, not coerced. */
class KSLYamlSchema {
    private data class Rule(
        val path: String,
        val defaultValue: Any,
        val accepts: (Any?) -> Boolean,
        val description: String
    )

    private val rules = mutableListOf<Rule>()

    private fun add(path: String, default: Any, description: String, accepts: (Any?) -> Boolean) {
        require(path.isNotBlank()) { "Schema path must not be blank" }
        require(rules.none { it.path == path }) { "Duplicate YAML schema path: $path" }
        rules += Rule(path, default, accepts, description)
    }

    fun string(path: String, default: String) =
        add(path, default, "string") { it is String }

    fun int(path: String, default: Int, min: Int? = null, max: Int? = null) {
        require(min == null || max == null || min <= max) { "min must be <= max" }
        add(path, default, "integer${range(min, max)}") {
            val number = integerValue(it)
            number != null &&
                number >= (min?.toLong() ?: Int.MIN_VALUE.toLong()) &&
                number <= (max?.toLong() ?: Int.MAX_VALUE.toLong())
        }
    }

    fun long(path: String, default: Long, min: Long? = null, max: Long? = null) {
        require(min == null || max == null || min <= max) { "min must be <= max" }
        add(path, default, "long${range(min, max)}") {
            val number = integerValue(it)
            number != null && (min == null || number >= min) && (max == null || number <= max)
        }
    }

    fun double(path: String, default: Double, min: Double? = null, max: Double? = null) {
        require(default.isFinite()) { "Default value must be finite" }
        require(min == null || min.isFinite())
        require(max == null || max.isFinite())
        require(min == null || max == null || min <= max) { "min must be <= max" }
        add(path, default, "number${range(min, max)}") {
            it is Number && it.toDouble().isFinite() &&
                (min == null || it.toDouble() >= min) && (max == null || it.toDouble() <= max)
        }
    }

    fun bool(path: String, default: Boolean) =
        add(path, default, "boolean") { it is Boolean }

    fun strings(path: String, default: List<String>) =
        add(path, default, "list of strings") { value ->
            value is List<*> && value.all { it is String }
        }

    fun validate(yaml: YamlConfiguration, applyDefaults: Boolean = true): List<KSLYamlValidationIssue> {
        val issues = mutableListOf<KSLYamlValidationIssue>()
        for (rule in rules) {
            val exists = yaml.contains(rule.path)
            val value = yaml.get(rule.path)
            if (!exists) {
                if (applyDefaults) yaml.set(rule.path, rule.defaultValue)
                issues += KSLYamlValidationIssue(rule.path, "Missing value; expected ${rule.description}", applyDefaults)
            } else if (!rule.accepts(value)) {
                if (applyDefaults) yaml.set(rule.path, rule.defaultValue)
                issues += KSLYamlValidationIssue(
                    rule.path,
                    "Invalid value type or range; expected ${rule.description}",
                    applyDefaults
                )
            }
        }
        return issues
    }

    /**
     * YAML may deserialize integral values as Int, Long or BigInteger depending on magnitude.
     * Accept only exact integral values; never truncate floating-point values or overflow.
     */
    private fun integerValue(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is BigInteger -> runCatching { value.longValueExact() }.getOrNull()
        else -> null
    }

    private fun range(min: Number?, max: Number?): String = when {
        min != null && max != null -> " in [$min, $max]"
        min != null -> " >= $min"
        max != null -> " <= $max"
        else -> ""
    }
}
