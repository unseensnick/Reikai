package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupChapterTombstone
import eu.kanade.tachiyomi.data.backup.models.BackupLocalVolume
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupManualPosition
import eu.kanade.tachiyomi.data.backup.models.BackupReadingSession
import eu.kanade.tachiyomi.data.backup.models.BackupVolumeOwner
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryContentType

/**
 * Reikai's own entry and category fields share no number with a field Komikku, TachiyomiSY, Yokai or Tsundoku
 * declare for another meaning. Each other app's message is mirrored below with only its own high-numbered
 * fields, copied from its refs/ clone; a backup either app writes then decodes in the other with each side's
 * fields at their defaults.
 */
class RefAppBackupFieldClashTest {

    @Serializable
    class ForeignManga(
        @ProtoNumber(1) var source: Long,
        @ProtoNumber(2) var url: String,
        // Komikku and TachiyomiSY: merged references, flat metadata, SY's merge settings.
        @ProtoNumber(600) var merged: List<String> = emptyList(),
        @ProtoNumber(601) var flatMetadata: String? = null,
        @ProtoNumber(604) var mergeSettings: String? = null,
        // Tsundoku's reserved block.
        @ProtoNumber(8000) var isNovel: Boolean = false,
        @ProtoNumber(8001) var alternativeTitles: List<String> = emptyList(),
    )

    @Serializable
    class ForeignChapter(
        @ProtoNumber(1) var url: String,
        @ProtoNumber(2) var name: String,
        // Komikku, TachiyomiSY and Tsundoku keep the sync fields Reikai retired; Yokai's pages left.
        @ProtoNumber(11) var lastModifiedAt: Long = 0,
        @ProtoNumber(12) var version: Long = 0,
        @ProtoNumber(800) var pagesLeft: Int = 0,
    )

    @Serializable
    class ForeignCategory(
        @ProtoNumber(1) var name: String,
        // TachiyomiSY's sync fields, Yokai's per-category sort.
        @ProtoNumber(601) var version: Long = 0,
        @ProtoNumber(602) var uid: Long = 0,
        @ProtoNumber(603) var lastModifiedAt: Long = 0,
        @ProtoNumber(800) var mangaSort: Int? = null,
    )

    @Test
    fun `a Reikai entry's own fields read as nothing in the other apps`() {
        val reikai = BackupManga(
            source = 1L,
            url = "u",
            readingSessions = listOf(BackupReadingSession("c", 1, 2)),
            manualOrder = listOf(BackupManualPosition(1, 2)),
            localVolumes = listOf(BackupLocalVolume("v", 1, "L")),
            chapterTombstones = listOf(BackupChapterTombstone(url = "c", read = true)),
            volumeOwners = listOf(BackupVolumeOwner("f", "v")),
        )

        val foreign = ProtoBuf.decodeFromByteArray(
            ForeignManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), reikai),
        )

        listOf(
            foreign.merged,
            foreign.flatMetadata,
            foreign.mergeSettings,
            foreign.isNovel,
            foreign.alternativeTitles,
        ) shouldBe
            listOf(emptyList<String>(), null, null, false, emptyList<String>())
    }

    @Test
    fun `another app's entry fields read as nothing in Reikai`() {
        val foreign = ForeignManga(
            source = 1L,
            url = "u",
            merged = listOf("m"),
            flatMetadata = "f",
            mergeSettings = "s",
            isNovel = true,
            alternativeTitles = listOf("a"),
        )

        val reikai = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(ForeignManga.serializer(), foreign),
        )

        listOf(
            reikai.searchMetadata,
            reikai.readingSessions.size,
            reikai.manualOrder.size,
            reikai.localVolumes.size,
            reikai.chapterTombstones.size,
            reikai.volumeOwners.size,
        ) shouldBe listOf(null, 0, 0, 0, 0, 0)
    }

    @Test
    fun `chapter fields do not cross either way`() {
        val reikai = ProtoBuf.decodeFromByteArray(
            BackupChapter.serializer(),
            ProtoBuf.encodeToByteArray(ForeignChapter.serializer(), ForeignChapter("c", "n", 5, 6, 7)),
        )
        val foreign = ProtoBuf.decodeFromByteArray(
            ForeignChapter.serializer(),
            ProtoBuf.encodeToByteArray(
                BackupChapter.serializer(),
                BackupChapter(url = "c", name = "n", pageCount = 9, sourceChapterNumber = 2.0, copyMark = 1),
            ),
        )

        listOf(
            reikai.pageCount,
            reikai.sourceChapterNumber,
            reikai.copyMark,
            foreign.pagesLeft,
            foreign.version,
        ) shouldBe
            listOf(0L, null, null, 0, 0L)
    }

    @Test
    fun `category fields do not cross either way`() {
        val reikai = ProtoBuf.decodeFromByteArray(
            BackupCategory.serializer(),
            ProtoBuf.encodeToByteArray(ForeignCategory.serializer(), ForeignCategory("c", 1, 2, 3, 65)),
        )
        val foreign = ProtoBuf.decodeFromByteArray(
            ForeignCategory.serializer(),
            ProtoBuf.encodeToByteArray(
                BackupCategory.serializer(),
                BackupCategory(name = "c", hidden = true, contentType = CategoryContentType.NOVEL, manualBase = 4),
            ),
        )

        listOf(reikai.hidden, reikai.contentType, reikai.manualBase, foreign.version, foreign.mangaSort) shouldBe
            listOf(false, CategoryContentType.MANGA, null, 0L, null)
    }
}
