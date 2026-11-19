package eu.kanade.tachiyomi.data.backup

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.NoVolumePicks
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.ContentType
import reikai.domain.library.FakeLibraryHiddenRepository
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.Novel
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * A merge group crosses a backup as {url, source} refs, since entry ids change on restore, and comes
 * back materialized against the restored series' new ids. Each type's real creator writes the group,
 * the wire carries it, and its real restorer reads it back.
 */
class MergeGroupBackupConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `a merge group is materialized against the restored ids`(type: MergeRoundTrip) = runTest {
        type.roundTrip(restored = mapOf("a" to 10L, "b" to 20L)) shouldBe listOf(10L, 20L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `a merge group is dropped when fewer than two members resolve`(type: MergeRoundTrip) = runTest {
        type.roundTrip(restored = mapOf("a" to 10L, "b" to null)) shouldBe null
    }

    companion object {
        @JvmStatic
        fun types() = listOf(MangaMergeRoundTrip(), NovelMergeRoundTrip())
    }
}

/** One type's backup of a group of a (id 1) and b (id 5), on two sources, and its restore. */
abstract class MergeRoundTrip(private val contentType: ContentType) {

    /** Backs the group up and restores it where each url resolves to the id given, returning the ids grouped. */
    abstract suspend fun roundTrip(restored: Map<String, Long?>): List<Long>?

    protected fun memberships() = mockk<MergeGroupRepository> {
        coEvery { getAllMemberships(any()) } returns emptyMap()
        coEvery { getAllMemberships(contentType) } returns mapOf(1L to 100L, 5L to 100L)
    }

    /** A repository that records the ids a restore materializes a group of this type for. */
    protected fun recorder(onGroup: (List<Long>) -> Unit) = mockk<MergeGroupRepository>(relaxed = true) {
        coEvery { materializeGroup(contentType, any(), false) } answers {
            onGroup(secondArg())
            100L
        }
    }
}

class MangaMergeRoundTrip : MergeRoundTrip(ContentType.MANGA) {

    override fun toString() = "manga"

    override suspend fun roundTrip(restored: Map<String, Long?>): List<Long>? {
        val merges = backUp().backupMangaMerges
        var grouped: List<Long>? = null
        MangaRestorer(
            restoreRepository = mockk(relaxed = true),
            getCategories = mockk(relaxed = true),
            fetchInterval = mockk(relaxed = true),
            getMangaByUrlAndSourceId = mockk {
                coEvery { await("a", 1L) } answers { restored["a"]?.let { Manga.create().copy(id = it) } }
                coEvery { await("b", 2L) } answers { restored["b"]?.let { Manga.create().copy(id = it) } }
            },
            restoreMergeGroups = RestoreMergeGroups(recorder { grouped = it }, PassThroughTransactions),
            setCustomMangaInfo = mockk(relaxed = true),
            localVolumes = NoLocalVolumes,
            volumePicks = NoVolumePicks,
        ).restoreMerges(merges, prefEra = null)
        return grouped
    }

    // Manga's groups are written by BackupCreator itself, so the whole backup runs with only the groups in it.
    private suspend fun backUp(): Backup {
        val out = ByteArrayOutputStream()
        mockkStatic(UniFile::class)
        try {
            val uri = mockk<Uri>()
            every { UniFile.fromUri(any(), any()) } returns mockk {
                every { isFile } returns true
                every { openOutputStream() } returns out
                every { this@mockk.uri } returns uri
            }
            BackupCreator(
                isAutoBackup = false,
                context = mockk(relaxed = true),
                parser = ProtoBuf,
                backupPreferences = mockk(relaxed = true),
                mangaRepository = mockk {
                    coEvery { getMangaById(1L) } returns Manga.create().copy(id = 1L, url = "a", source = 1L)
                    coEvery { getMangaById(5L) } returns Manga.create().copy(id = 5L, url = "b", source = 2L)
                },
                mergeGroupRepository = memberships(),
                categoriesBackupCreator = mockk(relaxed = true),
                mangaBackupCreator = mockk(relaxed = true),
                preferenceBackupCreator = mockk(relaxed = true),
                extensionStoresBackupCreator = mockk(relaxed = true),
                sourcesBackupCreator = mockk { coEvery { forSourceIds(any()) } returns emptyList() },
                backupFileValidator = mockk(relaxed = true),
                novelBackupCreator = mockk(relaxed = true),
                extensionBackupCreator = mockk(relaxed = true),
                feedBackupCreator = mockk(relaxed = true),
                novelPreferences = mockk(relaxed = true),
                novelFonts = mockk { every { backedUp() } returns emptyFlow() },
            ).backup(mockk(), OPTIONS)
        } finally {
            unmockkStatic(UniFile::class)
        }
        return ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            GZIPInputStream(out.toByteArray().inputStream()).readBytes(),
        )
    }

    private companion object {
        val OPTIONS = BackupOptions(
            includeNovels = false,
            customInfo = false,
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
    }
}

class NovelMergeRoundTrip : MergeRoundTrip(ContentType.NOVELS) {

    override fun toString() = "novel"

    override suspend fun roundTrip(restored: Map<String, Long?>): List<Long>? {
        val serializer = ListSerializer(BackupNovelMergeGroup.serializer())
        val wire = ProtoBuf.encodeToByteArray(serializer, creator().novelMerges())
        var grouped: List<Long>? = null
        NovelRestorer(
            tombstones = NoChapterTombstoneRepository,
            manualOrder = FakeLibraryManualOrderRepository(),
            novelRepository = mockk {
                coEvery { getByUrlAndSource("a", "s1") } answers { restored["a"]?.let { Novel.create().copy(id = it) } }
                coEvery { getByUrlAndSource("b", "s2") } answers { restored["b"]?.let { Novel.create().copy(id = it) } }
            },
            novelChapterRepository = mockk(relaxed = true),
            categoryRepository = mockk(relaxed = true),
            novelTrackRepository = mockk(relaxed = true),
            restoreMergeGroups = RestoreMergeGroups(recorder { grouped = it }, PassThroughTransactions),
            setCustomNovelInfo = mockk(relaxed = true),
            novelHistoryRepository = mockk(relaxed = true),
            chapterNumberOverrides = NoChapterNumberOverrides,
            copyMarks = InMemoryChapterCopyMarks(),
            localVolumes = NoLocalVolumes,
            volumePicks = NoVolumePicks,
            readingLog = NoReadingLog,
            readingPace = NoReadingPace,
            localNovelLanguages = NoLocalNovelLanguages,
        ).restoreMerges(ProtoBuf.decodeFromByteArray(serializer, wire), prefEra = null)
        return grouped
    }

    private fun creator() = NovelBackupCreator(
        libraryHides = FakeLibraryHiddenRepository(),
        tombstones = NoChapterTombstoneRepository,
        manualOrder = FakeLibraryManualOrderRepository(),
        novelRepository = mockk {
            coEvery { getById(1L) } returns Novel.create().copy(id = 1L, url = "a", source = "s1")
            coEvery { getById(5L) } returns Novel.create().copy(id = 5L, url = "b", source = "s2")
        },
        novelChapterRepository = mockk(relaxed = true),
        categoryRepository = mockk(relaxed = true),
        novelTrackRepository = mockk(relaxed = true),
        mergeGroupRepository = memberships(),
        customNovelInfoRepository = mockk(relaxed = true),
        novelHistoryRepository = mockk(relaxed = true),
        novelSourceManager = mockk(),
        chapterNumberOverrides = NoChapterNumberOverrides,
        copyMarks = InMemoryChapterCopyMarks(),
        localVolumes = NoLocalVolumes,
        volumePicks = NoVolumePicks,
        readingLog = NoReadingLog,
        readingPace = NoReadingPace,
        localNovelLanguages = NoLocalNovelLanguages,
    )
}
