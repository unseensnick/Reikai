package reikai.data.novel.update

import android.app.Application
import androidx.work.WorkerParameters
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.novel.update.LnPluginUpdate
import reikai.novel.update.LnPluginUpdateChecker
import reikai.novel.update.LnPluginUpdateNotifier

class LnPluginUpdateWorkerTest {

    private val notifier = mockk<LnPluginUpdateNotifier>(relaxed = true)

    private fun worker(shown: ContentType): LnPluginUpdateWorker {
        val update = mockk<LnPluginUpdate> { every { entry.name } returns "Plugin" }
        val checker = mockk<LnPluginUpdateChecker> { coEvery { checkAndRecord() } returns listOf(update) }
        val graph = mockk<AppGraph> {
            every { lnPluginUpdateChecker } returns checker
            every { lnPluginUpdateNotifier } returns notifier
            every { reikaiLibraryPreferences.shownContent.get() } returns shown
        }
        val app = mockk<Application>(relaxed = true, moreInterfaces = arrayOf(GraphProvider::class))
        every { app.applicationContext } returns app
        @Suppress("UNCHECKED_CAST")
        every { (app as GraphProvider<AppGraph>).graph } returns graph
        return LnPluginUpdateWorker(app, mockk<WorkerParameters>(relaxed = true))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["ALL", "NOVELS"])
    fun `a shown type's plugin update is announced`(shown: ContentType) = runTest {
        worker(shown).doWork()

        coVerify { notifier.promptUpdates(listOf("Plugin")) }
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA"])
    fun `no plugin update is announced while novels are hidden`(shown: ContentType) = runTest {
        worker(shown).doWork()

        coVerify(exactly = 0) { notifier.promptUpdates(any()) }
    }
}
