package exh.ui.metadata

import eu.kanade.domain.ui.UiPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.data.coil.seedColor
import reikai.domain.entry.EntryId
import reikai.presentation.MainDispatcherExtension
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration.Companion.seconds

/** The page tints from the manga's own cover, read by id, so a restored page never shows a stale colour. */
class MetadataViewViewModelTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val manga = Manga.create().copy(id = MANGA_ID, thumbnailUrl = "https://example.org/cover.jpg")

    private fun viewModel(themeCoverBased: Boolean) = main.track(
        MetadataViewViewModel(
            mangaId = MANGA_ID,
            sourceId = 2L,
            context = mockk(relaxed = true),
            getFlatMetadataById = mockk { coEvery { await(MANGA_ID) } returns null },
            sourceManager = mockk { coEvery { get(2L) } returns null },
            getManga = mockk { coEvery { await(MANGA_ID) } returns manga },
            uiPreferences = UiPreferences(
                InMemoryPreferenceStore(
                    sequenceOf(InMemoryPreference("pref_theme_cover_based_key", themeCoverBased, true)),
                ),
            ),
        ),
    )

    @Test
    fun `the page tints from the manga's cover`() = runTest {
        EntryId.Manga(MANGA_ID).seedColor(manga.thumbnailUrl, null) { COLOR }
        val viewModel = viewModel(themeCoverBased = true)

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { viewModel.seedColor.first { it != null } }
        } shouldBe COLOR
    }

    @Test
    fun `with the cover theme off the page is not tinted`() = runTest {
        EntryId.Manga(MANGA_ID).seedColor(manga.thumbnailUrl, null) { COLOR }
        val viewModel = viewModel(themeCoverBased = false)
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { viewModel.manga.first { it != null } } }

        viewModel.seedColor.value shouldBe null
    }

    private companion object {
        const val MANGA_ID = 5L
        const val COLOR = 0x123456
    }
}
