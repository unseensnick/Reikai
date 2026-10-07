package reikai.presentation.details

import android.content.Context
import android.content.Intent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.Constants
import java.util.IdentityHashMap

/**
 * What a widget, a notification or the reader writes has to be what MainActivity reads back. The JVM
 * android.jar has no working Intent, so each constructed one is backed by a map standing in for its Bundle.
 */
class DetailsIntentsTest {

    private val context = mockk<Context>()
    private val bundles = IdentityHashMap<Intent, MutableMap<String, Any?>>()

    // Only "/n/1" on "src" is saved on this device.
    private val novels = mockk<NovelRepository> {
        coEvery { getByUrlAndSource(any(), any()) } returns null
        coEvery { getByUrlAndSource("/n/1", "src") } returns mockk()
    }

    @BeforeEach
    fun fakeIntentBundle() {
        mockkConstructor(Intent::class)
        fun Intent.bundle() = bundles.getOrPut(this) { mutableMapOf() }
        every { anyConstructed<Intent>().setAction(any()) } answers {
            (self as Intent).bundle()[ACTION] = firstArg<String>()
            self as Intent
        }
        every { anyConstructed<Intent>().action } answers { (self as Intent).bundle()[ACTION] as String? }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers {
            (self as Intent).bundle()[firstArg()] = secondArg<String>()
            self as Intent
        }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } answers {
            (self as Intent).bundle()[firstArg()] = secondArg<Long>()
            self as Intent
        }
        every { anyConstructed<Intent>().getStringExtra(any()) } answers {
            (self as Intent).bundle()[firstArg()] as String?
        }
        every { anyConstructed<Intent>().getLongExtra(any(), any()) } answers {
            (self as Intent).bundle()[firstArg()] as Long? ?: secondArg()
        }
    }

    @AfterEach
    fun restoreIntent() {
        unmockkConstructor(Intent::class)
    }

    @Test
    fun `a novel intent opens the saved novel it was built for`() = runTest {
        val screen = novelDetailsIntent(context, "src", "/n/1").novelDetailsScreen(novels)

        screen?.let { it.sourceId to it.novelUrl } shouldBe ("src" to "/n/1")
    }

    // MainActivity is exported, so any app can send one; opening it would fetch the url and save the novel.
    @Test
    fun `a novel intent for a novel this device never saved opens nothing`() = runTest {
        novelDetailsIntent(context, "src", "/n/2").novelDetailsScreen(novels).shouldBeNull()
    }

    @Test
    fun `a novel intent missing its url opens nothing`() = runTest {
        val intent = Intent().setAction(Constants.SHORTCUT_NOVEL).putExtra(Constants.NOVEL_SOURCE_EXTRA, "src")

        intent.novelDetailsScreen(novels).shouldBeNull()
    }

    @Test
    fun `a manga intent carries the manga action and id MainActivity reads`() {
        val intent = mangaDetailsIntent(context, 42L)

        (intent.action to intent.getLongExtra(Constants.MANGA_EXTRA, -1L)) shouldBe (Constants.SHORTCUT_MANGA to 42L)
    }

    private companion object {
        const val ACTION = "\u0000action"
    }
}
