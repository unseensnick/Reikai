package reikai.presentation.browse

import android.content.Context
import eu.kanade.tachiyomi.util.system.isOnline
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.source.NoResultsException
import tachiyomi.i18n.MR
import java.net.ConnectException

/** How a source that failed to list or search is worded on the browse and migration surfaces. */
class SourceFailureMessageTest {

    @BeforeEach
    fun stubStatics() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        mockkStatic("eu.kanade.tachiyomi.util.system.NetworkExtensionsKt")
    }

    @AfterEach
    fun unstubStatics() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        unmockkStatic("eu.kanade.tachiyomi.util.system.NetworkExtensionsKt")
    }

    @Test
    fun `offline, a refused connection to a LAN host reads as offline`() {
        offline.read(ConnectException("Failed to connect to /10.10.1.13:25600")) shouldBe OFFLINE
    }

    @Test
    fun `offline, a source failing on the answer it never received reads as offline`() {
        offline.read(IndexOutOfBoundsException("Empty list doesn't contain element at index 0.")) shouldBe OFFLINE
    }

    @Test
    fun `offline, a source that answered nothing still says so`() {
        offline.read(NoResultsException()) shouldBe NO_RESULTS
    }

    @Test
    fun `online, a source failure keeps upstream's class name and message`() {
        online.read(IndexOutOfBoundsException("index 0")) shouldBe "IndexOutOfBoundsException: index 0"
    }

    private val offline by lazy { context(isOnline = false) }
    private val online by lazy { context(isOnline = true) }

    private fun context(isOnline: Boolean) = mockk<Context> {
        every { isOnline() } returns isOnline
        every { stringResource(MR.strings.exception_offline) } returns OFFLINE
        every { stringResource(MR.strings.no_results_found) } returns NO_RESULTS
    }

    private fun Context.read(error: Throwable): String = with(this) { error.sourceFailureMessage }

    private companion object {
        const val OFFLINE = "No Internet connection"
        const val NO_RESULTS = "No results found"
    }
}
