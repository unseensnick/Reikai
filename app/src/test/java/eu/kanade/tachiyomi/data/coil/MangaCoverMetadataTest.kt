package eu.kanade.tachiyomi.data.coil

import android.content.Context
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.CoverColors
import reikai.util.idleAppScope
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore

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

    private companion object {
        const val UNTOUCHED = "untouched"
    }
}
