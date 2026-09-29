package reikai.data.updateerror

import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
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

/** A job's part of the dump, in the order the dump lists them. */
enum class UpdateErrorSection(val label: StringResource) {
    MANGA(MR.strings.content_type_manga),
    GALLERIES(MR.strings.gallery_update_checker),
    NOVELS(MR.strings.content_type_novels),
}

/**
 * One section of the dump, in Mihon's marker format: `! error`, `  # source`, `    - title`. Empty
 * when nothing failed, so a clean run leaves no header behind.
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
 * The error dump every update job shares. Each job rewrites only its own section, because the jobs run
 * on their own schedules and none knows what the others found; a section is kept beside the dump so the
 * next writer can rebuild the whole file without parsing it back.
 */
class UpdateErrorLog(private val context: Context) {

    /** Replace [section]'s part (an empty [entries] clears it) and return the rebuilt dump. */
    fun write(section: UpdateErrorSection, entries: List<UpdateErrorEntry>): File = synchronized(LOCK) {
        try {
            val text = updateErrorSectionText(context.stringResource(section.label), entries)
            sectionFile(section).run { if (text.isEmpty()) delete() else writeText(text) }

            val file = context.createFileInCacheDir(LOG_FILE_NAME)
            file.writeText(
                updateErrorLogText(
                    help = context.stringResource(MR.strings.library_errors_help, Constants.URL_HELP),
                    sections = UpdateErrorSection.entries.map {
                        sectionFile(it).takeIf(File::exists)?.readText().orEmpty()
                    },
                ),
            )
            file
        } catch (_: Exception) {
            File("")
        }
    }

    private fun sectionFile(section: UpdateErrorSection): File =
        File(context.cacheDir, "update_errors_${section.name.lowercase()}.txt")

    private companion object {
        const val LOG_FILE_NAME = "reikai_update_errors.txt"

        // The jobs can be running at once, and each rebuilds the file from every section.
        val LOCK = Any()
    }
}
