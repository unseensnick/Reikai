package reikai.domain.novel.model

import mihon.domain.common.PartialUpdate
import kotlin.time.Instant

/**
 * Partial-update patch for the `novels` table, built like its manga twin
 * [tachiyomi.domain.manga.model.MangaUpdate]: only the fields assigned in [block] are written. The
 * full-row `update(Novel)` stays for restore. SQLDelight loses the
 * `updateStrategy` and [genre] column adapters through the partial update, so `updateStrategy` is absent
 * (patch it in full) and the repository writes [genre] on its own.
 */
class NovelUpdate(val id: Long, block: NovelUpdate.() -> Unit) : PartialUpdate() {
    var title: String? by field(null)
    var author: String? by field(null)
    var artist: String? by field(null)
    var description: String? by field(null)
    var genre: List<String>? by field(null)
    var status: Long? by field(null)
    var thumbnailUrl: String? by field(null)
    var favoriteAt: Instant? by field(null)
    var lastUpdate: Instant? by field(null)
    var initialized: Boolean? by field(null)
    var chapterFlags: Long? by field(null)
    var coverLastModified: Instant? by field(null)
    var totalPages: Long? by field(null)
    var notes: String? by field(null)
    var viewerFlags: Long? by field(null)
    var nextUpdate: Instant? by field(null)
    var fetchInterval: Int? by field(null)

    init {
        block()
    }
}
