package exh

import android.net.Uri
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import mihon.domain.source.models.RemoteMangaUpdate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.merge.ReconcileMergedChapters
import reikai.presentation.browse.FakeMangaLibrary
import reikai.presentation.browse.libraryCategory
import tachiyomi.domain.manga.model.Manga

/**
 * A gallery added from a batch or a shared link has no screen to ask on, so it lands the way any
 * other add does when nothing asks: the default chapter settings, the default category when one is
 * set, and nothing rewritten on a gallery that was already in the library.
 */
class GalleryAdderTest {

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkStatic(Uri::class)

    private val library = FakeMangaLibrary(userCategories = listOf(libraryCategory(3L)), defaultCategoryId = 3)

    private val source = mockk<HttpSource>(moreInterfaces = arrayOf(UrlImportableSource::class)) {
        every { id } returns FakeMangaLibrary.SOURCE_ID
        every { (this@mockk as UrlImportableSource).matchesUri(any()) } returns true
        every { (this@mockk as UrlImportableSource).mapUrlToChapterUrl(any()) } returns null
        coEvery { (this@mockk as UrlImportableSource).mapUrlToMangaUrl(any()) } returns "/1"
        every { (this@mockk as UrlImportableSource).cleanMangaUrl(any()) } returns "/1"
    }

    private fun adder() = GalleryAdder(
        libraryAdder = library.adder,
        updateMangaFromRemote = mockk {
            coEvery { this@mockk.invoke(any<Manga>(), any(), any(), any(), any()) } answers {
                Result.success(RemoteMangaUpdate(library.rows.getValue(1L), emptyList()))
            }
        },
        networkToLocalManga = mockk {
            coEvery { this@mockk.invoke(any<Manga>()) } answers { library.rows.getValue(1L) }
        },
        getChapter = mockk(relaxed = true),
        sourceManager = mockk(relaxed = true),
        reconcileMergedChapters = ReconcileMergedChapters(repository = mockk(relaxed = true), stitchers = emptySet()),
        sourcePreferences = mockk {
            every { enabledLanguages } returns mockk { every { get() } returns emptySet() }
            every { disabledSources } returns mockk { every { get() } returns emptySet() }
        },
    )

    private suspend fun addGallery() = adder().addGallery(
        context = mockk(relaxed = true),
        url = "https://gallery.test/1",
        fav = true,
        forceSource = source as UrlImportableSource,
    )

    @Test
    fun `adding a gallery already in the library writes no favorite`() = runTest {
        library.put(1L, favorite = true, categories = listOf(5L))

        addGallery()

        library.favoriteWrites shouldBe emptyList()
    }

    @Test
    fun `a gallery already in the library keeps its categories`() = runTest {
        library.put(1L, favorite = true, categories = listOf(5L))

        addGallery()

        library.filed[1L] shouldBe listOf(5L)
    }

    @Test
    fun `a new gallery takes the default chapter settings`() = runTest {
        library.put(1L, favorite = false)

        addGallery()

        library.chapterDefaultsStamped shouldBe setOf(1L)
    }

    @Test
    fun `a new gallery files into the default category`() = runTest {
        library.put(1L, favorite = false)

        addGallery()

        library.filed[1L] shouldBe listOf(3L)
    }
}
