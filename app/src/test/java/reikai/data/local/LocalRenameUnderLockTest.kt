package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.library.ContentType
import reikai.domain.local.LocalSeriesLocks
import reikai.storage.FakeSafProvider
import reikai.storage.rename
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File

/**
 * Rename files and a refresh of the same picked series share one lock, so the refresh cannot list the renamed
 * files against chapter rows still at the old addresses, which the sync would read as those chapters deleted.
 */
@RunWith(RobolectricTestRunner::class)
class LocalRenameUnderLockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val locks = LocalSeriesLocks().apply { open() }
    private lateinit var local: File

    @Before
    fun setUp() {
        FakeSafProvider.install(context, tmp.root)
        local = tmp.newFolder("Reikai", "local")
        // A chapter of pages, since reading an archive's metadata needs the device's native library.
        File(local, "Series/Vol 1/Ch 1").mkdirs()
        File(local, "Series/Vol 1/Ch 1/001.jpg").writeBytes(byteArrayOf(1))
    }

    private fun picked(folder: File): UniFile = UniFile.fromUri(context, FakeSafProvider.treeUri(folder))!!

    private val source by lazy {
        val fileSystem = LocalSourceFileSystem(
            mockk<StorageManager> { every { getLocalSourceDirectory() } answers { picked(local) } },
        )
        LocalSource(context, fileSystem, mockk(relaxed = true), Json, XML.v1 {}, NoLocalVolumes, locks)
    }

    private suspend fun listed(series: String) = source.getMangaUpdate(
        SManga.create().apply {
            url = series
            title = series
            // With a cover already, the listing opens no page to copy one, which on Windows would pin the folder.
            thumbnail_url = "cover.jpg"
        },
        emptyList(),
        fetchDetails = false,
        fetchChapters = true,
    ).chapters.map { it.url }

    @Test
    fun `a refresh started between a rename's disk step and its row step waits and lists the new addresses`() =
        runTest {
            val rows = mutableListOf("Series/Vol 1/Ch 1")
            listed("Series") // warm, so the listing below is a few milliseconds of real time
            val diskDone = CompletableDeferred<Unit>()
            val rowsGo = CompletableDeferred<Unit>()
            val rename = launch(Dispatchers.IO) {
                locks.withSeries(ContentType.MANGA, "Series") {
                    check(picked(File(local, "Series")).findFile("Vol 1")!!.rename("Vol 2"))
                    diskDone.complete(Unit)
                    rowsGo.await()
                    rows[0] = "Series/Vol 2/Ch 1"
                }
            }
            diskDone.await()

            // What the refresh listed, beside the rows as they stood the moment it had listed them.
            val refresh = async(Dispatchers.IO) { listed("Series").let { it to rows.toList() } }
            // Real time, off the test scheduler, so a listing nothing holds back has long finished before the rows move.
            withContext(Dispatchers.Default) { withTimeoutOrNull(1500) { refresh.await() } }
            rowsGo.complete(Unit)
            rename.join()

            refresh.await() shouldBe (listOf("Series/Vol 2/Ch 1") to listOf("Series/Vol 2/Ch 1"))
        }
}
