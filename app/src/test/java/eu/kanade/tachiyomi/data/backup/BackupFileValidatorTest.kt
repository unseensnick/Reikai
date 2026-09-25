package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.create.BackupProtoWriter
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
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

    private fun validator(vararg fields: Pair<Int, ByteArray>): BackupFileValidator {
        val file = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gzip ->
                fields.forEach { (n, data) -> BackupProtoWriter.writeField(gzip, n, data) }
            }
        }
        val novelSources = mockk<NovelSourceManager>()
        coEvery { novelSources.ensureLoaded() } just runs
        coEvery { novelSources.get(any()) } returns null
        return BackupFileValidator(
            context = mockk<Context> {
                every { contentResolver.openInputStream(any()) } returns file.toByteArray().inputStream()
            },
            sourceManager = mockk(),
            trackerManager = mockk(),
            novelSourceManager = novelSources,
            parser = ProtoBuf,
        )
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
}
