package reikai.novel.source

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import reikai.data.novel.NovelDateParser
import reikai.data.novel.NovelStatusCode

/** A novel extension app's catalogue crosses into the novel side's own forms. */
class TachiyomiNovelSourceTest {

    @Test
    fun `a status number arrives in the words novels store`() = runTest {
        val novel = source(details = { status = SManga.COMPLETED }).parseNovel("n")

        NovelStatusCode.fromString(novel.status) shouldBe NovelStatusCode.COMPLETED
    }

    @Test
    fun `an upload date survives to the millisecond`() = runTest {
        val chapter = source(chapter = { date_upload = 1_700_000_000_123L }).parseNovel("n").chapters!!.single()

        NovelDateParser.parse(chapter.releaseTime) shouldBe 1_700_000_000_123L
    }

    @Test
    fun `an unknown upload date is left unset`() = runTest {
        source(chapter = { date_upload = 0L }).parseNovel("n").chapters!!.single().releaseTime shouldBe null
    }

    @Test
    fun `an unknown chapter number is left unset`() = runTest {
        source(chapter = { chapter_number = -1f }).parseNovel("n").chapters!!.single().chapterNumber shouldBe null
    }

    @Test
    fun `a catalogue without filters offers none`() {
        source(filters = FilterList()).filters shouldBe null
    }

    @Test
    fun `a catalogue's filters travel with its search`() {
        source(filters = FilterList(Filter.Header("h"))).filters?.applyToSearch shouldBe true
    }

    private fun source(
        details: SManga.() -> Unit = {},
        chapter: SChapter.() -> Unit = {},
        filters: FilterList = FilterList(),
    ) = TachiyomiNovelSource(
        mockk<CatalogueSource> {
            every { id } returns 7L
            every { name } returns "App"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } returns filters
            coEvery { getMangaUpdate(any(), any(), any(), any()) } answers {
                val manga = SManga.create().apply {
                    url = "n"
                    title = "Novel"
                    details()
                }
                val chapters = listOf(
                    SChapter.create().apply {
                        url = "c"
                        name = "Chapter"
                        chapter()
                    },
                )
                SMangaUpdate(manga, chapters)
            }
        },
        Extension.Loaded(
            name = "App",
            pkgName = "eu.kanade.tachiyomi.novelextension.en.app",
            versionName = "1.6.1",
            versionCode = 1,
            libVersion = 1.6,
            lang = "en",
            contentWarning = ContentWarning.SAFE,
            isShared = true,
            kind = Extension.Kind.TACHIYOMI_NOVEL,
            pkgFactory = null,
            sources = emptyList(),
            icon = null,
        ),
    )
}
