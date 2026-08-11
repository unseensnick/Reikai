package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.coil.NovelCover
import reikai.domain.library.ContentType
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.recents.RecentlyAddedManga
import reikai.domain.recents.RecentlyAddedNovel
import reikai.presentation.updates.NovelUpdatesItem
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.util.Date

/**
 * The adapters' row mapping and display projection, pinned once for both content types rather than as
 * a twin pair. Three things it pins that nothing else can: the two engines hand over timestamps in
 * different types and must leave as one, a raw row id must never become an identity a mixed feed could
 * confuse with the other type's, and progress shows only where reading stopped short.
 */
class RecentsMappingTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an updated row is keyed by its own content type`(probe: RecentsMappingProbe) {
        val mapped = probe.update()

        (mapped.entryId.contentType to mapped.entryId.rawId) shouldBe (probe.contentType to 7L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a read time arrives as epoch millis whatever the engine stores`(probe: RecentsMappingProbe) {
        probe.history(readAt = 4321L).timestamp shouldBe 4321L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry never read carries no timestamp rather than a null one`(probe: RecentsMappingProbe) {
        probe.history(readAt = null).timestamp shouldBe 0L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a recently added row has no chapter to open`(probe: RecentsMappingProbe) {
        probe.added().lane shouldBe RecentsLane.Added
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an updated row projects the chapter's name and flags`(probe: RecentsMappingProbe) {
        val chapter = probe.rowUi(probe.update(bookmark = true)).chapter
            .shouldBeInstanceOf<RecentsChapterUi.Named>()

        (chapter.name to chapter.bookmark) shouldBe ("c" to true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chapter left unfinished keeps its progress`(probe: RecentsMappingProbe) {
        val chapter = probe.rowUi(probe.update(read = false, started = true)).chapter

        chapter.shouldBeInstanceOf<RecentsChapterUi.Named>().progress shouldBe probe.startedProgress()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chapter already read shows no progress`(probe: RecentsMappingProbe) {
        val chapter = probe.rowUi(probe.update(read = true, started = true)).chapter

        chapter.shouldBeInstanceOf<RecentsChapterUi.Named>().progress shouldBe null
    }

    // What that progress reads as on the row. Neutral, so it takes the typed slot directly rather
    // than a probe: the point is that one rule writes out both engines' units.

    @Test
    fun `a page count reads one-based, because it is stored zero-based`() {
        RecentsProgress.Pages(lastPageRead = 5).labelValue() shouldBe 6
    }

    @Test
    fun `a chapter open at its first page claims no progress`() {
        RecentsProgress.Pages(lastPageRead = 0).labelValue() shouldBe null
    }

    @Test
    fun `hundredths of a percent round down to whole percent`() {
        RecentsProgress.Percent(hundredths = 4999).labelValue() shouldBe 49
    }

    @Test
    fun `a fraction of a percent claims no progress rather than rounding up to one`() {
        RecentsProgress.Percent(hundredths = 99).labelValue() shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a read row projects a chapter number rather than a name`(probe: RecentsMappingProbe) {
        probe.rowUi(probe.history(readAt = 4321L)).chapter shouldBe RecentsChapterUi.Number(1.0)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a recently added row projects no chapter at all`(probe: RecentsMappingProbe) {
        probe.rowUi(probe.added()).chapter shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a row on a favorite-gated lane reports itself in the library`(probe: RecentsMappingProbe) {
        probe.rowUi(probe.update()).isFavorite shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a title is read out of the row the feed emitted`(probe: RecentsMappingProbe) {
        probe.rowUi(probe.update()).title shouldBe "t"
    }

    @Test
    fun `a manga and a novel sharing a row id are not the same entry`() {
        val manga = MangaRecentsMappingProbe().update()
        val novel = NovelRecentsMappingProbe().update()

        (manga.entryId == novel.entryId) shouldBe false
    }

    @Test
    fun `a manga and a novel sharing a chapter row id are not the same chapter`() {
        val chapters = setOf(
            (MangaRecentsMappingProbe().update().lane as RecentsLane.Updated).chapter,
            (NovelRecentsMappingProbe().update().lane as RecentsLane.Updated).chapter,
        )

        chapters.size shouldBe 2
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaRecentsMappingProbe(), NovelRecentsMappingProbe())
    }
}

/**
 * One content type's rows, normalized so both answer in the same shape. Every row uses id 7 and
 * chapter id 70, which is what lets the two cross-type cases above prove the id spaces stay apart.
 */
interface RecentsMappingProbe {
    val contentType: ContentType

    fun update(read: Boolean = false, bookmark: Boolean = false, started: Boolean = false): RecentsItem

    fun history(readAt: Long?): RecentsItem

    fun added(): RecentsItem

    fun rowUi(item: RecentsItem): RecentsRowUi

    /** What a started chapter reports, in this engine's own unit. */
    fun startedProgress(): RecentsProgress
}

class MangaRecentsMappingProbe : RecentsMappingProbe {

    override val contentType = ContentType.MANGA

    override fun toString() = "manga"

    private val cover = MangaCover(mangaId = 7, sourceId = 1, isMangaFavorite = true, url = null, lastModified = 0)

    override fun update(read: Boolean, bookmark: Boolean, started: Boolean) = UpdatesItem(
        update = UpdatesWithRelations(
            mangaId = 7,
            mangaTitle = "t",
            chapterId = 70,
            chapterName = "c",
            scanlator = null,
            chapterUrl = "u",
            read = read,
            bookmark = bookmark,
            lastPageRead = if (started) 5L else 0L,
            sourceId = 1,
            dateFetch = 1000,
            coverData = cover,
        ),
        downloadStateProvider = { Download.State.NOT_DOWNLOADED },
        downloadProgressProvider = { 0 },
    ).toRecentsItem()

    override fun history(readAt: Long?) = HistoryWithRelations(
        id = 1,
        chapterId = 70,
        mangaId = 7,
        title = "t",
        chapterNumber = 1.0,
        readAt = readAt?.let { Date(it) },
        readDuration = 0,
        coverData = cover,
    ).toRecentsItem()

    override fun added() =
        RecentlyAddedManga(mangaId = 7, title = "t", dateAdded = 99, coverData = cover).toRecentsItem()

    override fun rowUi(item: RecentsItem) = mangaRowUi(item)

    override fun startedProgress() = RecentsProgress.Pages(5L)
}

class NovelRecentsMappingProbe : RecentsMappingProbe {

    override val contentType = ContentType.NOVELS

    override fun toString() = "novel"

    private val cover = NovelCover(url = null, site = null, isNovelFavorite = true, lastModified = 0, novelId = 7)

    override fun update(read: Boolean, bookmark: Boolean, started: Boolean) = NovelUpdatesItem(
        update = NovelUpdateWithRelations(
            novelId = 7,
            novelTitle = "t",
            chapterId = 70,
            chapterName = "c",
            chapterUrl = "u",
            read = read,
            bookmark = bookmark,
            lastTextProgress = if (started) 5000L else 0L,
            source = "s",
            dateFetch = 1000,
            coverData = cover,
            novelUrl = "nu",
        ),
        downloadState = Download.State.NOT_DOWNLOADED,
    ).toRecentsItem()

    override fun history(readAt: Long?) = NovelHistoryWithRelations(
        id = 1,
        chapterId = 70,
        novelId = 7,
        title = "t",
        chapterNumber = 1.0,
        readAt = readAt,
        readDuration = 0,
        coverData = cover,
    ).toRecentsItem()

    override fun added() = RecentlyAddedNovel(
        novelId = 7,
        title = "t",
        source = "s",
        url = "u",
        dateAdded = 99,
        coverData = cover,
    ).toRecentsItem()

    override fun rowUi(item: RecentsItem) = novelRowUi(item)

    override fun startedProgress() = RecentsProgress.Percent(5000L)
}
