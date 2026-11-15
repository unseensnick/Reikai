package eu.kanade.tachiyomi.data.backup

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** A backup of the categories alone carries both libraries' categories, or a restore of it brings back half. */
class CategoriesOnlyBackupTest {

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
    fun `a backup with Categories on and Library entries off carries the novel categories`() = runTest {
        val novels = mockk<NovelBackupCreator>(relaxed = true) {
            coEvery { novelCategories(any()) } answers {
                if (firstArg<BackupOptions>().categories) listOf(BackupNovelCategory(name = "Novels")) else emptyList()
            }
        }
        BackupCreator(
            isAutoBackup = false,
            context = mockk(relaxed = true),
            parser = ProtoBuf,
            backupPreferences = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            mergeGroupRepository = mockk { coEvery { getAllMemberships(any()) } returns emptyMap() },
            categoriesBackupCreator = mockk(relaxed = true),
            mangaBackupCreator = mockk(relaxed = true),
            preferenceBackupCreator = mockk(relaxed = true),
            extensionStoresBackupCreator = mockk(relaxed = true),
            sourcesBackupCreator = mockk { coEvery { forSourceIds(any()) } returns emptyList() },
            backupFileValidator = mockk(relaxed = true),
            novelBackupCreator = novels,
            extensionBackupCreator = mockk(relaxed = true),
            feedBackupCreator = mockk(relaxed = true),
            novelPreferences = mockk(relaxed = true),
            novelFonts = mockk { every { backedUp() } returns emptyFlow() },
        ).backup(mockk(), OPTIONS)

        decoded().backupNovelCategories.map { it.name } shouldBe listOf("Novels")
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
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            savedSearches = false,
        )
    }
}
