package reikai.presentation.track

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import reikai.domain.entry.EntryId
import reikai.domain.track.EntryTrackPort
import reikai.domain.track.EntryTrackPorts
import reikai.domain.track.autobind.AutoBindEntry
import reikai.domain.track.autobind.AutoBindTracker
import reikai.domain.track.autobind.AutoBindTrackers
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

/** The track sheet's home page, one model for both content types. */
@RunWith(RobolectricTestRunner::class)
class EntryTrackInfoDialogHomeModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher + SupervisorJob())
    private val komga = mockk<Tracker>(relaxed = true) {
        every { id } returns 5L
        every { name } returns "Komga"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a tap-to-match bind whose match throws toasts the tracker error, not no match`() {
        val model = model(matchFails = IOException("server down"))

        model.registerAutoBind(TrackItem(null, komga))
        ShadowLooper.idleMainLooper()

        ShadowToast.getTextOfLatestToast() shouldBe "server down"
    }

    @Test
    fun `a tracker awaiting the replace confirmation is held by the model`() {
        val model = model(matchFails = null)

        model.askToReplace(TrackItem(null, komga))

        model.pendingReplace.value shouldBe komga.id
    }

    private fun model(matchFails: Exception?): EntryTrackInfoDialogHomeRoute.Model {
        val candidate = object : AutoBindTracker {
            override val tracker = komga
            override val offeredOnlyWhenAccepted = true
            override fun accepts(entry: AutoBindEntry) = true
            override suspend fun match(entry: AutoBindEntry): TrackSearch? = matchFails?.let { throw it }
        }
        val port = mockk<EntryTrackPort>(relaxed = true) {
            every { tracks() } returns flowOf(emptyList())
            coEvery { refresh() } returns emptyList()
            coEvery { autoBindEntry() } returns AutoBindEntry.Manga(Manga.create(), mockk<Source>(relaxed = true))
        }
        return EntryTrackInfoDialogHomeRoute.Model(
            entry = EntryId.Manga(1L),
            appScope = appScope,
            context = ApplicationProvider.getApplicationContext<Application>(),
            trackerManager = mockk { every { loggedInTrackers() } returns emptyList() },
            autoBindTrackers = mockk<AutoBindTrackers> { every { of(any()) } returns candidate },
            ports = mockk<EntryTrackPorts> { every { of(any()) } returns port },
        )
    }
}
