package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelFont
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.font.NovelFontManager

/** The reader fonts a backup carries come back through the real restore, with the App settings that name them. */
class NovelFontRestoreTest {

    private val restored = mutableListOf<Pair<String, List<Byte>>>()
    private val fonts = mockk<NovelFontManager> {
        coEvery { restore(any(), any()) } coAnswers {
            restored += firstArg<String>() to secondArg<ByteArray>().toList()
            true
        }
    }

    private suspend fun restore(appSettings: Boolean) = restoreEncoded(
        Backup(emptyList(), backupNovelFonts = listOf(BackupNovelFont("Literata.ttf", byteArrayOf(0, 1, 0, 0)))),
        RestoreOptions(libraryEntries = false, categories = false, appSettings = appSettings, sourceSettings = false),
        novelFonts = fonts,
    )

    @Test
    fun `a backup's reader fonts come back with App settings`() = runTest {
        restore(appSettings = true)

        restored shouldBe listOf("Literata.ttf" to listOf<Byte>(0, 1, 0, 0))
    }

    @Test
    fun `a restore without App settings leaves the reader fonts out`() = runTest {
        restore(appSettings = false)

        restored shouldBe emptyList()
    }
}
