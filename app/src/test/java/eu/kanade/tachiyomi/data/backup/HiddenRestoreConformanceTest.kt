package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupMergeGroupsStored
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.backup.RestoreHiddenEntries
import reikai.data.library.LibraryHiddenRepositoryImpl
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.NoVolumePicks
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.ContentType
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.Novel
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/**
 * A backup's hidden entries through the real restore: BackupRestorer's two streams over each type's
 * real restorer, merge groups and hide table, on one in-memory database.
 */
class HiddenRestoreConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("worlds")
    fun `a hidden entry in a merged group restores hidden`(world: HiddenWorld) = runTest {
        world.use {
            it.restore(
                listOf(HiddenRestoreEntry("a", hidden = true), HiddenRestoreEntry("b")),
                merges = listOf(listOf("a", "b")),
            )

            it.hiddenUrls() shouldBe setOf("a")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("worlds")
    fun `the device's own hidden group, not in the backup, stays hidden`(world: HiddenWorld) = runTest {
        world.use {
            it.device("c", favourite = true)
            it.device("d", favourite = true)
            it.group("c", "d")
            it.hide("c", "d")

            it.restore(listOf(HiddenRestoreEntry("e")))

            it.hiddenUrls() shouldBe setOf("c", "d")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("worlds")
    fun `an older backup without the flag leaves the device's hides alone`(world: HiddenWorld) = runTest {
        world.use {
            it.device("c", favourite = true)
            it.hide("c")

            it.restore(listOf(HiddenRestoreEntry("c")))

            it.hiddenUrls() shouldBe setOf("c")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("worlds")
    fun `a hidden entry stays hidden when a later entry of its group rejoins the library`(
        world: HiddenWorld,
    ) = runTest {
        world.use {
            it.device("a", favourite = false)
            it.device("b", favourite = false)
            it.group("a", "b")

            it.restore(listOf(HiddenRestoreEntry("a", hidden = true), HiddenRestoreEntry("b")))

            it.hiddenUrls() shouldBe setOf("a")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("worlds")
    fun `the device's hidden group stays hidden when the restore brings back a member it removed`(
        world: HiddenWorld,
    ) = runTest {
        world.use {
            it.device("c", favourite = true)
            it.device("d", favourite = false)
            it.group("c", "d")
            it.hide("c")

            it.restore(listOf(HiddenRestoreEntry("d")))

            it.hiddenUrls() shouldBe setOf("c")
        }
    }

    companion object {
        @JvmStatic
        fun worlds() = listOf(MangaHiddenWorld(), NovelHiddenWorld())
    }
}

data class HiddenRestoreEntry(val url: String, val hidden: Boolean = false)

/** One content type's restore over a fresh in-memory database. */
abstract class HiddenWorld(private val type: ContentType) : AutoCloseable {
    private var driver: JdbcSqliteDriver? = null
    protected lateinit var database: Database
    protected val merges by lazy { MergeGroupRepositoryImpl(database) }
    private val hiddenRepo by lazy { LibraryHiddenRepositoryImpl(database) }
    protected val restoreHidden by lazy { RestoreHiddenEntries(hiddenRepo) }

    suspend fun use(block: suspend (HiddenWorld) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { driver = it }
        Database.Schema.create(driver).await()
        driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
        database = DatabaseBindings.providesDatabase(driver)
        try {
            block(this)
        } finally {
            close()
        }
    }

    abstract suspend fun device(url: String, favourite: Boolean)
    abstract suspend fun idOf(url: String): Long
    abstract suspend fun restore(entries: List<HiddenRestoreEntry>, merges: List<List<String>> = emptyList())
    abstract suspend fun hiddenIds(): List<Long>

    suspend fun group(vararg urls: String) {
        merges.createGroup(type, urls.map { idOf(it) })
    }

    suspend fun hide(vararg urls: String) {
        hiddenRepo.hide(type, urls.map { idOf(it) })
    }

    suspend fun hiddenUrls(): Set<String> {
        val ids = hiddenIds().toSet()
        return listOf("a", "b", "c", "d", "e").filter { url -> runCatching { idOf(url) }.getOrNull() in ids }.toSet()
    }

    override fun close() {
        driver?.close()
    }
}

class MangaHiddenWorld : HiddenWorld(ContentType.MANGA) {
    override fun toString() = "manga"

    override suspend fun device(url: String, favourite: Boolean) {
        database.mangaQueries.insertReturningId(
            sourceId = SOURCE, remoteUrl = url, remoteArtist = null, remoteAuthor = null, remoteDescription = null,
            remoteGenre = null, remoteTitle = url, remoteStatus = 0, remoteCover = null,
            userFavoriteAt = if (favourite) Instant.fromEpochMilliseconds(1000) else null,
            stateChapterLastUpdate = null, stateChapterNextUpdate = null, stateChapterFetchInterval = 0,
            stateInitialized = false, userReaderFlags = 0, userChapterFlags = 0,
            stateCoverLastModified = Instant.fromEpochMilliseconds(0),
            remoteUpdateStrategy = Manga.create().updateStrategy, userNotes = "", remoteMemo = Manga.create().memo,
        ).awaitAsOne()
    }

    override suspend fun idOf(url: String): Long =
        checkNotNull(MangaRepositoryImpl(database).getMangaByUrlAndSourceId(url, SOURCE)?.id)

    override suspend fun restore(entries: List<HiddenRestoreEntry>, merges: List<List<String>>) {
        restoreEncoded(
            Backup(
                backupManga = entries.map {
                    BackupManga(
                        source = SOURCE,
                        url = it.url,
                        title = it.url,
                        dateAdded = 2000,
                        libraryHidden = it.hidden,
                    )
                },
                backupMangaMerges = merges.map { group ->
                    BackupMangaMergeGroup(group.map { BackupMangaSourceRef(it, SOURCE) })
                },
                backupMergeGroupsStored = BackupMergeGroupsStored(),
            ),
            RestoreOptions(libraryEntries = true, categories = false, appSettings = false),
            mangaRestorer = mangaRestorer(database, mergeGroups = this.merges),
            restoreHiddenEntries = restoreHidden,
        )
    }

    override suspend fun hiddenIds() = database.library_hiddenQueries.selectAllManga().awaitAsList()

    private companion object {
        const val SOURCE = 1L
    }
}

class NovelHiddenWorld : HiddenWorld(ContentType.NOVELS) {
    override fun toString() = "novel"

    private val novels by lazy { NovelRepositoryImpl(database) }

    override suspend fun device(url: String, favourite: Boolean) {
        val at = if (favourite) Instant.fromEpochMilliseconds(1000) else null
        checkNotNull(novels.insert(Novel.create().copy(source = SOURCE, url = url, title = url, favoriteAt = at)))
    }

    override suspend fun idOf(url: String): Long = checkNotNull(novels.getByUrlAndSource(url, SOURCE)?.id)

    override suspend fun restore(entries: List<HiddenRestoreEntry>, merges: List<List<String>>) {
        restoreEncoded(
            Backup(
                backupManga = emptyList(),
                backupNovels = entries.map {
                    BackupNovel(
                        source = SOURCE,
                        url = it.url,
                        title = it.url,
                        dateAdded = 2000,
                        libraryHidden = it.hidden,
                    )
                },
                backupNovelMerges = merges.map { group ->
                    BackupNovelMergeGroup(group.map { BackupNovelSourceRef(it, SOURCE) })
                },
                backupMergeGroupsStored = BackupMergeGroupsStored(),
            ),
            RestoreOptions(libraryEntries = true, categories = false, appSettings = false),
            novelRestorer = NovelRestorer(
                tombstones = NoChapterTombstoneRepository,
                manualOrder = FakeLibraryManualOrderRepository(),
                novelRepository = novels,
                novelChapterRepository = NovelChapterRepositoryImpl(database),
                categoryRepository = CategoryRepositoryImpl(database),
                novelTrackRepository = mockk(relaxed = true),
                restoreMergeGroups = RestoreMergeGroups(this.merges, PassThroughTransactions),
                setCustomNovelInfo = mockk(relaxed = true),
                novelHistoryRepository = mockk(relaxed = true),
                chapterNumberOverrides = NoChapterNumberOverrides,
                copyMarks = InMemoryChapterCopyMarks(),
                localVolumes = NoLocalVolumes,
                volumePicks = NoVolumePicks,
                readingLog = NoReadingLog,
                readingPace = NoReadingPace,
                localNovelLanguages = NoLocalNovelLanguages,
            ),
            restoreHiddenEntries = restoreHidden,
        )
    }

    override suspend fun hiddenIds() = database.library_hiddenQueries.selectAllNovel().awaitAsList()

    private companion object {
        const val SOURCE = "src"
    }
}
