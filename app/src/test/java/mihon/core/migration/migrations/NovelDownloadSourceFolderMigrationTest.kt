package mihon.core.migration.migrations

import android.text.TextUtils
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.novel.download.NovelDownloadProvider
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * Bare-id novel source folders on a real disk. Two app catalogues of one name and language (an extension's
 * version id bump) label to one folder, as manga's share one, so the second one's downloads have to reach it.
 */
class NovelDownloadSourceFolderMigrationTest {

    @TempDir
    lateinit var root: File

    // UniFile's file-backed implementation asks android.text.TextUtils, which the JVM test jar stubs out.
    @BeforeEach
    fun stubTextUtils() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
    }

    @AfterEach
    fun restoreTextUtils() {
        unmockkStatic(TextUtils::class)
    }

    private val libraryPreferences = LibraryPreferences(EmittingPreferenceStore())
    private val novelPreferences = NovelPreferences(EmittingPreferenceStore()).apply {
        seenNovelSources().set(
            mapOf(
                "tachiyomi:1" to LnSourceIdentity(name = "Foo", lang = "en"),
                "tachiyomi:2" to LnSourceIdentity(name = "Foo", lang = "en"),
            ),
        )
    }

    private val migration by lazy {
        val provider = NovelDownloadProvider(
            scope = testAppScope(),
            storageManager = mockk<StorageManager> {
                every { getNovelDownloadsDirectory() } returns UniFile.fromFile(root)
                every { changes } returns MutableSharedFlow()
            },
            downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
            libraryPreferences = libraryPreferences,
            novelPreferences = novelPreferences,
        )
        NovelDownloadSourceFolderMigration(novelPreferences, provider, cache = mockk(relaxed = true))
    }

    @Test
    fun `a second catalogue labelled onto a taken folder has its downloads moved into it`() = runTest {
        File(root, "tachiyomi_1/A").mkdirs()
        File(root, "tachiyomi_2/B").mkdirs()
        File(root, "tachiyomi_2/B/Ch 1.html").writeText("<p>text</p>")

        migration(MigrationContext(dryrun = false, previousVersion = 206))

        File(root, "Foo (EN)/B/Ch 1.html").readText() shouldBe "<p>text</p>"
    }

    @Test
    fun `a title both catalogues hold gets the chapters it lacks`() = runTest {
        File(root, "tachiyomi_1/A").mkdirs()
        File(root, "tachiyomi_1/A/Ch 1.html").writeText("<p>one</p>")
        File(root, "tachiyomi_2/A").mkdirs()
        File(root, "tachiyomi_2/A/Ch 2.html").writeText("<p>two</p>")

        migration(MigrationContext(dryrun = false, previousVersion = 206))

        File(root, "Foo (EN)/A/Ch 2.html").readText() shouldBe "<p>two</p>"
    }
}
