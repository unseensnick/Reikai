package reikai.novel.local

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.library.ContentType
import reikai.domain.local.LocalSeriesLocks
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/** A local novel's series folder is read whole or not at all, since a book missing from it drops its chapters. */
@RunWith(RobolectricTestRunner::class)
class LocalNovelFolderTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val root = File(context.cacheDir, "local-novels").apply {
        deleteRecursively()
        mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a series folder on picked storage that cannot be listed whole fails the refresh`() = runTest {
        File(root, "Saga").mkdirs()
        File(root, "Saga/Saga 1.epub").writeBytes(byteArrayOf(1))
        // No provider answers this picked folder's count, so its listing cannot be told whole.
        val picked = spyk(UniFile.fromFile(File(root, "Saga"))!!) {
            every { uri } returns Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3AN/document/primary%3AN%2FSaga",
            )
        }
        val pickedRoot = spyk(UniFile.fromFile(root)!!) { every { findFile("Saga") } returns picked }
        val source = LocalNovelSource(
            context,
            mockk<StorageManager> { every { getLocalNovelsDirectory() } returns pickedRoot },
            Json,
            mockk(relaxed = true),
            mockk(relaxed = true),
            LocalSeriesLocks(NoLocalVolumes).apply { open() },
        )

        runCatching { source.parseNovel("Saga") }.exceptionOrNull()?.javaClass?.simpleName shouldBe
            "FolderUnreadableException"
    }

    /** The hold a refresh takes through the source is the series' own lock, so a move of the series waits on it. */
    @Test
    fun `the source's hold on a series keeps another holder of it waiting until the hold ends`() = runTest {
        val locks = LocalSeriesLocks(NoLocalVolumes).apply { open() }
        val source = LocalNovelSource(
            context,
            mockk<StorageManager> { every { getLocalNovelsDirectory() } returns UniFile.fromFile(root) },
            Json,
            mockk(relaxed = true),
            mockk(relaxed = true),
            locks,
        )
        val waitedInside = CompletableDeferred<Boolean>()

        source.locked("Saga") {
            val other = backgroundScope.async { locks.withSeries(ContentType.NOVELS, "saga") { "moved" } }
            repeat(3) { yield() }
            waitedInside.complete(!other.isCompleted)
        }

        waitedInside.await() shouldBe true
    }
}
