package reikai.data.coil

import android.content.Context
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import reikai.domain.entry.CoverColors
import reikai.domain.entry.EntryId
import reikai.domain.entry.signedKey
import tachiyomi.core.common.util.lang.withIOContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/**
 * The colour this entry's cover tints its screens with: the one found for this cover, or what [extract] takes
 * from it, kept for next time. Both details screens and both readers ask here, so an entry tints the same
 * wherever it is opened. A cover is named by its address and change stamp, which every cover change moves, so
 * a new cover is read again without any caller remembering to say so. Whether a screen applies the colour is
 * the cover-theme setting's call, made by the screen, because the edit-info dialog tints from the cover either way.
 */
suspend fun EntryId.seedColor(
    coverUrl: String?,
    coverLastModified: Instant?,
    inLibrary: Boolean,
    extract: suspend () -> Int?,
): Int? {
    val key = signedKey()
    keptSeedColor(coverUrl, coverLastModified)?.let {
        // Filed again, so an entry added to or removed from the library since moves store.
        CoverColors.put(key, it, inLibrary)
        return it
    }
    val cover = coverCacheKey(null, coverUrl, coverLastModified)
    val color = extract()
    if (color == null) {
        CoverColors.remove(key)
        seedCovers.remove(key)
    } else {
        CoverColors.put(key, color, inLibrary)
        seedCovers[key] = cover
    }
    return color
}

/**
 * The colour [seedColor] kept for this very cover, or null until it has read this one, for a view built in a
 * moment that cannot wait for the cover to be read: a colour kept for an earlier cover is never handed back.
 */
fun EntryId.keptSeedColor(coverUrl: String?, coverLastModified: Instant?): Int? {
    val key = signedKey()
    if (seedCovers[key] != coverCacheKey(null, coverUrl, coverLastModified)) return null
    return CoverColors[key]
}

/**
 * The colour last kept for this entry, from whichever cover, for a page's first frame while its own cover is
 * read: the page then asks [seedColor] and takes its answer, so an old cover's colour lasts one read at most.
 */
fun EntryId.lastSeedColor(): Int? = CoverColors[signedKey()]

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
