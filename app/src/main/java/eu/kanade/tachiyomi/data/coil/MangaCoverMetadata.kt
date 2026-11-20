package eu.kanade.tachiyomi.data.coil

import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.core.metro.AppCoroutineScope
import okio.BufferedSource
import reikai.domain.entry.CoverColors
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.model.MangaCover
import java.io.File

/**
 * Reikai: extracts a vibrant color from a manga cover and caches it on
 * [CoverColors], persisting across restarts. The color seeds the reader and
 * manga-details theme when the cover-based theme preference is on. The [getBestColor] heuristic is
 * ported from Komikku (Jays2Kings).
 */
@Inject
@SingleIn(AppScope::class)
class MangaCoverMetadata(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val uiPreferences: UiPreferences,
    private val coverCache: CoverCache,
) {

    // Startup and every pause reach here, so both run on the app scope rather than the first frame or Main.
    private val loaded: Job by lazy {
        scope.launchIO {
            CoverColors.restore(uiPreferences.coverVibrantColors.get())
            saveMutex.withLock { lastSaved = uiPreferences.coverVibrantColors.get() }
        }
    }
    private val saveMutex = Mutex()
    private var lastSaved: Set<String>? = null

    /** Restore the persisted cover colors at app startup. */
    fun load(): Job = loaded

    /**
     * Persist the in-memory cover colors (called on app pause), skipping the write when nothing changed.
     * Waits for [load], or a pause before it finished would store a partial map over the full one.
     */
    fun savePrefs(): Job = scope.launchIO {
        loaded.join()
        val encoded = CoverColors.encode()
        saveMutex.withLock {
            if (encoded == lastSaved) return@launchIO
            uiPreferences.coverVibrantColors.set(encoded)
            lastSaved = encoded
        }
    }

    /** Extract the vibrant color in the background, skipping work when the feature is off or already known. */
    fun setVibrantColorAsync(mangaCover: MangaCover, bufferedSource: BufferedSource? = null, ogFile: File? = null) {
        if (!uiPreferences.themeCoverBased.get()) return
        // A known colour is only re-filed, so an entry added to or removed from the library since moves store.
        mangaCover.vibrantCoverColor?.let {
            mangaCover.vibrantCoverColor = it
            return
        }
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
}

private const val SUB_SAMPLE = 4

/**
 * Picks the most representative color from a cover's [Palette], favoring colorful swatches while
 * falling back to a sizeable muted swatch. Ported from Komikku (Jays2Kings, cuong-tran).
 */
internal fun Palette.getBestColor(): Int? {
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
