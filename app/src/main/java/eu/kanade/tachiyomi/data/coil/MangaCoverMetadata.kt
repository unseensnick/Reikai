package eu.kanade.tachiyomi.data.coil

import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.CoroutineScope
import okio.BufferedSource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Reikai (Y11): extracts a vibrant color from a manga cover and caches it on
 * [MangaCover.vibrantCoverColorMap], persisting across restarts. The color seeds the reader and
 * manga-details theme when the cover-based theme preference is on. The [getBestColor] heuristic is
 * ported from Komikku (Jays2Kings).
 */
object MangaCoverMetadata {
    private val uiPreferences: UiPreferences by injectLazy()
    private val coverCache: CoverCache by injectLazy()
    private val scope: CoroutineScope by injectLazy()

    /** Restore the persisted cover colors at app startup. */
    fun load() {
        val colors = uiPreferences.coverVibrantColors.get()
        MangaCover.vibrantCoverColorMap.putAll(
            colors.mapNotNull {
                val splits = it.split("|")
                val id = splits.getOrNull(0)?.toLongOrNull()
                val color = splits.getOrNull(1)?.toIntOrNull()
                if (id != null && color != null) id to color else null
            }.toMap(),
        )
    }

    /** Persist the in-memory cover colors (called on app pause). */
    fun savePrefs() {
        val copy = MangaCover.vibrantCoverColorMap.toMap()
        uiPreferences.coverVibrantColors.set(
            copy.mapNotNull { (id, color) -> color?.let { "$id|$it" } }.toSet(),
        )
    }

    /** Extract the vibrant color in the background, skipping work when the feature is off or already known. */
    fun setVibrantColorAsync(mangaCover: MangaCover, bufferedSource: BufferedSource? = null, ogFile: File? = null) {
        if (!uiPreferences.themeCoverBased.get() || mangaCover.vibrantCoverColor != null) return
        scope.launchIO { setVibrantColor(mangaCover, bufferedSource, ogFile) }
    }

    fun setVibrantColor(mangaCover: MangaCover, bufferedSource: BufferedSource? = null, ogFile: File? = null) {
        if (mangaCover.vibrantCoverColor != null) return

        val options = BitmapFactory.Options().apply { inSampleSize = SUB_SAMPLE }
        val file = ogFile
            ?: coverCache.getCustomCoverFile(mangaCover.mangaId).takeIf { it.exists() }
            ?: coverCache.getCoverFile(mangaCover.url)

        val bitmap = when {
            bufferedSource != null -> BitmapFactory.decodeStream(bufferedSource.inputStream(), null, options)
            file?.exists() == true -> BitmapFactory.decodeFile(file.path, options)
            else -> return
        } ?: return

        Palette.from(bitmap).generate().getBestColor()?.let { mangaCover.vibrantCoverColor = it }
    }

    private const val SUB_SAMPLE = 4
}

/**
 * Picks the most representative color from a cover's [Palette], favoring colorful swatches while
 * falling back to a sizeable muted swatch. Ported from Komikku (Jays2Kings, cuong-tran).
 */
private fun Palette.getBestColor(): Int? {
    val vibPopulation = vibrantSwatch?.population ?: -1
    val domSat = dominantSwatch?.hsl?.get(1) ?: 0f
    val domLum = dominantSwatch?.hsl?.get(2) ?: -1f
    val mutedPopulation = mutedSwatch?.population ?: -1
    val mutedSat = mutedSwatch?.hsl?.get(1) ?: 0f
    val mutedSatMinAcceptable = if (mutedPopulation > vibPopulation * 3f) 0.1f else 0.25f

    val dominantIsColorful = domSat >= .25f
    val dominantBrightnessJustRight = domLum <= .8f && domLum > .2f
    val vibrantIsConsiderableBigEnough = vibPopulation >= mutedPopulation * 0.75f
    val mutedIsBig = mutedPopulation > vibPopulation * 1.5f
    val mutedIsNotTooBoring = mutedSat > mutedSatMinAcceptable

    return when {
        dominantIsColorful && dominantBrightnessJustRight -> dominantSwatch
        vibrantIsConsiderableBigEnough -> vibrantSwatch
        mutedIsBig && mutedIsNotTooBoring -> mutedSwatch
        else -> listOfNotNull(vibrantSwatch, lightVibrantSwatch, darkVibrantSwatch)
            .maxByOrNull {
                if (it === vibrantSwatch) vibPopulation * 3 else it.population
            }
    }?.rgb
}
