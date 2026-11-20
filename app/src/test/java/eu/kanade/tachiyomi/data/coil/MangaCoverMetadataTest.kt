package eu.kanade.tachiyomi.data.coil

import android.content.Context
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
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
        MangaCover.vibrantCoverColorMap.clear()
    }

    @Test
    fun `a library larger than the cap stores only the cap`() = runTest {
        uiPreferences.coverVibrantColors.set((1L..MangaCover.VIBRANT_COLOR_CAP + 500L).map { "$it|1" }.toSet())

        metadata.savePrefs().join()

        uiPreferences.coverVibrantColors.get().size shouldBe MangaCover.VIBRANT_COLOR_CAP
    }

    @Test
    fun `a colour found before the restore keeps over the stored one`() = runTest {
        uiPreferences.coverVibrantColors.set(setOf("7|1"))
        MangaCover.vibrantCoverColorMap[7L] = 2

        metadata.load().join()

        MangaCover.vibrantCoverColorMap[7L] shouldBe 2
    }

    @Test
    fun `a pause before the restore finished keeps the stored colours`() = runTest {
        uiPreferences.coverVibrantColors.set(setOf("7|1"))
        MangaCover.vibrantCoverColorMap[8L] = 2

        metadata.savePrefs().join()

        uiPreferences.coverVibrantColors.get() shouldContain "7|1"
    }

    @Test
    fun `a pause with no new colour writes nothing`() = runTest {
        uiPreferences.coverVibrantColors.set(setOf("7|1"))
        metadata.load().join()
        uiPreferences.coverVibrantColors.set(setOf(UNTOUCHED))

        metadata.savePrefs().join()

        uiPreferences.coverVibrantColors.get() shouldBe setOf(UNTOUCHED)
    }

    private companion object {
        const val UNTOUCHED = "untouched"
    }
}
