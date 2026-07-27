package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupProtoWriter
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomMangaInfo
import eu.kanade.tachiyomi.data.backup.models.BackupCustomNovelInfo
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryContentType
import java.io.ByteArrayOutputStream

/**
 * The streamed backup hand-writes each top-level protobuf field (BackupProtoWriter) instead of
 * encoding the whole Backup at once. A wrong field number or a varint bug would silently corrupt or
 * drop data on a user's backup, so this proves the streamed bytes decode back through the real
 * Backup serializer, across every Reikai (700+) field. If a field number drifts, this fails to
 * build, not on a restore.
 */
class BackupStreamFramingTest {

    private val proto = ProtoBuf

    private fun <T> ByteArrayOutputStream.write(fieldNumber: Int, serializer: SerializationStrategy<T>, item: T) {
        BackupProtoWriter.writeField(this, fieldNumber, proto.encodeToByteArray(serializer, item))
    }

    @Test
    fun `streamed fields decode back through the Backup serializer`() {
        val out = ByteArrayOutputStream()

        // Two manga to prove repeated framing, plus every content type and each RK field.
        out.write(1, BackupManga.serializer(), BackupManga(source = 1L, url = "m1", title = "Manga 1"))
        out.write(1, BackupManga.serializer(), BackupManga(source = 2L, url = "m2", title = "Manga 2"))
        out.write(700, BackupNovel.serializer(), BackupNovel(source = "ns", url = "n1", title = "Novel 1"))
        out.write(
            2,
            BackupCategory.serializer(),
            BackupCategory(name = "Cat", contentType = CategoryContentType.UNIVERSAL),
        )
        out.write(101, BackupSource.serializer(), BackupSource(name = "Src", sourceId = 1L))
        out.write(710, BackupExtension.serializer(), BackupExtension(pkgName = "pkg", name = "Ext"))
        out.write(
            711,
            BackupMangaMergeGroup.serializer(),
            BackupMangaMergeGroup(refs = listOf(BackupMangaSourceRef("a", 1L), BackupMangaSourceRef("b", 2L))),
        )
        out.write(
            713,
            BackupCustomMangaInfo.serializer(),
            BackupCustomMangaInfo(source = 1L, url = "m1", title = "Custom Manga"),
        )
        out.write(701, BackupNovelCategory.serializer(), BackupNovelCategory(name = "NCat"))
        out.write(
            702,
            BackupNovelMergeGroup.serializer(),
            BackupNovelMergeGroup(refs = listOf(BackupNovelSourceRef("na", "ns1"), BackupNovelSourceRef("nb", "ns2"))),
        )
        out.write(
            714,
            BackupCustomNovelInfo.serializer(),
            BackupCustomNovelInfo(source = "ns", url = "n1", title = "Custom Novel"),
        )

        val backup = proto.decodeFromByteArray(Backup.serializer(), out.toByteArray())

        backup.backupManga.map { it.url } shouldBe listOf("m1", "m2")
        backup.backupNovels shouldHaveSize 1
        backup.backupNovels.first().title shouldBe "Novel 1"
        backup.backupCategories.single().name shouldBe "Cat"
        backup.backupCategories.single().contentType shouldBe CategoryContentType.UNIVERSAL
        backup.backupSources.single().sourceId shouldBe 1L
        backup.backupExtensions.single().name shouldBe "Ext"
        backup.backupMangaMerges.single().refs.map { it.url } shouldBe listOf("a", "b")
        backup.backupCustomMangaInfo.single().title shouldBe "Custom Manga"
        backup.backupNovelCategories.single().name shouldBe "NCat"
        backup.backupNovelMerges.single().refs.map { it.source } shouldBe listOf("ns1", "ns2")
        backup.backupCustomNovelInfo.single().title shouldBe "Custom Novel"
    }

    @Test
    fun `a category from a backup written before the content type existed reads as manga`() {
        val bytes = proto.encodeToByteArray(
            PreContentTypeBackupCategory.serializer(),
            PreContentTypeBackupCategory(name = "Old", order = 3, flags = 4),
        )

        val category = proto.decodeFromByteArray(BackupCategory.serializer(), bytes)

        category.name shouldBe "Old"
        category.flags shouldBe 4
        category.contentType shouldBe CategoryContentType.MANGA
    }

    @Test
    fun `a build that does not know the content type still reads a category that carries one`() {
        val bytes = proto.encodeToByteArray(
            BackupCategory.serializer(),
            BackupCategory(name = "New", order = 3, flags = 4, contentType = CategoryContentType.NOVEL),
        )

        val category = proto.decodeFromByteArray(PreContentTypeBackupCategory.serializer(), bytes)

        category.name shouldBe "New"
        category.flags shouldBe 4
    }
}

/**
 * [BackupCategory] as it was before it carried a content type. Decoding each one as the other proves
 * both directions of wire compatibility: an older backup restores here, and a backup made here still
 * restores on a build that predates the field.
 */
@Serializable
private class PreContentTypeBackupCategory(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val order: Long = 0,
    @ProtoNumber(3) val id: Long = 0,
    @ProtoNumber(100) val flags: Long = 0,
)
