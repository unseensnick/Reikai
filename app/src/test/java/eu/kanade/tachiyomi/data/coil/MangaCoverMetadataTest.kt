package eu.kanade.tachiyomi.data.coil

import android.content.Context
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.CoverColors
import reikai.util.idleAppScope
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.model.MangaCover

/**
 * The cover colours persist across restarts, browse covers included, so the store is capped, read off the first
 * frame, and written only when a colour changed.
 */
class MangaCoverMetadataTest {

    private val uiPreferences = UiPreferences(InMemoryPreferenceStore())
    private val metadata = MangaCoverMetadata(
        testAppScope(),
        uiPreferences,
        CoverCache(idleAppScope(), mockk<Context>(relaxed = true)),
    )

    @AfterEach
    fun clearColors() {
        CoverColors.clear()
    }

    @Test
    fun `a library larger than the cap stores only the cap`() = runTest {
        uiPreferences.coverVibrantColors.set((1L..CoverColors.CAP + 500L).map { "$it|1" }.toSet())

        metadata.savePrefs().join()

        uiPreferences.coverVibrantColors.get().size shouldBe CoverColors.CAP
    }

    @Test
    fun `a colour found before the restore keeps over the stored one`() = runTest {
        uiPreferences.coverVibrantColors.set(setOf("7|1"))
        CoverColors.put(7L, 2, inLibrary = false)

        metadata.load().join()

        CoverColors[7L] shouldBe 2
    }

    @Test
    fun `a pause before the restore finished keeps the stored colours`() = runTest {
        uiPreferences.coverVibrantColors.set(setOf("7|1"))
        CoverColors.put(8L, 2, inLibrary = false)

        metadata.savePrefs().join()
        CoverColors.clear()
        CoverColors.restore(uiPreferences.coverVibrantColors.get())

        CoverColors[7L] shouldBe 1
    }

    @Test
    fun `a pause with no new colour writes nothing`() = runTest {
        uiPreferences.coverVibrantColors.set(setOf("7|1|0"))
        metadata.load().join()
        uiPreferences.coverVibrantColors.set(setOf(UNTOUCHED))

        metadata.savePrefs().join()

        uiPreferences.coverVibrantColors.get() shouldBe setOf(UNTOUCHED)
    }

    @Test
    fun `a pause after reads that changed only the order writes nothing`() = runTest {
        uiPreferences.coverVibrantColors.set(setOf("7|1|0", "8|2|1"))
        metadata.load().join()
        CoverColors[7L]
        uiPreferences.coverVibrantColors.set(setOf(UNTOUCHED))

        metadata.savePrefs().join()

        uiPreferences.coverVibrantColors.get() shouldBe setOf(UNTOUCHED)
    }

    @Test
    fun `no cover source is opened while cover theming is off`() {
        uiPreferences.themeCoverBased.set(false)
        var opened = false

        metadata.setVibrantColorAsync(cover()) {
            opened = true
            null
        }

        opened shouldBe false
    }

    @Test
    fun `no cover source is opened for a colour already known`() {
        val cover = cover().apply { vibrantCoverColor = 1 }
        var opened = false

        metadata.setVibrantColorAsync(cover) {
            opened = true
            null
        }

        opened shouldBe false
    }

    @Test
    fun `an opened cover source is closed once read`() = runTest {
        val source = TrackingSource()

        metadata.setVibrantColorAsync(cover()) { source.buffer() }?.join()

        source.closed shouldBe true
    }

    @Test
    fun `a cover past the peek cap is not copied for its colour`() {
        val body = ByteArray((MAX_COVER_PEEK_BYTES + 1).toInt()).toResponseBody()

        MangaCoverMetadata.peekForColour(response(body)) shouldBe null
    }

    private fun cover() = MangaCover(
        mangaId = 7L,
        sourceId = 1L,
        isMangaFavorite = true,
        url = null,
        lastModified = null,
    )

    private fun response(body: ResponseBody) = Response.Builder()
        .request(Request.Builder().url("https://example.org/cover.jpg").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body)
        .build()

    private class TrackingSource : ForwardingSource(Buffer().write(ByteArray(16))) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val UNTOUCHED = "untouched"
    }
}
