package reikai.domain.library

import android.content.Context
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.track.source.SourceTrackerDispatcher
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File

/**
 * The one library removal both content types and every surface take entries out through, run over each
 * type's half against rows in memory and a real cover cache on disk. Mihon's heart order: the write,
 * then the covers, stamped only when one was deleted.
 */
class EntryLibraryRemovalConformanceTest {

    @TempDir
    lateinit var dir: File

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a removed entry leaves the library`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID) }

        table.removal.await(listOf(ID))

        table.favoriteAt(ID) shouldBe null
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the tracker hand-out runs while the entry is still in the library`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID) }

        table.removal.await(listOf(ID))

        table.inLibraryAtHandOut shouldBe listOf(true)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the source's own tracker hears the removal`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID) }

        table.removal.await(listOf(ID))

        table.toldSourceTracker shouldBe listOf(table.entryId(ID) to false)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a removed entry loses its cached cover`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID) }
        val cover = table.cachedCover(ID).also { it.writeText("x") }

        table.removal.await(listOf(ID))

        cover.exists() shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a removed entry loses its custom cover`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID) }
        val custom = table.cache.getCustomCoverFile(table.entryId(ID)).also { it.writeText("x") }

        table.removal.await(listOf(ID))

        custom.exists() shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a deleted cover gets a new cover stamp`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID) }
        table.cachedCover(ID).writeText("x")

        table.removal.await(listOf(ID))

        table.coverStamps shouldBe listOf(ID)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `no cover deleted means no cover stamp`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID) }

        table.removal.await(listOf(ID))

        table.coverStamps shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a refused write takes none of the batch out`(type: ContentType) = runTest {
        val table = table(type).apply {
            put(ID)
            put(OTHER_ID)
            refusedIds += OTHER_ID
        }

        table.removal.await(listOf(ID, OTHER_ID))

        table.favoriteAt(ID) shouldBe JOINED_AT
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a refused write keeps the covers`(type: ContentType) = runTest {
        val table = table(type).apply {
            put(ID)
            refusedIds += ID
        }
        val cover = table.cachedCover(ID).also { it.writeText("x") }

        table.removal.await(listOf(ID))

        cover.exists() shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an entry outside the library is not taken out again`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID, joinedAt = null) }

        table.removal.await(listOf(ID)) shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `taking out leaves the covers for the undo window`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID) }
        val cover = table.cachedCover(ID).also { it.writeText("x") }

        table.removal.takeOut(listOf(ID))

        cover.exists() shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an undo puts back the date the entry joined the library`(type: ContentType) = runTest {
        val table = table(type).apply { put(ID) }

        table.removal.restore(table.removal.takeOut(listOf(ID)))

        table.favoriteAt(ID) shouldBe JOINED_AT
    }

    private fun table(type: ContentType): Table = when (type) {
        ContentType.MANGA -> MangaTable(coverCache())
        else -> NovelTable(coverCache())
    }

    private fun coverCache(): CoverCache {
        val context = mockk<Context> {
            every { getExternalFilesDir(any()) } answers { File(dir, firstArg<String>()).also { it.mkdirs() } }
        }
        return CoverCache(context)
    }

    /** One content type's rows, with what the removal told the trackers and wrote. */
    private abstract class Table(val cache: CoverCache) {
        val refusedIds = mutableSetOf<Long>()
        val coverStamps = mutableListOf<Long>()
        val inLibraryAtHandOut = mutableListOf<Boolean>()
        val toldSourceTracker = mutableListOf<Pair<EntryId, Boolean>>()

        protected val sourceTracker = mockk<SourceTrackerDispatcher> {
            every { favoriteChanged(any(), any()) } answers { toldSourceTracker += firstArg<EntryId>() to secondArg() }
        }

        abstract val removal: EntryLibraryRemoval
        abstract fun entryId(id: Long): EntryId
        abstract fun put(id: Long, joinedAt: Long? = JOINED_AT)
        abstract fun favoriteAt(id: Long): Long?
        abstract fun cachedCover(id: Long): File

        protected fun handingOut(ids: List<Long>) {
            ids.forEach { inLibraryAtHandOut += favoriteAt(it) != null }
        }
    }

    private class MangaTable(cache: CoverCache) : Table(cache) {
        private val rows = mutableMapOf<Long, Manga>()

        private val repository = mockk<MangaRepository> {
            coEvery { updateAll(any()) } answers {
                val updates = firstArg<List<MangaUpdate>>()
                if (updates.any { it.id in refusedIds }) return@answers false
                updates.forEach { rows[it.id] = rows.getValue(it.id).copy(favoriteAt = it.favoriteAt) }
                true
            }
            coEvery { update(any()) } answers {
                val update = firstArg<MangaUpdate>()
                if (update.isSet(MangaUpdate::coverLastModified)) coverStamps += update.id
                true
            }
        }

        override val removal = RemoveMangaFromLibrary(
            mergeManager = mockk<MangaMergeManager> {
                coEvery { handOutTrackersBeforeRemoval(any()) } answers { handingOut(firstArg()) }
            },
            sourceTracker = sourceTracker,
            getManga = mockk<GetManga> { coEvery { await(any<Long>()) } answers { rows[firstArg()] } },
            updateManga = UpdateManga(repository, mockk(), mockk(relaxed = true)),
            coverCache = cache,
        )

        override fun entryId(id: Long) = EntryId.Manga(id)

        override fun put(id: Long, joinedAt: Long?) {
            rows[id] =
                Manga.create().copy(
                    id = id,
                    source = 99L,
                    thumbnailUrl = "https://example.org/$id.jpg",
                    favoriteAt = joinedAt,
                )
        }

        override fun favoriteAt(id: Long) = rows.getValue(id).favoriteAt

        override fun cachedCover(id: Long) = cache.getCoverFile(rows.getValue(id).thumbnailUrl)!!
    }

    private class NovelTable(cache: CoverCache) : Table(cache) {
        private val rows = mutableMapOf<Long, Novel>()

        private val repository = mockk<NovelRepository> {
            coEvery { getById(any()) } answers { rows[firstArg()] }
            coEvery { updateAll(any()) } answers {
                val updates = firstArg<List<NovelUpdate>>()
                if (updates.any { it.id in refusedIds }) return@answers false
                updates.forEach { rows[it.id] = rows.getValue(it.id).copy(favoriteAt = it.favoriteAt) }
                true
            }
            coEvery { update(any<NovelUpdate>()) } answers {
                val update = firstArg<NovelUpdate>()
                if (update.isSet(NovelUpdate::coverLastModified)) coverStamps += update.id
                true
            }
        }

        override val removal = RemoveNovelsFromLibrary(
            mergeManager = mockk<NovelMergeManager> {
                coEvery { handOutTrackersBeforeRemoval(any()) } answers { handingOut(firstArg()) }
            },
            sourceTracker = sourceTracker,
            novelRepository = repository,
            updateNovel = UpdateNovel(repository, mockk(relaxed = true)),
            coverCache = cache,
        )

        override fun entryId(id: Long) = EntryId.Novel(id)

        override fun put(id: Long, joinedAt: Long?) {
            rows[id] = Novel.create().copy(id = id, thumbnailUrl = "https://example.org/$id.jpg", favoriteAt = joinedAt)
        }

        override fun favoriteAt(id: Long) = rows.getValue(id).favoriteAt

        override fun cachedCover(id: Long) = cache.getCoverFile(rows.getValue(id).thumbnailUrl)!!
    }

    private companion object {
        const val ID = 5L
        const val OTHER_ID = 6L
        const val JOINED_AT = 100L
    }
}
