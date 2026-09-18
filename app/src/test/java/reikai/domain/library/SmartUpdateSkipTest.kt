package reikai.domain.library

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelStatusCode
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.manga.model.Manga

/** The Smart update rules, run once through each content type's library row. */
class SmartUpdateSkipTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `fetch-once with chapters is skipped whatever the restrictions`(entry: SmartUpdateEntry) {
        smartUpdateSkip(entry.facts(fetchesOnce = true, total = 3), emptySet(), WINDOW_END) shouldBe
            SmartUpdateSkip.NOT_ALWAYS_UPDATE
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `fetch-once without chapters is fetched`(entry: SmartUpdateEntry) {
        smartUpdateSkip(entry.facts(fetchesOnce = true), emptySet(), WINDOW_END) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `fetch-once is reported before a restriction that also matches`(entry: SmartUpdateEntry) {
        val facts = entry.facts(fetchesOnce = true, completed = true, total = 3)

        smartUpdateSkip(facts, setOf(MANGA_NON_COMPLETED), WINDOW_END) shouldBe SmartUpdateSkip.NOT_ALWAYS_UPDATE
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `a completed entry is skipped under the completed restriction`(entry: SmartUpdateEntry) {
        smartUpdateSkip(entry.facts(completed = true), setOf(MANGA_NON_COMPLETED), WINDOW_END) shouldBe
            SmartUpdateSkip.COMPLETED
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `an entry with unread chapters is skipped under the unread restriction`(entry: SmartUpdateEntry) {
        smartUpdateSkip(entry.facts(total = 3, read = 2), setOf(MANGA_HAS_UNREAD), WINDOW_END) shouldBe
            SmartUpdateSkip.NOT_CAUGHT_UP
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `an entry read to the end passes the unread restriction`(entry: SmartUpdateEntry) {
        smartUpdateSkip(entry.facts(total = 3, read = 3), setOf(MANGA_HAS_UNREAD), WINDOW_END) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `an unstarted entry with chapters is skipped under the unstarted restriction`(entry: SmartUpdateEntry) {
        smartUpdateSkip(entry.facts(total = 3), setOf(MANGA_NON_READ), WINDOW_END) shouldBe
            SmartUpdateSkip.NOT_STARTED
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `an entry with no chapters yet is not skipped as unstarted`(entry: SmartUpdateEntry) {
        smartUpdateSkip(entry.facts(), setOf(MANGA_NON_READ), WINDOW_END) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `an entry due after the window is skipped under the release period restriction`(entry: SmartUpdateEntry) {
        val facts = entry.facts(nextUpdate = WINDOW_END + 1)

        smartUpdateSkip(facts, setOf(MANGA_OUTSIDE_RELEASE_PERIOD), WINDOW_END) shouldBe
            SmartUpdateSkip.NOT_IN_RELEASE_PERIOD
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `an entry due inside the window is fetched`(entry: SmartUpdateEntry) {
        val facts = entry.facts(nextUpdate = WINDOW_END)

        smartUpdateSkip(facts, setOf(MANGA_OUTSIDE_RELEASE_PERIOD), WINDOW_END) shouldBe null
    }

    companion object {
        private const val WINDOW_END = 2_000L

        @JvmStatic
        fun entries() = listOf(MangaSmartUpdateEntry(), NovelSmartUpdateEntry())
    }
}

/** One content type's library row, built from the facts the rules read and read back through its adapter. */
interface SmartUpdateEntry {
    fun facts(
        fetchesOnce: Boolean = false,
        completed: Boolean = false,
        total: Long = 0,
        read: Long = 0,
        nextUpdate: Long = 0,
    ): SmartUpdateFacts
}

class MangaSmartUpdateEntry : SmartUpdateEntry {

    override fun toString() = "manga"

    override fun facts(fetchesOnce: Boolean, completed: Boolean, total: Long, read: Long, nextUpdate: Long) =
        LibraryManga(
            manga = Manga.create().copy(
                updateStrategy = if (fetchesOnce) UpdateStrategy.ONLY_FETCH_ONCE else UpdateStrategy.ALWAYS_UPDATE,
                status = (if (completed) SManga.COMPLETED else SManga.ONGOING).toLong(),
                nextUpdate = nextUpdate,
            ),
            categories = emptyList(),
            totalChapters = total,
            readCount = read,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        ).smartUpdateFacts()
}

class NovelSmartUpdateEntry : SmartUpdateEntry {

    override fun toString() = "novel"

    override fun facts(fetchesOnce: Boolean, completed: Boolean, total: Long, read: Long, nextUpdate: Long) =
        LibraryNovel(
            novel = Novel.create().copy(
                updateStrategy = if (fetchesOnce) UpdateStrategy.ONLY_FETCH_ONCE else UpdateStrategy.ALWAYS_UPDATE,
                status = (if (completed) NovelStatusCode.COMPLETED else NovelStatusCode.ONGOING).toLong(),
                nextUpdate = nextUpdate,
            ),
            categories = emptyList(),
            totalChapters = total,
            readCount = read,
            bookmarkCount = 0,
            downloadCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
        ).smartUpdateFacts()
}
