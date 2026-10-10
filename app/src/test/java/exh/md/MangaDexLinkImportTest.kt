package exh.md

import android.net.Uri
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.GalleryAdder
import exh.md.dto.ChapterDto
import exh.md.dto.RelationshipDto
import exh.md.handlers.MangaHandler
import exh.pref.DelegateSourcePreferences
import exh.source.EnhancedHttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.track.installTrackerTestGraph
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import java.net.URI

/** A mangadex.org link opened in the app imports through the MangaDex delegate, while delegation is on. */
class MangaDexLinkImportTest {

    private lateinit var replaced: InjektScope

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uri(firstArg()) }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
        if (::replaced.isInitialized) Injekt = replaced
    }

    private val extension = mockk<HttpSource>(relaxed = true) {
        every { id } returns SOURCE_ID
        every { lang } returns "en"
        every { name } returns "MangaDex"
    }

    private val mangaDex = MangaDex(extension, mockk(relaxed = true))

    private fun uri(url: String): Uri {
        val parsed = URI(url)
        return mockk {
            every { host } returns parsed.host
            every { pathSegments } returns parsed.path.split('/').filter { it.isNotEmpty() }
        }
    }

    private fun delegation(on: Boolean) {
        val store = InMemoryPreferenceStore(sequenceOf(InMemoryPreference("eh_delegate_sources", on, true)))
        replaced = installTrackerTestGraph(preferences = store) {
            every { delegateSourcePreferences } returns DelegateSourcePreferences(store)
        }
    }

    private suspend fun pickSource(url: String) = GalleryAdder(
        libraryAdder = mockk(),
        updateMangaFromRemote = mockk(),
        networkToLocalManga = mockk(),
        getChapter = mockk(),
        sourceManager = mockk {
            coEvery { getOnlineSources() } returns listOf(EnhancedHttpSource(extension, mangaDex))
        },
        reconcileMergedChapters = mockk(),
        sourcePreferences = mockk {
            every { enabledLanguages } returns mockk { every { get() } returns emptySet() }
            every { disabledSources } returns mockk { every { get() } returns emptySet() }
        },
    ).pickSource(url)

    @Test
    fun `a title link maps to the series`() = runTest {
        mangaDex.mapUrlToMangaUrl(uri("https://mangadex.org/title/$TITLE/some-slug")) shouldBe "/manga/$TITLE"
    }

    @Test
    fun `an old manga link maps to the series`() = runTest {
        mangaDex.mapUrlToMangaUrl(uri("https://mangadex.org/manga/$TITLE")) shouldBe "/manga/$TITLE"
    }

    @Test
    fun `a title link without an id maps to no series`() = runTest {
        mangaDex.mapUrlToMangaUrl(uri("https://mangadex.org/title")) shouldBe null
    }

    @Test
    fun `a link to another page maps to no series`() = runTest {
        mangaDex.mapUrlToMangaUrl(uri("https://mangadex.org/group/$TITLE")) shouldBe null
    }

    @Test
    fun `a chapter link maps to the chapter`() {
        mangaDex.mapUrlToChapterUrl(uri("https://mangadex.org/chapter/$CHAPTER")) shouldBe
            "https://api.mangadex.org/chapter/$CHAPTER"
    }

    @Test
    fun `a title link maps to no chapter`() {
        mangaDex.mapUrlToChapterUrl(uri("https://mangadex.org/title/$TITLE")) shouldBe null
    }

    @Test
    fun `a chapter resolves to the series it belongs to`() = runTest {
        val chapter = mockk<ChapterDto> {
            every { data.relationships } returns listOf(
                RelationshipDto("group", "scanlation_group"),
                RelationshipDto(TITLE, "manga"),
            )
        }
        val handler = MangaHandler("en", mockk { coEvery { viewChapter(CHAPTER) } returns chapter })

        handler.getMangaFromChapterId(CHAPTER) shouldBe TITLE
    }

    @Test
    fun `a www link is the site's own`() {
        mangaDex.matchesUri(uri("https://www.mangadex.org/title/$TITLE")) shouldBe true
    }

    @Test
    fun `a link imports through the MangaDex delegate while delegation is on`() = runTest {
        delegation(on = true)

        pickSource("https://mangadex.org/title/$TITLE") shouldBe listOf(mangaDex)
    }

    @Test
    fun `a link finds no importer while delegation is off`() = runTest {
        delegation(on = false)

        pickSource("https://mangadex.org/title/$TITLE") shouldBe emptyList()
    }

    private companion object {
        const val SOURCE_ID = 2499283573021220255L
        const val TITLE = "a96676e5-8ae2-425e-b549-7f15dd34a6d8"
        const val CHAPTER = "0f8b6a2d-6d0f-4ec1-9a3a-3b0b8e0d1c2a"
    }
}
