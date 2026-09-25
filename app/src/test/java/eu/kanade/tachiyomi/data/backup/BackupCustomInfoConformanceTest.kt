package eu.kanade.tachiyomi.data.backup

import android.net.Uri
import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupCustomMangaInfo
import eu.kanade.tachiyomi.data.backup.models.BackupCustomNovelInfo
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.LegacyCustomInfo
import eu.kanade.tachiyomi.data.backup.models.customInfo
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.repository.CustomNovelInfoRepository
import tachiyomi.data.Database
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Custom info rides on each backed-up entry for both content types, and restore reads it only from
 * there: an older Reikai backup's root list is folded onto its entries first. Runs each type's real
 * restorer and, for the write side, the real creators behind one BackupCreator.
 */
class BackupCustomInfoConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `custom info carried on an entry restores onto it`(restorer: CustomInfoRestorer) = runTest {
        restorer.restore(onEntry = EVERY_FIELD) shouldBe listOf(LOCAL_ID to EVERY_FIELD)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `an older Reikai backup's root list restores its custom info`(restorer: CustomInfoRestorer) = runTest {
        restorer.restore(inRootList = EVERY_FIELD) shouldBe listOf(LOCAL_ID to EVERY_FIELD)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `an entry's own custom info wins over the root list`(restorer: CustomInfoRestorer) = runTest {
        restorer.restore(onEntry = EVERY_FIELD, inRootList = BackupCustomInfo(title = "Older")) shouldBe
            listOf(LOCAL_ID to EVERY_FIELD)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `an older Reikai backup's root list reaches an entry the backup does not list`(restorer: CustomInfoRestorer) =
        runTest {
            restorer.restore(inRootList = EVERY_FIELD, listed = false) shouldBe listOf(LOCAL_ID to EVERY_FIELD)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `an entry without custom info leaves the device's own untouched`(restorer: CustomInfoRestorer) = runTest {
        restorer.restore() shouldBe emptyList()
    }

    @Test
    fun `a backup written now carries custom info on each entry and no root list`() = runTest {
        val backup = writeBackup()

        listOf(
            backup.backupManga.single().customInfo,
            backup.backupNovels.single().customInfo,
            backup.backupCustomMangaInfo.size + backup.backupCustomNovelInfo.size,
        ) shouldBe listOf(EVERY_FIELD, EVERY_FIELD, 0)
    }

    @Test
    fun `a backup written with custom info off leaves it off every entry`() = runTest {
        val backup = writeBackup(BACKUP_OPTIONS.copy(customInfo = false))

        listOf(backup.backupManga.single().customInfo, backup.backupNovels.single().customInfo) shouldBe
            listOf(null, null)
    }

    @Test
    fun `a backup names the source of each novel it carries`() = runTest {
        writeBackup().backupNovelSources shouldBe listOf(BackupNovelSource(name = "Novel source", sourceId = "src"))
    }

    /** One favorite manga and one favorite novel, each id 7 with [EVERY_FIELD] stored, through BackupCreator. */
    private suspend fun writeBackup(options: BackupOptions = BACKUP_OPTIONS): Backup {
        val out = ByteArrayOutputStream()
        val uri = mockk<Uri>()
        mockkStatic(UniFile::class)
        try {
            every { UniFile.fromUri(any(), any()) } returns mockk {
                every { isFile } returns true
                every { openOutputStream() } returns out
                every { this@mockk.uri } returns uri
            }
            // No excluded scanlators: a query whose cursor is empty.
            val scanlators = object : Query<String>({ it.getString(0)!! }) {
                override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>) = mapper(
                    object : SqlCursor {
                        override fun next() = QueryResult.Value(false)
                        override fun getString(index: Int): String? = null
                        override fun getLong(index: Int): Long? = null
                        override fun getBytes(index: Int): ByteArray? = null
                        override fun getDouble(index: Int): Double? = null
                        override fun getBoolean(index: Int): Boolean? = null
                    },
                )
                override fun addListener(listener: Query.Listener) = Unit
                override fun removeListener(listener: Query.Listener) = Unit
            }
            val database = mockk<Database>(relaxed = true) {
                every { excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(7) } returns scanlators
            }
            val mangaCustomInfo = mockk<CustomMangaInfoRepository> {
                every { getByMangaIdAsFlow(7) } returns flowOf(EVERY_FIELD.toManga(7))
            }
            val novelCustomInfo = mockk<CustomNovelInfoRepository> {
                every { getByNovelIdAsFlow(7) } returns flowOf(EVERY_FIELD.toNovel(7))
            }
            val creator = BackupCreator(
                isAutoBackup = false,
                context = mockk(relaxed = true),
                parser = ProtoBuf,
                backupPreferences = mockk(relaxed = true),
                mangaRepository = mockk(relaxed = true),
                mergeGroupRepository = mockk { coEvery { getAllMemberships(any()) } returns emptyMap() },
                categoriesBackupCreator = mockk(relaxed = true),
                mangaBackupCreator = MangaBackupCreator(
                    database = database,
                    getCategories = mockk(relaxed = true),
                    getHistory = mockk(relaxed = true),
                    mangaMetadataRepository = mockk { coEvery { getMetadataById(7) } returns null },
                    customMangaInfoRepository = mangaCustomInfo,
                    getFavorites = mockk {
                        coEvery { await() } returns listOf(Manga.create().copy(id = 7, url = "/7", source = 1L))
                    },
                    mangaRepository = mockk(),
                    mergeGroupRepository = mockk { coEvery { getAllMemberships(any()) } returns emptyMap() },
                ),
                preferenceBackupCreator = mockk(relaxed = true),
                extensionStoresBackupCreator = mockk(relaxed = true),
                sourcesBackupCreator = mockk { coEvery { forSourceIds(any()) } returns emptyList() },
                backupFileValidator = mockk(relaxed = true),
                novelBackupCreator = NovelBackupCreator(
                    novelRepository = mockk {
                        coEvery { getFavorites() } returns
                            listOf(Novel.create().copy(id = 7, url = "/7", source = "src", favorite = true))
                    },
                    novelChapterRepository = mockk(),
                    categoryRepository = mockk(),
                    novelTrackRepository = mockk(),
                    mergeGroupRepository = mockk { coEvery { getAllMemberships(any()) } returns emptyMap() },
                    customNovelInfoRepository = novelCustomInfo,
                    database = mockk(),
                    novelSourceManager = mockk { coEvery { nameOf("src") } returns "Novel source" },
                ),
                extensionBackupCreator = mockk(relaxed = true),
                feedBackupCreator = mockk(relaxed = true),
            )

            creator.backup(uri, options)
        } finally {
            unmockkStatic(UniFile::class)
        }
        val bytes = GZIPInputStream(out.toByteArray().inputStream()).readBytes()
        return ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
    }

    companion object {
        const val LOCAL_ID = 10L

        val EVERY_FIELD = BackupCustomInfo(
            title = "My title",
            author = "My author",
            artist = "My artist",
            description = "My description",
            genre = listOf("Action", "Drama"),
            status = 2L,
            thumbnailUrl = "https://example.org/cover.jpg",
        )

        // Library entries plus custom info, everything else off so only the two entries are read.
        val BACKUP_OPTIONS = BackupOptions(
            categories = false,
            chapters = false,
            tracking = false,
            history = false,
            readEntries = false,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            savedSearches = false,
        )

        @JvmStatic
        fun restorers() = listOf(MangaCustomInfoRestorer(), NovelCustomInfoRestorer())
    }
}

fun BackupCustomInfo.toManga(id: Long) =
    CustomMangaInfo(id, title, author, artist, description, genre, status, thumbnailUrl)

fun BackupCustomInfo.toNovel(id: Long) =
    CustomNovelInfo(id, title, author, artist, description, genre, status, thumbnailUrl)

/** One type's restore of a single entry already on the device under [BackupCustomInfoConformanceTest.LOCAL_ID]. */
interface CustomInfoRestorer {

    /**
     * Restores the entry with [onEntry] on its own fields and [inRootList] in an older backup's root list,
     * decoded from real bytes, and returns what was written as (local id, custom info). An entry not
     * [listed] in the backup is only on the device, as a 0.3.x novel out of the library was.
     */
    suspend fun restore(
        onEntry: BackupCustomInfo? = null,
        inRootList: BackupCustomInfo? = null,
        listed: Boolean = true,
    ): List<Pair<Long, BackupCustomInfo>>
}

class MangaCustomInfoRestorer : CustomInfoRestorer {

    override fun toString() = "manga"

    override suspend fun restore(
        onEntry: BackupCustomInfo?,
        inRootList: BackupCustomInfo?,
        listed: Boolean,
    ): List<Pair<Long, BackupCustomInfo>> {
        val backup = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(
                Backup.serializer(),
                Backup(
                    backupManga = listOf(
                        BackupManga(source = 1L, url = "u").apply {
                            customInfo = onEntry
                        },
                    ).filter { listed },
                    backupCustomMangaInfo = listOfNotNull(inRootList?.let { it.toRootEntry() }),
                ),
            ),
        )
        val legacy = LegacyCustomInfo(backup.backupCustomMangaInfo, emptyList())

        val written = mutableListOf<Pair<Long, BackupCustomInfo>>()
        val repository = mockk<CustomMangaInfoRepository> {
            coEvery { set(any()) } answers {
                val info = firstArg<CustomMangaInfo>()
                written += info.mangaId to
                    BackupCustomInfo(
                        info.title,
                        info.author,
                        info.artist,
                        info.description,
                        info.genre,
                        info.status,
                        info.thumbnailUrl,
                    )
            }
        }
        val database = mockk<Database>(relaxed = true) {
            coEvery { transaction(any(), any()) } coAnswers {
                secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>().invoke(mockk(relaxed = true))
            }
        }
        val restorer = MangaRestorer(
            database = database,
            getCategories = mockk(relaxed = true),
            getMangaByUrlAndSourceId = mockk<GetMangaByUrlAndSourceId> {
                coEvery { await("u", 1L) } returns
                    Manga.create().copy(id = BackupCustomInfoConformanceTest.LOCAL_ID, url = "u", source = 1L)
            },
            getChaptersByMangaId = mockk<GetChaptersByMangaId> {
                coEvery { await(BackupCustomInfoConformanceTest.LOCAL_ID) } returns emptyList()
            },
            updateManga = mockk(relaxed = true),
            getTracks = mockk(relaxed = true),
            insertTrack = mockk(relaxed = true),
            fetchInterval = mockk(relaxed = true),
            restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
            mangaMetadataRepository = mockk(relaxed = true),
            setCustomMangaInfo = SetCustomMangaInfo(repository),
        )
        backup.backupManga.forEach {
            restorer.restore(
                legacy.decodeManga(ProtoBuf, ProtoBuf.encodeToByteArray(BackupManga.serializer(), it)),
                emptyList(),
            )
        }
        legacy.unclaimedManga().forEach { (ref, info) -> restorer.restoreCustomInfo(ref.first, ref.second, info) }
        return written
    }

    private fun BackupCustomInfo.toRootEntry() =
        BackupCustomMangaInfo(1L, "u", title, author, artist, description, genre.orEmpty(), status, thumbnailUrl)
}

class NovelCustomInfoRestorer : CustomInfoRestorer {

    override fun toString() = "novel"

    override suspend fun restore(
        onEntry: BackupCustomInfo?,
        inRootList: BackupCustomInfo?,
        listed: Boolean,
    ): List<Pair<Long, BackupCustomInfo>> {
        val backup = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(
                Backup.serializer(),
                Backup(
                    backupManga = emptyList(),
                    backupNovels = listOf(
                        BackupNovel(source = "s", url = "u").apply {
                            customInfo = onEntry
                        },
                    ).filter { listed },
                    backupCustomNovelInfo = listOfNotNull(inRootList?.let { it.toRootEntry() }),
                ),
            ),
        )
        val legacy = LegacyCustomInfo(emptyList(), backup.backupCustomNovelInfo)

        val written = mutableListOf<Pair<Long, BackupCustomInfo>>()
        val repository = mockk<CustomNovelInfoRepository> {
            coEvery { set(any()) } answers {
                val info = firstArg<CustomNovelInfo>()
                written += info.novelId to
                    BackupCustomInfo(
                        info.title,
                        info.author,
                        info.artist,
                        info.description,
                        info.genre,
                        info.status,
                        info.thumbnailUrl,
                    )
            }
        }
        val novels = mockk<NovelRepository>(relaxed = true) {
            coEvery { getByUrlAndSource("u", "s") } returns
                Novel.create().copy(id = BackupCustomInfoConformanceTest.LOCAL_ID, url = "u", source = "s")
            coEvery { getById(BackupCustomInfoConformanceTest.LOCAL_ID) } returns null
            coEvery { update(any<Novel>(), any()) } returns true
        }
        val restorer = NovelRestorer(
            novelRepository = novels,
            novelChapterRepository = mockk(relaxed = true),
            categoryRepository = mockk(relaxed = true),
            novelTrackRepository = mockk(relaxed = true),
            restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
            setCustomNovelInfo = SetCustomNovelInfo(repository),
            database = mockk(relaxed = true),
        )
        backup.backupNovels.forEach {
            restorer.restore(
                legacy.decodeNovel(ProtoBuf, ProtoBuf.encodeToByteArray(BackupNovel.serializer(), it)),
                emptyList(),
            )
        }
        legacy.unclaimedNovels().forEach { (ref, info) -> restorer.restoreCustomInfo(ref.first, ref.second, info) }
        return written
    }

    private fun BackupCustomInfo.toRootEntry() =
        BackupCustomNovelInfo("s", "u", title, author, artist, description, genre.orEmpty(), status, thumbnailUrl)
}
