package exh.ui.metadata

import android.content.Context
import eu.kanade.domain.ui.UiPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.data.coil.extractCoverColor
import reikai.data.coil.seedColor
import reikai.domain.entry.CoverColors
import reikai.domain.entry.EntryId
import reikai.domain.entry.signedKey
import reikai.presentation.MainDispatcherExtension
import reikai.util.restubDefaultDispatcher
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

/** The page tints from the manga's own cover, read by id, so a restored page never shows a stale colour. */
class MetadataViewViewModelTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    // The model reads the manga and its cover on IO, which this puts on the test scheduler beside Main.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    // Main stays set, so the extension can stop the model on it before it resets Main.
    @AfterEach
    fun tearDown() = unmockkStatic(Dispatchers::class)

    private val manga = Manga.create().copy(id = MANGA_ID, thumbnailUrl = "https://example.org/cover.jpg")

    private fun viewModel(
        themeCoverBased: Boolean,
        getManga: GetManga = mockk { coEvery { await(MANGA_ID) } returns manga },
    ): MetadataViewViewModel {
        val getFlatMetadataById = mockk<GetFlatMetadataById> { coEvery { await(MANGA_ID) } returns null }
        val sourceManager = mockk<SourceManager> { coEvery { get(2L) } returns null }
        // The coEvery stubs leave Default answering a bare mock.
        restubDefaultDispatcher()
        return main.track(
            MetadataViewViewModel(
                mangaId = MANGA_ID,
                sourceId = 2L,
                context = mockk(relaxed = true),
                getFlatMetadataById = getFlatMetadataById,
                sourceManager = sourceManager,
                getManga = getManga,
                uiPreferences = UiPreferences(
                    InMemoryPreferenceStore(
                        sequenceOf(InMemoryPreference("pref_theme_cover_based_key", themeCoverBased, true)),
                    ),
                ),
            ),
        )
    }

    /** Nothing kept, so the colour can only come from the model reading the cover itself. */
    @Test
    fun `the page tints from the manga's cover`() = runTest {
        CoverColors.remove(EntryId.Manga(MANGA_ID).signedKey())
        mockkStatic(SEED_COLOR)
        coEvery { any<Context>().extractCoverColor(any()) } returns COLOR
        try {
            val viewModel = viewModel(themeCoverBased = true)

            withTimeout(5.seconds) { viewModel.seedColor.first { it != null } } shouldBe COLOR
        } finally {
            unmockkStatic(SEED_COLOR)
        }
    }

    @Test
    fun `a colour already kept tints the first frame, before the manga is read`() = runTest {
        EntryId.Manga(MANGA_ID).seedColor(manga.thumbnailUrl, null, inLibrary = true) { COLOR }

        val unread = mockk<GetManga> { coEvery { await(MANGA_ID) } coAnswers { awaitCancellation() } }

        val viewModel = viewModel(themeCoverBased = true, getManga = unread)

        viewModel.seedColor.value shouldBe COLOR
    }

    @Test
    fun `with the cover theme off the page is not tinted`() = runTest {
        EntryId.Manga(MANGA_ID).seedColor(manga.thumbnailUrl, null, inLibrary = true) { COLOR }
        val viewModel = viewModel(themeCoverBased = false)
        withTimeout(5.seconds) { viewModel.manga.first { it != null } }

        viewModel.seedColor.value shouldBe null
    }

    private companion object {
        const val MANGA_ID = 5L
        const val COLOR = 0x123456
        const val SEED_COLOR = "reikai.data.coil.EntrySeedColorKt"
    }
}
