package tachiyomi.domain.backup.model

import exh.metadata.metadata.base.FlatMetadata
import reikai.domain.chapter.ChapterNumberOverride
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import java.util.Date

data class RestoredManga(
    val manga: Manga,
    val chapters: List<Chapter>,
    val categoryIds: List<Long>,
    val history: List<RestoredHistory>,
    val tracks: List<Track>,
    val excludedScanlators: List<String>,
    // RK --> adult gallery metadata and the user's custom info, written under the restored entry's id
    // (whatever id they carry here is replaced); null leaves the device's own alone
    val searchMetadata: FlatMetadata? = null,
    val customInfo: CustomMangaInfo? = null,
    // The chapter numbers the user corrected, keyed by url, which land after the chapters do.
    val chapterNumberOverrides: List<ChapterNumberOverride> = emptyList(),
    // RK <--
)

data class RestoredHistory(
    val chapterUrl: String,
    val readAt: Date?,
    val readDuration: Long,
)
