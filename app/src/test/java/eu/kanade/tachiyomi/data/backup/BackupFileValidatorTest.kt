package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.create.BackupProtoWriter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelSourceManager
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** The warning before a restore names a missing novel source the way it names a manga one. */
class BackupFileValidatorTest {

    private fun validator(
        vararg fields: Pair<Int, ByteArray>,
        trackerManager: TrackerManager = mockk(),
    ): BackupFileValidator {
        val novelSources = mockk<NovelSourceManager>()
        coEvery { novelSources.ensureLoaded() } just runs
        coEvery { novelSources.get(any()) } returns null
        return validator(novelSources, fields.asList(), trackerManager)
    }

    // Strict mocks: touching a source or tracker manager that is not stubbed fails the test.
    private fun validator(
        novelSources: NovelSourceManager,
        fields: List<Pair<Int, ByteArray>>,
        trackerManager: TrackerManager = mockk(),
    ) = validatorOver(
        ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gzip ->
                fields.forEach { (n, data) -> BackupProtoWriter.writeField(gzip, n, data) }
            }
        }.toByteArray(),
        novelSources,
        trackerManager,
    )

    private fun validatorOver(
        file: ByteArray,
        novelSources: NovelSourceManager,
        trackerManager: TrackerManager = mockk(),
    ) = BackupFileValidator(
        context = mockk<Context> {
            every { contentResolver.openInputStream(any()) } returns file.inputStream()
        },
        sourceManager = mockk(),
        trackerManager = trackerManager,
        novelSourceManager = novelSources,
        parser = ProtoBuf,
    )

    @Test
    fun `the check after writing a backup reads it without resolving any source`() = runTest {
        // Resolving novel sources loads every installed plugin and can fetch over the network, on every
        // backup including the automatic ones, for a result the caller never reads.
        validator(mockk<NovelSourceManager>(), listOf(novel)).checkReadable(mockk<Uri>()) shouldBe Unit
    }

    @Test
    fun `the check after writing a backup rejects a malformed file`() = runTest {
        // A length-delimited field claiming more bytes than the file holds.
        val truncated = byteArrayOf(0x0A, 0x7F, 0x01)

        shouldThrow<IllegalStateException> {
            validatorOver(truncated, mockk()).checkReadable(mockk<Uri>())
        }
    }

    private val novel = 700 to ProtoBuf.encodeToByteArray(
        BackupNovel.serializer(),
        BackupNovel(source = "tachiyomi:123", url = "u"),
    )

    @Test
    fun `a missing novel source is named by the backup's source list`() = runTest {
        val name = 717 to ProtoBuf.encodeToByteArray(
            BackupNovelSource.serializer(),
            BackupNovelSource(name = "Foo", sourceId = "tachiyomi:123"),
        )

        validator(novel, name).validate(mockk<Uri>()).missingSources shouldBe listOf("Foo")
    }

    @Test
    fun `a backup made before novel source names falls back to the id`() = runTest {
        validator(novel).validate(mockk<Uri>()).missingSources shouldBe listOf("tachiyomi:123")
    }

    @Test
    fun `a signed-out tracker is named once, whichever content types track with it`() = runTest {
        val signedOut = { trackerName: String ->
            mockk<BaseTracker> {
                every { name } returns trackerName
                every { isLoggedIn } returns false
            }
        }
        val trackers = mockk<TrackerManager> {
            every { get(2L) } returns signedOut("AniList")
            every { get(5L) } returns signedOut("Kitsu")
            every { get(9L) } returns signedOut("NovelUpdates")
        }
        val manga = 1 to ProtoBuf.encodeToByteArray(
            BackupManga.serializer(),
            BackupManga(source = 1L, url = "m").apply {
                tracking = listOf(BackupTracking(syncId = 2, libraryId = 0), BackupTracking(syncId = 5, libraryId = 0))
            },
        )
        val trackedNovel = 700 to ProtoBuf.encodeToByteArray(
            BackupNovel.serializer(),
            BackupNovel(source = "tachiyomi:123", url = "u").apply {
                tracking = listOf(BackupNovelTracking(trackerId = 2L), BackupNovelTracking(trackerId = 9L))
            },
        )

        validator(manga, trackedNovel, trackerManager = trackers).validate(mockk<Uri>()).missingTrackers shouldBe
            listOf("AniList", "Kitsu", "NovelUpdates")
    }
}
