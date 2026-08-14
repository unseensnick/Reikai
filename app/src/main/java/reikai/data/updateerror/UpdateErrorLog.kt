package reikai.data.updateerror

import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import reikai.domain.library.ContentType
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.File

/** One entry that failed a library update, as the dump names it. */
data class UpdateErrorEntry(
    val title: String,
    val sourceName: String,
    val message: String,
)

/**
 * One content type's half of the dump, in Mihon's marker format: `! error`, `  # source`,
 * `    - title`. Empty when nothing failed, so a clean run leaves no header behind.
 */
fun updateErrorSectionText(label: String, entries: List<UpdateErrorEntry>): String {
    if (entries.isEmpty()) return ""
    return buildString {
        append("\n=== ").append(label).append(" ===\n")
        entries.groupBy { it.message }.forEach { (message, forMessage) ->
            append("\n! ").append(message).append('\n')
            forMessage.groupBy { it.sourceName }.forEach { (source, forSource) ->
                append("  # ").append(source).append('\n')
                forSource.forEach { append("    - ").append(it.title).append('\n') }
            }
        }
    }
}

/** The help line, then whichever sections have anything in them, in the order they were handed over. */
fun updateErrorLogText(help: String, sections: List<String>): String =
    help + "\n" + sections.joinToString(separator = "")

/**
 * The error dump shared by both update jobs. Each job rewrites only its own section, because the two
 * run on their own schedules and neither knows what the other found; a section is kept beside the dump
 * so the next writer can rebuild the whole file without parsing it back.
 */
class UpdateErrorLog(private val context: Context) {

    /** Replace [type]'s section (an empty [entries] clears it) and return the rebuilt dump. */
    fun write(type: ContentType, entries: List<UpdateErrorEntry>): File {
        val label = labelOf(type) ?: return File("")
        return synchronized(LOCK) {
            try {
                val section = updateErrorSectionText(context.stringResource(label), entries)
                sectionFile(type).run { if (section.isEmpty()) delete() else writeText(section) }

                val file = context.createFileInCacheDir(LOG_FILE_NAME)
                file.writeText(
                    updateErrorLogText(
                        help = context.stringResource(MR.strings.library_errors_help, Constants.URL_HELP),
                        sections = SECTION_ORDER.map { sectionFile(it).takeIf(File::exists)?.readText().orEmpty() },
                    ),
                )
                file
            } catch (_: Exception) {
                File("")
            }
        }
    }

    private fun sectionFile(type: ContentType): File =
        File(context.cacheDir, "update_errors_${type.name.lowercase()}.txt")

    private fun labelOf(type: ContentType): StringResource? = when (type) {
        ContentType.MANGA -> MR.strings.content_type_manga
        ContentType.NOVELS -> MR.strings.content_type_novels
        // The dump holds one section per real content type, so ALL names none of them.
        ContentType.ALL -> null
    }

    private companion object {
        const val LOG_FILE_NAME = "reikai_update_errors.txt"
        val SECTION_ORDER = listOf(ContentType.MANGA, ContentType.NOVELS)

        // Both jobs can be running at once, and each rebuilds the file from both sections.
        val LOCK = Any()
    }
}
