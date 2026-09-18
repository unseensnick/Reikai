package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupCustomMangaInfo
import eu.kanade.tachiyomi.data.backup.models.BackupCustomNovelInfo
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.LegacyCustomInfo
import eu.kanade.tachiyomi.data.backup.models.customInfo
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test

/**
 * Custom info crosses apps only if the field numbers match byte for byte, so these encode real
 * protobuf. The fork shapes below copy the fields of Komikku's and Yōkai's BackupManga that matter
 * here; decoding one side's bytes as the other's class is exactly what a cross-app restore does.
 */
class BackupCustomInfoWireTest {

    private val proto = ProtoBuf

    private val everyField = BackupCustomInfo(
        title = "My title",
        author = "My author",
        artist = "My artist",
        description = "My description",
        genre = listOf("Action", "Drama"),
        status = 2L,
        thumbnailUrl = "https://example.org/cover.jpg",
    )

    @Test
    fun `a Komikku series restores every custom field`() {
        val bytes = proto.encodeToByteArray(
            KomikkuManga.serializer(),
            KomikkuManga(
                source = 1L,
                url = "/m",
                customStatus = 2,
                customThumbnailUrl = "https://example.org/cover.jpg",
                customTitle = "My title",
                customArtist = "My artist",
                customAuthor = "My author",
                customDescription = "My description",
                customGenre = listOf("Action", "Drama"),
            ),
        )

        proto.decodeFromByteArray(BackupManga.serializer(), bytes).customInfo shouldBe everyField
    }

    @Test
    fun `a Yokai series restores every custom field it writes`() {
        val bytes = proto.encodeToByteArray(
            YokaiManga.serializer(),
            YokaiManga(
                source = 1L,
                url = "/m",
                customStatus = 2,
                customTitle = "My title",
                customGenre = listOf("Action"),
            ),
        )

        proto.decodeFromByteArray(BackupManga.serializer(), bytes).customInfo shouldBe
            BackupCustomInfo(title = "My title", genre = listOf("Action"), status = 2L)
    }

    @Test
    fun `a series written here reads in Komikku with its custom info`() {
        val manga = BackupManga(source = 1L, url = "/m").apply { customInfo = everyField }

        val komikku = proto.decodeFromByteArray(
            KomikkuManga.serializer(),
            proto.encodeToByteArray(BackupManga.serializer(), manga),
        )

        komikku shouldBe KomikkuManga(
            source = 1L,
            url = "/m",
            customStatus = 2,
            customThumbnailUrl = "https://example.org/cover.jpg",
            customTitle = "My title",
            customArtist = "My artist",
            customAuthor = "My author",
            customDescription = "My description",
            customGenre = listOf("Action", "Drama"),
        )
    }

    @Test
    fun `a series keeps every custom field through a round trip`() {
        val manga = BackupManga(source = 1L, url = "/m").apply { customInfo = everyField }

        proto.decodeFromByteArray(BackupManga.serializer(), proto.encodeToByteArray(BackupManga.serializer(), manga))
            .customInfo shouldBe everyField
    }

    @Test
    fun `a novel keeps every custom field through a round trip`() {
        val novel = BackupNovel(source = "src", url = "/n").apply { customInfo = everyField }

        proto.decodeFromByteArray(BackupNovel.serializer(), proto.encodeToByteArray(BackupNovel.serializer(), novel))
            .customInfo shouldBe everyField
    }

    @Test
    fun `an entry without custom info reads as none`() {
        BackupManga(source = 1L, url = "/m").customInfo shouldBe null
    }

    @Test
    fun `an old Reikai backup's manga custom info reaches its series`() {
        val backup = decodeOldBackup(
            Backup(
                backupManga = listOf(BackupManga(source = 1L, url = "/m")),
                backupCustomMangaInfo = listOf(
                    BackupCustomMangaInfo(
                        source = 1L,
                        url = "/m",
                        title = "My title",
                        author = "My author",
                        artist = "My artist",
                        description = "My description",
                        genre = listOf("Action", "Drama"),
                        status = 2L,
                        thumbnailUrl = "https://example.org/cover.jpg",
                    ),
                ),
            ),
        )

        legacyOf(backup).applyTo(backup.backupManga.single()).customInfo shouldBe everyField
    }

    @Test
    fun `an old Reikai backup's novel custom info reaches its novel`() {
        val backup = decodeOldBackup(
            Backup(
                backupManga = emptyList(),
                backupNovels = listOf(BackupNovel(source = "src", url = "/n")),
                backupCustomNovelInfo = listOf(
                    BackupCustomNovelInfo(
                        source = "src",
                        url = "/n",
                        title = "My title",
                        author = "My author",
                        artist = "My artist",
                        description = "My description",
                        genre = listOf("Action", "Drama"),
                        status = 2L,
                        thumbnailUrl = "https://example.org/cover.jpg",
                    ),
                ),
            ),
        )

        legacyOf(backup).applyTo(backup.backupNovels.single()).customInfo shouldBe everyField
    }

    @Test
    fun `an old entry names its series by source and address`() {
        val legacy =
            LegacyCustomInfo(listOf(BackupCustomMangaInfo(source = 2L, url = "/m", title = "Other")), emptyList())

        legacy.applyTo(BackupManga(source = 1L, url = "/m")).customInfo shouldBe null
    }

    @Test
    fun `custom info on the series wins over an old root entry for it`() {
        val legacy =
            LegacyCustomInfo(listOf(BackupCustomMangaInfo(source = 1L, url = "/m", title = "Old")), emptyList())
        val manga = BackupManga(source = 1L, url = "/m").apply { customInfo = BackupCustomInfo(author = "New") }

        legacy.applyTo(manga).customInfo shouldBe BackupCustomInfo(author = "New")
    }

    private fun decodeOldBackup(backup: Backup) =
        proto.decodeFromByteArray(Backup.serializer(), proto.encodeToByteArray(Backup.serializer(), backup))

    private fun legacyOf(backup: Backup) = LegacyCustomInfo(backup.backupCustomMangaInfo, backup.backupCustomNovelInfo)

    /** Komikku's BackupManga, cut to the identity and custom-info fields. */
    @Serializable
    data class KomikkuManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(602) val customStatus: Int = 0,
        @ProtoNumber(603) val customThumbnailUrl: String? = null,
        @ProtoNumber(800) val customTitle: String? = null,
        @ProtoNumber(801) val customArtist: String? = null,
        @ProtoNumber(802) val customAuthor: String? = null,
        @ProtoNumber(804) val customDescription: String? = null,
        @ProtoNumber(805) val customGenre: List<String>? = null,
    )

    /** Yōkai's BackupManga, cut the same way. It has no 603. */
    @Serializable
    data class YokaiManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(602) val customStatus: Int = 0,
        @ProtoNumber(800) val customTitle: String? = null,
        @ProtoNumber(801) val customArtist: String? = null,
        @ProtoNumber(802) val customAuthor: String? = null,
        @ProtoNumber(804) val customDescription: String? = null,
        @ProtoNumber(805) val customGenre: List<String>? = null,
    )
}
