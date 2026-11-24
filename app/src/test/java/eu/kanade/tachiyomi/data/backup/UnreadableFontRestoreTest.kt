package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupProtoWriter
import eu.kanade.tachiyomi.data.backup.models.BackupFields
import eu.kanade.tachiyomi.data.backup.models.BackupNovelFont
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.novel.font.NovelFontManager
import java.io.ByteArrayOutputStream

/**
 * A font the backup cannot decode is skipped, as an unreadable entry is, so the fonts after it still come
 * back. Under Robolectric because writing the error log reaches Android's graphics classes.
 */
@RunWith(RobolectricTestRunner::class)
class UnreadableFontRestoreTest {

    @get:Rule
    val cache = TemporaryFolder()

    @Test
    fun `an unreadable font is skipped and the fonts after it still restore`() = runTest {
        val restored = mutableListOf<String>()
        val file = ByteArrayOutputStream().also { out ->
            // The file name, a string, written as a number.
            BackupProtoWriter.writeField(out, BackupFields.NOVEL_FONTS, byteArrayOf(0x08, 0x01))
            BackupProtoWriter.writeField(
                out,
                BackupFields.NOVEL_FONTS,
                ProtoBuf.encodeToByteArray(
                    BackupNovelFont.serializer(),
                    BackupNovelFont("Literata.ttf", byteArrayOf(1)),
                ),
            )
        }.toByteArray()

        restoreFile(
            file,
            RestoreOptions(libraryEntries = false, categories = false, appSettings = true, sourceSettings = false),
            novelFonts = mockk<NovelFontManager> {
                coEvery { restore(any(), any()) } answers {
                    restored += firstArg<String>()
                    true
                }
            },
            cacheDir = cache.root,
        )

        restored shouldBe listOf("Literata.ttf")
    }
}
