package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelFont
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import reikai.novel.font.NovelFontManager

/** The reader fonts a backup carries come back through the real restore, with the App settings that name them. */
class NovelFontRestoreTest {

    private val restored = mutableListOf<Pair<String, List<Byte>>>()
    private val fonts = mockk<NovelFontManager> {
        coEvery { restore(any(), any()) } coAnswers {
            order += "font"
            restored += firstArg<String>() to secondArg<ByteArray>().toList()
            true
        }
    }

    private val order = mutableListOf<String>()
    private val preferences = mockk<PreferenceRestorer> {
        // A real wait off the test's clock, since the fonts are read on the IO dispatcher too.
        coEvery { restoreApp(any(), any(), any()) } coAnswers {
            withContext(Dispatchers.IO) { delay(200) }
            order += "settings"
        }
    }

    private suspend fun restore(appSettings: Boolean) = restoreEncoded(
        Backup(emptyList(), backupNovelFonts = listOf(BackupNovelFont("Literata.ttf", byteArrayOf(0, 1, 0, 0)))),
        RestoreOptions(libraryEntries = false, categories = false, appSettings = appSettings, sourceSettings = false),
        novelFonts = fonts,
        preferenceRestorer = preferences,
    )

    /** A font restored under a cleaned name rewrites the reader setting, which a later settings restore would undo. */
    @Test
    fun `the reader fonts restore after the settings that name them`() = runTest {
        restore(appSettings = true)

        order shouldBe listOf("settings", "font")
    }

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
