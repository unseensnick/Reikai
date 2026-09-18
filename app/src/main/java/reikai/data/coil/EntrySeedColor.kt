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
import reikai.domain.entry.vibrantColorKey
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.MangaCover

/**
 * The colour this entry's cover tints its screens with: the one already found, or what [extract] takes
 * from the cover, kept for next time. Both details screens and both readers ask here, so an entry tints
 * the same wherever it is opened. Whether a screen applies it is the cover-theme setting's call, made
 * by the screen, because the edit-info dialog tints from the cover either way.
 */
suspend fun EntryId.seedColor(extract: suspend () -> Int?): Int? {
    val key = vibrantColorKey()
    return MangaCover.vibrantCoverColorMap[key]
        ?: extract()?.also { MangaCover.vibrantCoverColorMap[key] = it }
}

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
