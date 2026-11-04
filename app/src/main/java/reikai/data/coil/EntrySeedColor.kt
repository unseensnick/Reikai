package reikai.data.coil

import android.content.Context
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import reikai.domain.entry.EntryId
import reikai.domain.entry.signedKey
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.MangaCover
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/**
 * The colour this entry's cover tints its screens with: the one found for this cover, or what [extract] takes
 * from it, kept for next time. Both details screens and both readers ask here, so an entry tints the same
 * wherever it is opened. A cover is named by its address and change stamp, which every cover change moves, so
 * a new cover is read again without any caller remembering to say so. Whether a screen applies the colour is
 * the cover-theme setting's call, made by the screen, because the edit-info dialog tints from the cover either way.
 */
suspend fun EntryId.seedColor(coverUrl: String?, coverLastModified: Instant?, extract: suspend () -> Int?): Int? {
    val key = signedKey()
    val cover = coverCacheKey(null, coverUrl, coverLastModified)
    if (seedCovers[key] == cover) MangaCover.vibrantCoverColorMap[key]?.let { return it }
    val color = extract()
    if (color == null) {
        MangaCover.vibrantCoverColorMap.remove(key)
        seedCovers.remove(key)
    } else {
        MangaCover.vibrantCoverColorMap[key] = color
        seedCovers[key] = cover
    }
    return color
}

// Which cover each kept colour came from. Kept for the process only: a colour restored from disk, or found by
// the library's cover loads, carries no cover, so the first open after a start reads it again.
private val seedCovers = ConcurrentHashMap<Long, String>()

/**
 * Palette's pick from the cover [coverData] names, loaded through that content type's own fetcher (a
 * novel's sends the Referer some cover hosts require), so an entry opened from browsing still tints.
 */
suspend fun Context.extractCoverColor(coverData: Any): Int? = withIOContext {
    val request = ImageRequest.Builder(this@extractCoverColor)
        .data(coverData)
        .allowHardware(false) // Palette can't read hardware bitmaps
        // With no view to size it, the cover would decode at full size for a pick Palette makes from
        // about 112 by 112 pixels anyway.
        .size(PALETTE_SAMPLE_SIZE)
        .build()
    imageLoader.execute(request).image
        ?.asDrawable(resources)
        ?.getBitmapOrNull()
        ?.let { Palette.from(it).generate().getBestColor() }
}

private const val PALETTE_SAMPLE_SIZE = 256
