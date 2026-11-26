package reikai.domain.download

import eu.kanade.tachiyomi.data.download.DownloadManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadCache
import reikai.novel.local.LocalNovelSource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import kotlin.time.Instant
import reikai.domain.manga.downloadedChapterIds as mangaDownloadedIds
import reikai.domain.manga.isOnDevice as mangaOnDevice
import reikai.domain.novel.downloadedChapterIds as novelDownloadedIds
import reikai.domain.novel.isOnDevice as novelOnDevice

/**
 * Each type's own owner lookup and local test, handed to [ownedOnDisk]. Owners 1 and 2 keep one chapter
 * name each in their folder ("Ch 1", "Ch 2"); owner 3 is local and has nothing downloaded.
 */
class DownloadedChaptersConformanceTest {

    interface Type {
        /** The ids found of chapters (id, owner, name). */
        fun downloaded(chapters: List<Triple<Long, Long, String>>): Set<Long>

        /** Whether one copy of a local owner's chapter is on the device. */
        fun localCopyOnDevice(): Boolean
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `each chapter is looked up in its own owner's folder`(type: Type) {
        // 12 and 22 share a name with the other owner's download, but neither is downloaded under its own.
        type.downloaded(
            listOf(Triple(11L, 1L, "Ch 1"), Triple(12L, 1L, "Ch 2"), Triple(21L, 2L, "Ch 2"), Triple(22L, 2L, "Ch 1")),
        ) shouldBe setOf(11L, 21L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `a local owner's chapters are all on disk`(type: Type) {
        type.downloaded(listOf(Triple(31L, 3L, "Ch 31"), Triple(32L, 3L, "Ch 32"))) shouldBe setOf(31L, 32L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `one copy of a local owner's chapter is on the device`(type: Type) {
        type.localCopyOnDevice() shouldBe true
    }

    private object MangaType : Type {
        private val owners = mapOf(
            1L to Manga.create().copy(id = 1L, source = 10L, title = "first"),
            2L to Manga.create().copy(id = 2L, source = 20L, title = "second"),
            3L to Manga.create().copy(id = 3L, source = LocalSource.ID, title = "local"),
        )
        private val folders = mapOf(1L to setOf("Ch 1"), 2L to setOf("Ch 2"))
        private val manager = mockk<DownloadManager> {
            every { getDownloadedChapterIds(any(), any()) } answers {
                val names = folders[secondArg<Manga>().id].orEmpty()
                firstArg<List<Chapter>>().filter { it.name in names }.mapTo(HashSet()) { it.id }
            }
            every { isChapterDownloaded(any(), any(), any(), any(), any()) } returns false
        }

        override fun downloaded(chapters: List<Triple<Long, Long, String>>): Set<Long> = manager.mangaDownloadedIds(
            chapters.map { (id, owner, name) -> Chapter.create().copy(id = id, mangaId = owner, name = name) },
        ) { owners.getValue(it.mangaId) }

        override fun localCopyOnDevice() = owners.getValue(3L).let {
            manager.mangaOnDevice("Ch 31", null, "/31", it.title, it.source)
        }

        override fun toString() = "manga"
    }

    private object NovelType : Type {
        private val owners = mapOf(
            1L to Novel.create().copy(id = 1L, source = "a", title = "One"),
            2L to Novel.create().copy(id = 2L, source = "b", title = "Two"),
            3L to Novel.create().copy(id = 3L, source = LocalNovelSource.ID, title = "Local"),
        )
        private val folders = mapOf(1L to setOf("Ch 1"), 2L to setOf("Ch 2"))
        private val cache = mockk<NovelDownloadCache> {
            every { downloadedChapterIds(any<Novel>(), any()) } answers {
                val names = folders[firstArg<Novel>().id].orEmpty()
                secondArg<List<NovelChapter>>().filter { it.name in names }.mapTo(HashSet()) { it.id }
            }
            every { isChapterDownloaded(any(), any(), any(), any()) } returns false
        }

        override fun downloaded(chapters: List<Triple<Long, Long, String>>): Set<Long> =
            cache.novelDownloadedIds(chapters.map { (id, owner, name) -> chapter(id, owner, name) }, owners)

        override fun localCopyOnDevice() = cache.novelOnDevice(owners.getValue(3L), chapter(31L, 3L, "Ch 31"))

        private fun chapter(id: Long, novelId: Long, name: String) = NovelChapter(
            id = id,
            novelId = novelId,
            url = "/c/$id",
            name = name,
            read = false,
            bookmark = false,
            lastTextProgress = 0L,
            chapterNumber = id.toDouble(),
            sourceOrder = id,
            dateFetch = Instant.fromEpochMilliseconds(0L),
            dateUpload = null,
            page = "",
        )

        override fun toString() = "novels"
    }

    companion object {
        @JvmStatic
        fun types() = listOf(MangaType, NovelType)
    }
}
