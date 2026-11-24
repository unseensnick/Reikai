package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.customInfo
import eu.kanade.tachiyomi.data.backup.models.legacyEdits
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.NoVolumePicks
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace

/**
 * Reikai 0.1.x and 0.2.x kept a novel's edits in its own fields and marked them by bit. Restored as the
 * custom info later versions keep edits in, they survive the next refresh instead of being overwritten.
 */
class NovelLegacyEditsRestoreTest {

    @Test
    fun `an older backup's edited author comes back as an edit, the rest as the source's`() = runTest {
        val restored = mutableListOf<CustomNovelInfo>()
        val customInfo = mockk<SetCustomNovelInfo> {
            coEvery { restore(any()) } answers
                { restored += firstArg<CustomNovelInfo>() }
        }

        restorer(customInfo).restore(
            BackupNovel(source = "s", url = "u", author = "Mine", artist = "Theirs", legacyEditedFlags = 1),
            emptyList(),
        )

        restored.single().let { it.author to it.artist } shouldBe ("Mine" to null)
    }

    // An entry's own custom info is newer than its legacy bits, so it wins.
    @Test
    fun `an older backup's custom info wins over its legacy edits`() = runTest {
        val restored = mutableListOf<CustomNovelInfo>()
        val setCustomInfo = mockk<SetCustomNovelInfo> {
            coEvery { restore(any()) } answers { restored += firstArg<CustomNovelInfo>() }
        }

        restorer(setCustomInfo).restore(
            BackupNovel(
                source = "s",
                url = "u",
                author = "Mine",
                legacyEditedFlags = 1,
            ).apply { customInfo = BackupCustomInfo(author = "Custom") },
            emptyList(),
        )

        restored.single().author shouldBe "Custom"
    }

    @ParameterizedTest
    @EnumSource(EditedField::class)
    fun `each legacy edit bit marks its own field`(field: EditedField) {
        BackupNovel(
            source = "s",
            url = "u",
            author = "A",
            artist = "B",
            description = "D",
            genre = listOf("G"),
            status = 2,
            legacyEditedFlags = 1L shl field.ordinal,
        ).legacyEdits() shouldBe field.edit
    }

    // In bit order.
    enum class EditedField(val edit: BackupCustomInfo) {
        AUTHOR(BackupCustomInfo(author = "A")),
        ARTIST(BackupCustomInfo(artist = "B")),
        DESCRIPTION(BackupCustomInfo(description = "D")),
        GENRE(BackupCustomInfo(genre = listOf("G"))),
        STATUS(BackupCustomInfo(status = 2)),
    }

    private fun restorer(customInfo: SetCustomNovelInfo) = NovelRestorer(
        tombstones = NoChapterTombstoneRepository,
        manualOrder = FakeLibraryManualOrderRepository(),
        novelRepository = mockk(relaxed = true) {
            coEvery { getByUrlAndSource(any(), any()) } returns null
            coEvery { insert(any()) } returns 1L
            coEvery { getById(any()) } returns null
        },
        novelChapterRepository = mockk { coEvery { getByNovelId(any()) } returns emptyList() },
        categoryRepository = mockk(relaxed = true),
        novelTrackRepository = mockk(relaxed = true),
        restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
        setCustomNovelInfo = customInfo,
        novelHistoryRepository = mockk(relaxed = true),
        chapterNumberOverrides = NoChapterNumberOverrides,
        copyMarks = InMemoryChapterCopyMarks(),
        localVolumes = NoLocalVolumes,
        volumePicks = NoVolumePicks,
        readingLog = NoReadingLog,
        readingPace = NoReadingPace,
        localNovelLanguages = NoLocalNovelLanguages,
    )
}
