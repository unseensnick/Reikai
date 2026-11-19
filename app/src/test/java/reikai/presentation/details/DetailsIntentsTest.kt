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
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import tachiyomi.core.common.Constants
import java.util.IdentityHashMap

/**
 * What a widget, a notification or the reader writes has to be what MainActivity reads back. The JVM
 * android.jar has no working Intent, so each constructed one is backed by a map standing in for its Bundle.
 */
class DetailsIntentsTest {

    private val context = mockk<Context>()
    private val bundles = IdentityHashMap<Intent, MutableMap<String, Any?>>()

    // Only "/n/1" on "src" is saved on this device, as id 4. Strict, so anything past the lookup (an insert, a
    // fetch through a stored source) fails the test.
    private val novels = mockk<NovelRepository> {
        coEvery { getByUrlAndSource(any(), any()) } returns null
        coEvery { getByUrlAndSource("/n/1", "src") } returns Novel.create().copy(id = 4L, source = "src", url = "/n/1")
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
    fun `a novel intent opens the novel it was built for`() {
        novelDetailsIntent(context, 12L).novelToOpen() shouldBe EntryId.Novel(12L)
    }

    @Test
    fun `an older build's novel intent names no novel id`() {
        legacyNovelIntent("src", "/n/1").novelToOpen().shouldBeNull()
    }

    // A pinned widget or a posted notification an older build wrote keeps its source and url.
    @Test
    fun `an older build's novel intent opens the saved novel it names`() = runTest {
        legacyNovelIntent("src", "/n/1").legacyNovelToOpen(novels) shouldBe EntryId.Novel(4L)
    }

    // MainActivity is exported, so any app can send one; opening it would fetch the url and save the novel.
    @Test
    fun `an older build's novel intent for a novel this device never saved opens nothing`() = runTest {
        legacyNovelIntent("src", "/n/2").legacyNovelToOpen(novels).shouldBeNull()
    }

    @Test
    fun `a novel intent missing its url opens nothing`() = runTest {
        val intent = Intent().setAction(Constants.SHORTCUT_NOVEL).putExtra(Constants.NOVEL_SOURCE_EXTRA, "src")

        intent.legacyNovelToOpen(novels).shouldBeNull()
    }

    private fun legacyNovelIntent(source: String, url: String) = Intent()
        .setAction(Constants.SHORTCUT_NOVEL)
        .putExtra(Constants.NOVEL_SOURCE_EXTRA, source)
        .putExtra(Constants.NOVEL_URL_EXTRA, url)

    @Test
    fun `a manga intent carries the manga action and id MainActivity reads`() {
        val intent = mangaDetailsIntent(context, 42L)

        (intent.action to intent.getLongExtra(Constants.MANGA_EXTRA, -1L)) shouldBe (Constants.SHORTCUT_MANGA to 42L)
    }

    private companion object {
        const val ACTION = "\u0000action"
    }
}
