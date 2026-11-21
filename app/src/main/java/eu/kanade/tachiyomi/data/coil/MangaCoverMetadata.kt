package eu.kanade.tachiyomi.data.coil

import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import okhttp3.Response
import okio.BufferedSource
import reikai.domain.entry.CoverColors
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
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
            saveMutex.withLock { lastSaved = CoverColors.withoutRecency(uiPreferences.coverVibrantColors.get()) }
        }
    }
    private val saveMutex = Mutex()
    private var lastSaved: Set<String>? = null

    /** Restore the persisted cover colors at app startup. */
    fun load(): Job = loaded

    /**
     * Persist the in-memory cover colors (called on app pause), skipping the write when no colour came, went or
     * changed.
     * Waits for [load], or a pause before it finished would store a partial map over the full one.
     */
    fun savePrefs(): Job = scope.launchIO {
        loaded.join()
        val encoded = CoverColors.encode()
        val colours = CoverColors.withoutRecency(encoded)
        saveMutex.withLock {
            if (colours == lastSaved) return@launchIO
            uiPreferences.coverVibrantColors.set(encoded)
            lastSaved = colours
        }
    }

    /**
     * Extract the vibrant color in the background, skipping work when the feature is off or already known.
     * [openSource] runs on the calling thread and only when a colour will be read, since a disk snapshot can be
     * closed and a response body consumed once the fetch returns; it must not hand back the source Coil reads.
     * A null or failed open reads nothing, rather than falling back to [CoverCache], which may hold an older cover.
     */
    fun setVibrantColorAsync(
        mangaCover: MangaCover,
        ogFile: File? = null,
        openSource: (() -> BufferedSource?)? = null,
    ): Job? {
        if (!uiPreferences.themeCoverBased.get()) return null
        // A known colour is only re-filed, so an entry added to or removed from the library since moves store.
        mangaCover.vibrantCoverColor?.let {
            mangaCover.vibrantCoverColor = it
            return null
        }
        val source = openSource?.let { open -> colourOptional("open") { open() } ?: return null }
        return scope.launchIO {
            source.use { colourOptional("extract") { setVibrantColor(mangaCover, it, ogFile) } }
        }
    }

    private fun setVibrantColor(mangaCover: MangaCover, bufferedSource: BufferedSource?, ogFile: File?) {
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

    // The colour is optional, so neither a dropped connection nor a cover too large to decode may fail the fetch.
    private inline fun <T> colourOptional(step: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Cover colour: failed to $step" }
        null
    } catch (e: OutOfMemoryError) {
        logcat(LogPriority.WARN, e) { "Cover colour: out of memory to $step" }
        null
    }

    companion object {
        /**
         * An independent copy of [response]'s body for the colour, or null past [MAX_COVER_PEEK_BYTES], so a huge
         * cover is neither buffered whole nor decoded truncated.
         */
        fun peekForColour(response: Response): BufferedSource? {
            if (response.body.contentLength() > MAX_COVER_PEEK_BYTES) return null
            // One byte past the cap tells a body of unknown length that exceeds it from one that fits exactly.
            val peeked = response.peekBody(MAX_COVER_PEEK_BYTES + 1)
            return peeked.source().takeIf { peeked.contentLength() <= MAX_COVER_PEEK_BYTES }
        }
    }
}

private const val SUB_SAMPLE = 4
internal const val MAX_COVER_PEEK_BYTES = 5L * 1024 * 1024

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
