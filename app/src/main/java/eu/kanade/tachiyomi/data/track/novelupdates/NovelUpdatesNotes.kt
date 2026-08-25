package eu.kanade.tachiyomi.data.track.novelupdates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * NovelUpdates stores no reading position, so progress lives inside the user's own free-text note
 * and every update is a read-modify-write over something a person typed.
 *
 * The note is therefore decoded as real JSON rather than captured with a regex. The reference fork
 * matches `"notes"\s*:\s*"([^"]+)"`, which stops at the first escaped quote, so a note containing
 * one is truncated and the truncation written back; it also treats a failed parse as an empty note
 * and replaces the whole thing. Both are silent data loss, and neither is reproduced here.
 */
@Serializable
data class NovelUpdatesNotes(
    val notes: String = "",
    val tags: String = "",
)

private const val PROGRESS_LABEL = "total chapters read: "

private const val LINE_BREAK = "<br/>"

private val PROGRESS = Regex("""total\s+chapters\s+read:\s*(\d+)""", RegexOption.IGNORE_CASE)

/**
 * The ajax endpoint answers with JSON followed by the `0` WordPress's `die()` appends, so the body
 * is cut at its last brace. Returns null when nothing parses, which callers must treat as "do not
 * write" rather than as an empty note.
 */
internal fun parseNotesPayload(body: String, json: Json): NovelUpdatesNotes? {
    val objectText = body.substringBeforeLast('}', missingDelimiterValue = "")
        .ifEmpty { return null } + "}"
    return runCatching { json.decodeFromString<NovelUpdatesNotes>(objectText) }.getOrNull()
}

internal fun progressFrom(notes: String): Int? =
    PROGRESS.find(notes)?.groupValues?.get(1)?.toIntOrNull()

/**
 * The note with its progress line set to [chapters], leaving every other character untouched: an
 * existing line is rewritten in place, and a note without one keeps its text and gains a line.
 */
internal fun notesWithProgress(notes: String, chapters: Int): String {
    val line = "$PROGRESS_LABEL$chapters"
    return when {
        PROGRESS.containsMatchIn(notes) -> PROGRESS.replace(notes, line)
        notes.isBlank() -> line
        else -> "$notes$LINE_BREAK$line"
    }
}
