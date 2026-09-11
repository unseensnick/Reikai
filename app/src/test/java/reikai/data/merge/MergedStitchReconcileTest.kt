package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaGroupStitcher
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.MergedChapterUnitRepository.StoredUnit
import reikai.domain.merge.MergedGroupStitcher
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelGroupStitcher
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.data.Chapters
import tachiyomi.data.Custom_manga_info
import tachiyomi.data.Custom_novel_info
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Novels
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * When the stored stitch is rebuilt. Every surface reads it rather than stitching for itself, so a
 * change that should reshape a group's list and is not seen as staleness leaves every screen on the
 * old list. The ranking is one of those inputs: it picks the trunk, so a reorder or a new preferred
 * source has to reach the stitch as surely as a new chapter does. Runs the real stitchers and SQL over
 * both content types; only chapter and entry loading are stubbed, from the same rows the SQL sees.
 */
class MergedStitchReconcileTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var groups: MergeGroupRepositoryImpl
    private lateinit var units: MergedChapterUnitRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            val database = Database(
                driver = driver,
                historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
                novelsAdapter = Novels.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
                custom_manga_infoAdapter = Custom_manga_info.Adapter(genreAdapter = StringListColumnAdapter),
                custom_novel_infoAdapter = Custom_novel_info.Adapter(genreAdapter = StringListColumnAdapter),
            )
            groups = MergeGroupRepositoryImpl(database)
            units = MergedChapterUnitRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `reordering a group's sources restitches it on the new trunk`(type: ContentType) = runTest {
        val fixture = fixture(type)
        val group = fixture.twoSourceGroup()
        val reconcile = fixture.reconcile()
        reconcile.awaitGroup(type, group)

        groups.setSourceOrder(type, group, fixture.membersOf(group).reversed())
        reconcile.awaitGroup(type, group)

        fixture.ownerOfFirstChapter(group) shouldBe SECOND
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `resetting a group's source order restitches it on the global ranking`(type: ContentType) = runTest {
        val fixture = fixture(type)
        val group = fixture.twoSourceGroup()
        groups.setSourceOrder(type, group, fixture.membersOf(group).reversed())
        val reconcile = fixture.reconcile()
        reconcile.awaitGroup(type, group)

        groups.clearSourceOrder(type, group)
        reconcile.awaitGroup(type, group)

        fixture.ownerOfFirstChapter(group) shouldBe FIRST
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a changed preferred-source list restitches the group`(type: ContentType) = runTest {
        val fixture = fixture(type)
        val group = fixture.twoSourceGroup()
        fixture.reconcile().awaitGroup(type, group)

        // A preference store reads its values once, so the changed list is a new stitcher over it.
        fixture.reconcile(preferredSources = listOf(SECOND_SOURCE)).await()

        fixture.ownerOfFirstChapter(group) shouldBe SECOND
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a group nothing changed in is not stitched again`(type: ContentType) = runTest {
        val fixture = fixture(type)
        val group = fixture.twoSourceGroup()
        val counting = CountingStitcher(fixture.stitcher(emptyList()))
        val reconcile = ReconcileMergedChapters(units, setOf(counting))
        reconcile.awaitGroup(type, group)

        reconcile.awaitGroup(type, group)

        counting.stitched shouldBe listOf(group)
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a new chapter in one group leaves another group's stitch alone`(type: ContentType) = runTest {
        val fixture = fixture(type)
        val settled = fixture.twoSourceGroup()
        val changed = fixture.twoSourceGroup()
        val counting = CountingStitcher(fixture.stitcher(emptyList()))
        val reconcile = ReconcileMergedChapters(units, setOf(counting))
        reconcile.await()
        fixture.addChapter(owner = fixture.membersOf(changed).first(), name = "Chapter 3: Charlie", number = 3.0)

        reconcile.awaitGroup(type, settled)

        counting.stitched.count { it == settled } shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a new chapter in a group restitches that group`(type: ContentType) = runTest {
        val fixture = fixture(type)
        val group = fixture.twoSourceGroup()
        val counting = CountingStitcher(fixture.stitcher(emptyList()))
        val reconcile = ReconcileMergedChapters(units, setOf(counting))
        reconcile.awaitGroup(type, group)
        fixture.addChapter(owner = fixture.membersOf(group).first(), name = "Chapter 3: Charlie", number = 3.0)

        reconcile.awaitGroup(type, group)

        counting.stitched shouldBe listOf(group, group)
    }

    /** Records which groups were stitched, so a test can tell a rebuild from a read of what was stored. */
    private class CountingStitcher(private val inner: MergedGroupStitcher) : MergedGroupStitcher by inner {
        val stitched = mutableListOf<Long>()

        override suspend fun stitch(groupId: Long): List<StoredUnit> {
            stitched += groupId
            return inner.stitch(groupId)
        }
    }

    private fun fixture(type: ContentType): Fixture = when (type) {
        ContentType.NOVELS -> NovelFixture()
        else -> MangaFixture()
    }

    /** One content type's rows and stitcher, over the shared database. */
    private abstract inner class Fixture {
        private var nextEntryId = 1L
        private var nextChapterId = 1L
        private val members = mutableMapOf<Long, List<Long>>()

        abstract val type: ContentType

        abstract suspend fun insertEntry(id: Long, source: Long)

        abstract suspend fun insertChapter(id: Long, owner: Long, name: String, number: Double)

        abstract fun stitcher(preferredSources: List<Long>): MergedGroupStitcher

        fun reconcile(preferredSources: List<Long> = emptyList()) =
            ReconcileMergedChapters(units, setOf(stitcher(preferredSources)))

        /**
         * A group of two members carrying the same two chapters, the first from [FIRST_SOURCE] and the
         * second from [SECOND_SOURCE]. Nothing tells them apart, so with no ranking the lower id leads.
         */
        suspend fun twoSourceGroup(): Long {
            val ids = listOf(FIRST_SOURCE, SECOND_SOURCE).map { source ->
                val id = nextEntryId++
                insertEntry(id, source)
                addChapter(id, "Chapter 1: Alpha", 1.0)
                addChapter(id, "Chapter 2: Bravo", 2.0)
                id
            }
            val group = groups.createGroup(type, ids)!!
            members[group] = ids
            return group
        }

        fun membersOf(group: Long) = members.getValue(group)

        suspend fun addChapter(owner: Long, name: String, number: Double) {
            insertChapter(nextChapterId++, owner, name, number)
        }

        /** Which member's copy the stored stitch shows for the group's first chapter, as its position. */
        suspend fun ownerOfFirstChapter(group: Long): Long {
            val shown = units.getStitch(type, group).first { it.unit == 0 && it.copyOrder == 0 }
            return membersOf(group).indexOf(ownerOf(shown.chapterId)).toLong()
        }

        abstract fun ownerOf(chapterId: Long): Long
    }

    private inner class MangaFixture : Fixture() {
        private val mangas = mutableMapOf<Long, Manga>()
        private val chapters = mutableListOf<Chapter>()

        override val type = ContentType.MANGA

        override suspend fun insertEntry(id: Long, source: Long) {
            mangas[id] = Manga.create().copy(id = id, source = source, favorite = true)
            driver.execute(
                null,
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added) " +
                    "VALUES ($id, $source, 'm-url-$id', 'title', 0, 1, 0, 0, 0, 0, 0)",
                0,
            ).await()
        }

        override suspend fun insertChapter(id: Long, owner: Long, name: String, number: Double) {
            chapters += Chapter.create().copy(
                id = id,
                mangaId = owner,
                url = "/$owner/$id",
                name = name,
                chapterNumber = number,
                sourceOrder = id,
            )
            driver.execute(
                null,
                "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, " +
                    "last_page_read, chapter_number, source_order, date_fetch, date_upload) " +
                    "VALUES ($id, $owner, '/$owner/$id', '$name', NULL, 0, 0, 0, $number, $id, 0, 0)",
                0,
            ).await()
        }

        override fun stitcher(preferredSources: List<Long>): MergedGroupStitcher {
            val prefs = preferences(InMemoryPreference("preferred_manga_sources", preferredSources, emptyList()))
            val mangaRepository = mockk<MangaRepository> {
                coEvery { getMangaById(any()) } answers { mangas.getValue(firstArg()) }
            }
            val chapterRepository = mockk<ChapterRepository> {
                coEvery { getChapterByMangaId(any(), any()) } answers {
                    chapters.filter { it.mangaId == firstArg<Long>() }
                }
            }
            val sourceManager = mockk<SourceManager>()
            coEvery { sourceManager.get(any<Long>()) } returns null
            return MangaGroupStitcher(
                groups,
                GetMangaWithChapters(mangaRepository, chapterRepository),
                MangaMergeManager(groups, prefs) {},
                sourceManager,
                prefs,
            )
        }

        override fun ownerOf(chapterId: Long) = chapters.first { it.id == chapterId }.mangaId
    }

    private inner class NovelFixture : Fixture() {
        private val novels = mutableMapOf<Long, Novel>()
        private val chapters = mutableListOf<NovelChapter>()

        override val type = ContentType.NOVELS

        override suspend fun insertEntry(id: Long, source: Long) {
            novels[id] = Novel.create().copy(id = id, source = source.toString(), favorite = true)
            driver.execute(
                null,
                "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags) " +
                    "VALUES ($id, '$source', 'n-url-$id', 'title', 0, 1, 0, 0)",
                0,
            ).await()
        }

        override suspend fun insertChapter(id: Long, owner: Long, name: String, number: Double) {
            chapters += NovelChapter(
                id = id,
                novelId = owner,
                url = "/$owner/$id",
                name = name,
                read = false,
                bookmark = false,
                lastTextProgress = 0,
                chapterNumber = number,
                sourceOrder = id,
                dateFetch = 0L,
                dateUpload = 0L,
                page = "",
            )
            driver.execute(
                null,
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, " +
                    "source_order, date_fetch, date_upload) " +
                    "VALUES ($id, $owner, '/$owner/$id', '$name', 0, 0, $number, $id, 0, 0)",
                0,
            ).await()
        }

        override fun stitcher(preferredSources: List<Long>): MergedGroupStitcher {
            val prefs = preferences(
                InMemoryPreference("preferred_novel_sources", preferredSources.map(Long::toString), emptyList()),
            )
            val novelRepository = mockk<NovelRepository> {
                coEvery { getById(any()) } answers { novels[firstArg()] }
            }
            val chapterRepository = mockk<NovelChapterRepository> {
                coEvery { getByNovelId(any()) } answers { chapters.filter { it.novelId == firstArg<Long>() } }
            }
            return NovelGroupStitcher(
                groups,
                novelRepository,
                chapterRepository,
                NovelMergeManager(groups, prefs) {},
                prefs,
            )
        }

        override fun ownerOf(chapterId: Long) = chapters.first { it.id == chapterId }.novelId
    }

    private fun preferences(preferredSources: InMemoryPreference<*>) =
        ReikaiLibraryPreferences(InMemoryPreferenceStore(sequenceOf(preferredSources)))

    companion object {
        // Positions in the group, which is what the ranking reorders.
        private const val FIRST = 0L
        private const val SECOND = 1L
        private const val FIRST_SOURCE = 100L
        private const val SECOND_SOURCE = 200L
    }
}
