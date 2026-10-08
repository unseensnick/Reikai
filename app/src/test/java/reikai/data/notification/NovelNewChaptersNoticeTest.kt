package reikai.data.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat.NotificationWithIdAndTag
import eu.kanade.tachiyomi.util.system.notify
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class NovelNewChaptersNoticeTest {

    @Test
    fun `the updater posts a novel's notice under the key every dismiss reads`() = NovelUpdateNoticeHalf().use {
        runTest {
            val posted = slot<List<NotificationWithIdAndTag>>()
            every { any<Context>().notify(capture(posted)) } just runs

            it.showNewChapters()

            posted.captured.map { notice -> notice.key() } shouldBe
                listOf(NovelNewChaptersNotice.TAG to NovelNewChaptersNotice.id(it.novel.id))
        }
    }

    /** A notice an earlier build posted outlives the upgrade, and must still dismiss. */
    @Test
    fun `a novel's notice keeps the tag earlier builds posted under`() {
        NovelNewChaptersNotice.TAG shouldBe "novel_new_chapters"
    }

    @Test
    fun `a novel's notice keeps the id earlier builds posted under`() {
        NovelNewChaptersNotice.id(NOVEL_ID) shouldBe NOVEL_ID.hashCode()
    }

    // androidx exposes no getters for the key, only these package-private fields.
    private fun NotificationWithIdAndTag.key(): Pair<String?, Int> {
        fun field(name: String) = NotificationWithIdAndTag::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this)
        return field("mTag") as String? to field("mId") as Int
    }

    private companion object {
        // Past Int range, so the id is the Long's hash rather than a plain narrowing.
        const val NOVEL_ID = (7L shl 32) or 5L
    }
}
