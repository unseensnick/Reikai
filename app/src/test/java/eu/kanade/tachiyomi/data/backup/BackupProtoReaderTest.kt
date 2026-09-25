package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.create.BackupProtoWriter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 * A backup entry that does not decode reaches the Restore screen as the localized invalid-backup
 * message, as Mihon's whole-file decoder reported it, rather than the serializer's own text.
 */
class BackupProtoReaderTest {

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @Test
    fun `an entry that does not decode fails with the invalid backup message`() = runTest {
        // Field 1, the source id, is a number but arrives as a length-delimited string.
        val entry = byteArrayOf(0x0A, 0x01, 'a'.code.toByte())
        val file = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { BackupProtoWriter.writeField(it, 1, entry) }
        }
        val context = mockk<Context> {
            every { contentResolver.openInputStream(any()) } returns file.toByteArray().inputStream()
            every { stringResource(MR.strings.invalid_backup_file_unknown) } returns "Invalid backup"
        }

        val error = shouldThrow<IOException> {
            BackupProtoReader(context).read(mockk<Uri>()) { _, data ->
                ProtoBuf.decodeFromByteArray(BackupManga.serializer(), data)
            }
        }

        error.message shouldBe "Invalid backup"
    }
}
