package eu.kanade.tachiyomi.data.backup

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** The reader fonts ride App settings on the way out, as NovelFontRestoreTest pins they do on the way in. */
class NovelFontBackupTest {

    private val out = ByteArrayOutputStream()

    @BeforeEach
    fun setUp() {
        mockkStatic(UniFile::class)
        val uri = mockk<Uri>()
        every { UniFile.fromUri(any(), any()) } returns mockk {
            every { isFile } returns true
            every { openOutputStream() } returns out
            every { this@mockk.uri } returns uri
        }
    }

    @AfterEach
    fun tearDown() = unmockkStatic(UniFile::class)

    @Test
    fun `a backup with App settings carries the reader fonts`() = runTest {
        backUp(appSettings = true)

        decoded().backupNovelFonts.map { it.fileName } shouldBe listOf("Literata.ttf")
    }

    @Test
    fun `a backup without App settings leaves the reader fonts out`() = runTest {
        backUp(appSettings = false)

        decoded().backupNovelFonts shouldBe emptyList()
    }

    private suspend fun backUp(appSettings: Boolean) {
        BackupCreator(
            isAutoBackup = false,
            context = mockk(relaxed = true),
            parser = ProtoBuf,
            backupPreferences = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            mergeGroupRepository = mockk { coEvery { getAllMemberships(any()) } returns emptyMap() },
            // A category, so the backup without fonts is not refused as empty.
            categoriesBackupCreator = mockk { coEvery { this@mockk() } returns listOf(BackupCategory(name = "C")) },
            mangaBackupCreator = mockk(relaxed = true),
            preferenceBackupCreator = mockk(relaxed = true),
            extensionStoresBackupCreator = mockk(relaxed = true),
            sourcesBackupCreator = mockk { coEvery { forSourceIds(any()) } returns emptyList() },
            backupFileValidator = mockk(relaxed = true),
            novelBackupCreator = mockk(relaxed = true),
            extensionBackupCreator = mockk(relaxed = true),
            feedBackupCreator = mockk(relaxed = true),
            novelPreferences = mockk(relaxed = true),
            novelFonts = mockk { every { backedUp() } returns flowOf("Literata.ttf" to byteArrayOf(0, 1, 0, 0)) },
        ).backup(mockk(), OPTIONS.copy(appSettings = appSettings))
    }

    private fun decoded(): Backup =
        ProtoBuf.decodeFromByteArray(Backup.serializer(), GZIPInputStream(out.toByteArray().inputStream()).readBytes())

    private companion object {
        val OPTIONS = BackupOptions(
            libraryEntries = false,
            categories = true,
            chapters = false,
            tracking = false,
            history = false,
            readEntries = false,
            extensionStores = false,
            sourceSettings = false,
            savedSearches = false,
        )
    }
}
