package reikai.data.chapter

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.CopyMark
import reikai.domain.chapter.CopyMarkReport
import reikai.domain.library.ContentType
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException

/**
 * Writes every Never skip and hand-made copy group, of both types, to [uri] as JSON, for a report on the
 * duplicate rule: each chapter as its source names and numbers it, which is what the rule reads.
 * Returns how many chapters it wrote, or null when the file could not be written.
 */
suspend fun writeCopyMarkReport(
    context: Context,
    uri: Uri,
    marks: ChapterCopyMarkRepository,
    sourceManager: SourceManager,
    novelSourceManager: NovelSourceManager,
): Int? {
    val manga = marks.report(ContentType.MANGA)
    val novels = marks.report(ContentType.NOVELS)
    val json = buildJsonObject {
        put(
            "manga",
            buildJsonArray {
                manga.forEach { add(it.toJson(sourceManager.getOrStub(it.source.toLong()).name)) }
            },
        )
        put(
            "novels",
            buildJsonArray { novels.forEach { add(it.toJson(novelSourceManager.get(it.source)?.name ?: it.source)) } },
        )
    }
    // The provider behind a picked file may refuse it or fail mid-write.
    return runCatchingCancellable {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
        stream.bufferedWriter().use {
            it.write(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
        }
        manga.size + novels.size
    }.onFailure {
        logcat("CopyMarkExport", LogPriority.ERROR) { "Couldn't export the duplicate marks: ${it.asLog()}" }
    }.getOrNull()
}

private fun CopyMarkReport.toJson(sourceName: String) = buildJsonObject {
    put("series", title)
    put("source", sourceName)
    put("url", url)
    put("chapter", chapterName)
    put("number", chapterNumber)
    put("scanlator", scanlator)
    when (val mark = mark) {
        CopyMark.NeverSkip -> put("mark", "never_skip")
        is CopyMark.Group -> {
            put("mark", "copies")
            put("group", mark.key)
        }
    }
}
