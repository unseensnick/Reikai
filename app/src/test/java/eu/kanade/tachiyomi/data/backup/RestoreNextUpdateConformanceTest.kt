package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.readinglog.NoReadingLog
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A restore keeps the next update an entry came with while it is still ahead, the device's over the
 * backup's, and predicts one otherwise, so an older backup without the field restores as it always did.
 * Each type's backup goes through the wire and its real restore over real SQL.
 */
@OptIn(ExperimentalSerializationApi::class)
class RestoreNextUpdateConformanceTest {

    // Whole milliseconds, which is what a backup holds
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a next update still ahead in the backup is kept`(type: Type) = runTest {
        val ahead = now + 3.days

        type.restore(backup = ahead, device = null) shouldBe ahead
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a backup without a next update restores the prediction`(type: Type) = runTest {
        type.restore(backup = null, device = null)!! shouldBeGreaterThan now
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a next update the backup let fall behind gives way to the prediction`(type: Type) = runTest {
        type.restore(backup = now - 10.days, device = null)!! shouldBeGreaterThan now
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the device's own next update wins over the backup's`(type: Type) = runTest {
        val device = now + 2.days

        type.restore(backup = now + 5.days, device = device) shouldBe device
    }

    enum class Type {
        MANGA {
            override suspend fun restore(backup: Instant?, device: Instant?): Instant? =
                MangaRestoreHarness.create().use { harness ->
                    device?.let { harness.insert(Manga.create().copy(url = URL, source = 1L, nextUpdate = it)) }
                    val wire = BackupManga(source = 1L, url = URL, nextUpdate = backup.millis())
                    harness.restorer().restore(listOf(wire.overTheWire(BackupManga.serializer())), emptyList())
                    harness.mangas.getMangaByUrlAndSourceId(URL, 1L)!!.nextUpdate
                }
        },
        NOVEL {
            override suspend fun restore(backup: Instant?, device: Instant?): Instant? =
                MangaRestoreHarness.create().use { harness ->
                    val novels = NovelRepositoryImpl(harness.database)
                    device?.let {
                        val id = novels.insert(Novel.create().copy(url = URL, source = "s"))!!
                        novels.update(NovelUpdate(id) { nextUpdate = it })
                    }
                    val wire = BackupNovel(source = "s", url = URL, nextUpdate = backup.millis())
                    NovelRestorer(
                        novelRepository = novels,
                        novelChapterRepository = NovelChapterRepositoryImpl(harness.database),
                        categoryRepository = mockk(relaxed = true),
                        novelTrackRepository = mockk(relaxed = true),
                        restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
                        setCustomNovelInfo = mockk(relaxed = true),
                        novelHistoryRepository = NovelHistoryRepositoryImpl(harness.database),
                        chapterNumberOverrides = NoChapterNumberOverrides,
                        readingLog = NoReadingLog,
                    ).restore(wire.overTheWire(BackupNovel.serializer()), emptyList())
                    novels.getByUrlAndSource(URL, "s")!!.nextUpdate
                }
        },
        ;

        /** Restores an entry whose backup carries [backup] over a device copy carrying [device], if any. */
        abstract suspend fun restore(backup: Instant?, device: Instant?): Instant?
    }

    private companion object {
        const val URL = "/e"

        // An absent next update is written as 0, which the wire omits, as in a backup made before the field
        fun Instant?.millis() = this?.toEpochMilliseconds() ?: 0L

        fun <T> T.overTheWire(serializer: KSerializer<T>): T =
            ProtoBuf.decodeFromByteArray(serializer, ProtoBuf.encodeToByteArray(serializer, this))
    }
}
