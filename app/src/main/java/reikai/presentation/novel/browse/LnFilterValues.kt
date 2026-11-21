package reikai.presentation.novel.browse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** A Checkbox filter's checked option values, read from the string array the plugin reads. */
internal fun checkedValues(value: JsonElement?): Set<String> =
    (value as? JsonArray)?.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

/** A Checkbox filter's value for the options [checked]. */
internal fun checkboxValue(checked: Collection<String>): JsonArray = JsonArray(checked.map(::JsonPrimitive))

/** An ExcludableCheckboxGroup filter's value; the plugin reads both lists, so both are always written. */
internal data class IncludeExclude(val include: Set<String>, val exclude: Set<String>) {

    fun toJson(): JsonObject = buildJsonObject {
        put("include", checkboxValue(include))
        put("exclude", checkboxValue(exclude))
    }

    companion object {
        fun of(value: JsonElement?): IncludeExclude {
            val obj = value as? JsonObject
            return IncludeExclude(
                include = checkedValues(obj?.get("include")),
                exclude = checkedValues(obj?.get("exclude")),
            )
        }
    }
}

/**
 * A MultiText filter's value: each entry split at line breaks and trimmed, blanks and repeats dropped, as
 * LNReader's `addMultiTextValues`. The plugin reads the string array as is, so it owns any further format.
 */
internal fun multiTextValue(values: List<String>): JsonArray =
    checkboxValue(values.flatMap { it.split('\n') }.map(String::trim).filter(String::isNotEmpty).distinct())

/**
 * The filter types the sheet draws, by the type string the host's `FilterTypes` hands a plugin (`lnhost/headless.js`).
 * A type outside these reads as null, and the sheet shows it as unsupported rather than dropping it.
 */
internal enum class LnFilterType(private vararg val wire: String) {
    Toggle("Switch", "XCheckbox"),
    Text("TextInput", "Text"),
    Picker("Picker"),
    Checkbox("Checkbox"),
    ExcludableCheckboxGroup("ExcludableCheckboxGroup"),
    MultiText("MultiText"),
    ;

    companion object {
        fun of(type: String?): LnFilterType? = entries.firstOrNull { type in it.wire }
    }
}
