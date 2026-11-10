package tachiyomi.source.local

import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.chapter.NoLocalVolumes
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File

/** What a local manga's chapter list reads off its folder, since an empty list deletes every chapter row. */
@RunWith(RobolectricTestRunner::class)
class LocalSourceChapterListTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val root = File(context.cacheDir, "local-manga").apply {
        deleteRecursively()
        mkdirs()
    }
    private val fileSystem = LocalSourceFileSystem(
        mockk<StorageManager> { every { getLocalSourceDirectory() } returns UniFile.fromFile(root) },
    )
    private val source = LocalSource(context, fileSystem, mockk(relaxed = true), NoLocalVolumes)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private suspend fun chapterUrls(series: String) = source.getMangaUpdate(
        SManga.create().apply {
            url = series
            title = series
        },
        emptyList(),
        fetchDetails = false,
        fetchChapters = true,
    ).chapters.map { it.url }

    @Test
    fun `a series folder that is not there fails the refresh instead of listing nothing`() = runTest {
        runCatching { chapterUrls("Gone") }.isFailure shouldBe true
    }

    @Test
    fun `a folder the system left inside a chapter folder keeps it one chapter`() = runTest {
        File(root, "Saga/Ch 1/__MACOSX").mkdirs()
        File(root, "Saga/Ch 1/001.jpg").writeBytes(byteArrayOf(1))
        File(root, "Saga/Ch 1/__MACOSX/._001.jpg").writeBytes(byteArrayOf(1))

        chapterUrls("Saga") shouldBe listOf("Saga/Ch 1")
    }
}
