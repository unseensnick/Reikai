package reikai.novel.content

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Collections
import java.util.LinkedHashMap

object NovelRegexReplacements {

    /**
     * A rule ready to run. [literal] is the difference between the two modes: a regex rule's
     * replacement may reference groups with `$1`, while a find-and-replace-text rule's is taken
     * exactly as typed, so a price like `$5` does not read as a group and fail the whole rule.
     */
    data class Compiled(val regex: Regex, val replacement: String, val literal: Boolean) {
        fun applyTo(input: String): String =
            if (literal) regex.replace(input) { replacement } else regex.replace(input, replacement)
    }

    /** The pattern compiled but its replacement cannot be read, so the editor marks the Replace field. */
    class InvalidReplacementException(cause: Exception) : IllegalArgumentException(cause.message, cause)

    // Compiling a rule set costs more than running it and the same set is re-applied to every
    // chapter, so the compiled form is cached against the raw JSON. Access-ordered and capped, so
    // editing rules mid-session cannot grow it without bound.
    private val cache: MutableMap<String, List<Compiled>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, List<Compiled>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<Compiled>>,
                ) = size > 16
            },
        )

    /**
     * Compiles one rule. Throws whatever the pattern failed with, or [InvalidReplacementException] for
     * a regex replacement, so the editor can name the problem; the pipeline catches and skips the
     * rule instead. Both go through here, or a rule could behave one way when tested and another way
     * when read.
     */
    fun compile(rule: NovelRegexReplacement): Compiled {
        val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        if (rule.isRegex) {
            val regex = Regex(rule.pattern, options)
            checkReplacement(regex, rule.replacement)
            return Compiled(regex, rule.replacement, literal = false)
        }
        val escaped = Regex.escape(rule.pattern)
        val bounded = if (rule.matchWholeWord) {
            "(?<![\\p{L}\\p{N}_])(?:$escaped)(?![\\p{L}\\p{N}_])"
        } else {
            escaped
        }
        return Compiled(Regex(bounded, options), rule.replacement, literal = true)
    }

    fun apply(content: String, rulesJson: String): String {
        if (rulesJson.isBlank() || rulesJson == "[]") return content

        val compiled = synchronized(cache) {
            cache.computeIfAbsent(rulesJson) { json ->
                NovelRegexRules.decode(json).mapNotNull { rule ->
                    if (!rule.enabled || rule.pattern.isBlank()) return@mapNotNull null
                    try {
                        compile(rule)
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Failed to compile regex for '${rule.title}'" }
                        null
                    }
                }
            }
        }

        var result = content
        for (rule in compiled) {
            try {
                result = rule.applyTo(result)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Regex replacement failed" }
            }
        }
        return result
    }

    private val groupNameRegex = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")

    /**
     * The runtime reads a replacement only when something matches, so it is run once against a
     * stand-in pattern with the same groups that matches the empty string. The runtime judges its own
     * grammar that way. The name scan can only over-find (a name inside a character class), which
     * accepts a rule the reader then skips, never refuses a valid one.
     */
    private fun checkReplacement(regex: Regex, replacement: String) {
        val names = groupNameRegex.findAll(regex.pattern).map { it.groupValues[1] }.distinct().toList()
        val unnamed = (regex.toPattern().matcher("").groupCount() - names.size).coerceAtLeast(0)
        val standIn = names.joinToString("") { "(?<$it>)" } + "()".repeat(unnamed)
        try {
            Regex(standIn).replace("", replacement)
        } catch (e: RuntimeException) {
            // An unknown group number throws IndexOutOfBoundsException, the rest IllegalArgumentException.
            throw InvalidReplacementException(e)
        }
    }
}
