package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.novel.DEAD_READER_PADDING_KEY
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories

/**
 * What a restore does with a preference key the app has retired. The store answers null for a key it
 * does not hold, which satisfies the restorer's type check, so a retired key is written back unless
 * the restorer says otherwise: the six Yōkai-era keys it already skips, and the novel reader's page
 * padding, whose value has somewhere to go.
 */
class PreferenceRestorerTest {

    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)
    private val context = mockk<Context>()

    private val restorer = PreferenceRestorer(
        context = context,
        getCategories = mockk<GetCategories>(),
        preferenceStore = store,
        categoryIdPreferences = mockk<CategoryIdPreferences>(relaxed = true),
        novelPreferences = novelPreferences,
    )

    /** Both are WorkManager scheduling the restore does on its way out, which needs a real app. */
    @BeforeEach
    fun stubTheJobs() {
        mockkObject(LibraryUpdateJob)
        mockkObject(BackupCreateJob)
        every { LibraryUpdateJob.setupTask(any(), any()) } returns Unit
        every { BackupCreateJob.setupTask(any(), any()) } returns Unit
    }

    @AfterEach
    fun releaseTheJobs() {
        unmockkObject(LibraryUpdateJob)
        unmockkObject(BackupCreateJob)
    }

    private fun margins() = with(novelPreferences) {
        listOf(readerMarginTop(), readerMarginBottom(), readerMarginLeft(), readerMarginRight()).map { it.get() }
    }

    private suspend fun restore(key: String, value: Int) =
        restorer.restoreApp(listOf(BackupPreference(key, IntPreferenceValue(value))), backupCategories = null)

    @Test
    @DisplayName("a backup taken before the margins existed keeps its page padding")
    fun retiredPaddingReachesTheMargins() = runTest {
        restore(DEAD_READER_PADDING_KEY, 32)

        margins() shouldBe listOf(32, 32, 32, 32)
    }

    /** The upgrade migration has already run by the time a restore lands, so nothing would read it. */
    @Test
    @DisplayName("the retired padding key is not written back into the store")
    fun retiredPaddingIsNotResurrected() = runTest {
        restore(DEAD_READER_PADDING_KEY, 32)

        store.getInt(DEAD_READER_PADDING_KEY, 0).isSet() shouldBe false
    }

    @Test
    @DisplayName("a live preference is still restored")
    fun aLivePreferenceIsRestored() = runTest {
        restore(novelPreferences.readerFontSize().key(), 22)

        novelPreferences.readerFontSize().get() shouldBe 22
    }
}
