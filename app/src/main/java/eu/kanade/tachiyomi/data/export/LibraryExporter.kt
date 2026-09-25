package eu.kanade.tachiyomi.data.export

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import reikai.domain.library.LibraryExportRow

object LibraryExporter {

    data class ExportOptions(
        val includeTitle: Boolean,
        val includeAuthor: Boolean,
        val includeArtist: Boolean,
    )

    suspend fun exportToCsv(
        context: Context,
        uri: Uri,
        // RK: both content types' favourites, as neutral rows
        favorites: List<LibraryExportRow>,
        options: ExportOptions,
        onExportComplete: () -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                val csvData = generateCsvData(favorites, options)
                outputStream.write(csvData.toByteArray())
            }
            onExportComplete()
        }
    }

    private val escapeRequired = listOf("\r", "\n", "\"", ",")

    // RK: internal and neutral rows, so the CSV is unit-testable for both content types
    internal fun generateCsvData(favorites: List<LibraryExportRow>, options: ExportOptions): String {
        val columnSize = listOf(
            options.includeTitle,
            options.includeAuthor,
            options.includeArtist,
        )
            .count { it }

        val rows = buildList(favorites.size) {
            favorites.forEach { manga ->
                buildList(columnSize) {
                    if (options.includeTitle) add(manga.title)
                    if (options.includeAuthor) add(manga.author)
                    if (options.includeArtist) add(manga.artist)
                }
                    .let(::add)
            }
        }
        return rows.joinToString("\r\n") { columns ->
            columns.joinToString(",") columns@{ column ->
                if (column.isNullOrBlank()) return@columns ""
                if (escapeRequired.any { column.contains(it) }) {
                    column.replace("\"", "\"\"").let { "\"$it\"" }
                } else {
                    column
                }
            }
        }
    }
}
