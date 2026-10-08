package reikai.domain.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.ui.manga.seedManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.manga.MangaChapterSettings
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.SetNovelChapterFlags
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * A merged series shares one chapter setting, its lead's, whichever member it is opened through. One
 * suite over both content types, each through its own [GroupChapterSettings] and the real setter its
 * details screen calls, over a real database. [LEAD] is first in the group's order, [SIBLING] second.
 */
class GroupChapterSettingsConformanceTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = DatabaseBindings.providesDatabase(driver)

    @AfterEach
    fun tearDown() = driver.close()

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `opening through a sibling shows the lead's sort`(type: ContentType) = runTest {
        val side = side(type)

        side.shownDescending(SIBLING, GROUP) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a sort changed through a sibling is what opening through the lead shows`(type: ContentType) = runTest {
        val side = side(type)

        side.flipSortThrough(SIBLING, GROUP)

        side.shownDescending(LEAD, GROUP) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a sort changed through the lead is stored on every member`(type: ContentType) = runTest {
        val side = side(type)

        // Flipped back to descending, so the sibling's own stored ascending sort cannot pass for it.
        side.flipSortThrough(LEAD, GROUP)
        side.flipSortThrough(LEAD, GROUP)

        side.shownDescending(SIBLING, listOf(SIBLING)) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merge gives the members joining the lead's sort`(type: ContentType) = runTest {
        val side = side(type)

        side.settings.adoptLead(GROUP)

        side.shownDescending(SIBLING, listOf(SIBLING)) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an entry outside a group shows its own sort`(type: ContentType) = runTest {
        val side = side(type)

        side.shownDescending(SIBLING, listOf(SIBLING)) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `reordering the sources moves the lead`(type: ContentType) = runTest {
        val side = side(type)

        side.shownDescending(LEAD, listOf(SIBLING, LEAD)) shouldBe false
    }

    /** [LEAD] sorted descending and [SIBLING] ascending, each with its own setting. */
    private suspend fun side(type: ContentType): Side {
        Database.Schema.create(driver).await()
        return when (type) {
            ContentType.MANGA -> MangaSide().apply { seed(LEAD, descending = true) }.apply { seed(SIBLING, false) }
            else -> NovelSide().apply { seed(LEAD, descending = true) }.apply { seed(SIBLING, false) }
        }
    }

    private interface Side {
        val settings: GroupChapterSettings<*>
        suspend fun seed(id: Long, descending: Boolean)
        suspend fun shownDescending(openedId: Long, memberIds: List<Long>): Boolean

        /** Picks the sort already shown, which flips its direction, as the details sort page does. */
        suspend fun flipSortThrough(openedId: Long, memberIds: List<Long>)
    }

    private inner class MangaSide : Side {
        private val mangas = MangaRepositoryImpl(database)
        private val setFlags = SetMangaChapterFlags(mangas)
        override val settings = MangaChapterSettings(mangas)

        override suspend fun seed(id: Long, descending: Boolean) {
            driver.seedManga(id, chapterId = id * 10)
            val direction = if (descending) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC
            mangas.update(MangaUpdate(id) { chapterFlags = direction })
        }

        private suspend fun shown(openedId: Long, memberIds: List<Long>) =
            settings.shown(mangas.getMangaById(openedId), memberIds)

        override suspend fun shownDescending(openedId: Long, memberIds: List<Long>) =
            shown(openedId, memberIds).sortDescending()

        override suspend fun flipSortThrough(openedId: Long, memberIds: List<Long>) {
            val shown = shown(openedId, memberIds)
            settings.change(openedId, memberIds) { setFlags.awaitSetSortingModeOrFlipOrder(shown, shown.sorting) }
        }
    }

    private inner class NovelSide : Side {
        private val novels = NovelRepositoryImpl(database)
        private val preferences = NovelPreferences(InMemoryPreferenceStore())
        private val setFlags = SetNovelChapterFlags(novels, preferences)
        override val settings = NovelChapterSettings(novels)

        override suspend fun seed(id: Long, descending: Boolean) {
            val direction = if (descending) NovelChapterFlags.SORT_DESC else NovelChapterFlags.SORT_ASC
            val stored = novels.insert(
                Novel.create().copy(
                    source = "src$id",
                    url = "/novel/$id",
                    title = "Novel",
                    chapterFlags = direction or NovelChapterFlags.SORT_LOCAL,
                ),
            )
            stored shouldBe id
        }

        private suspend fun shown(openedId: Long, memberIds: List<Long>) =
            settings.shown(novels.getById(openedId)!!, memberIds)

        override suspend fun shownDescending(openedId: Long, memberIds: List<Long>) =
            shown(openedId, memberIds).effectiveSortDescending(preferences)

        override suspend fun flipSortThrough(openedId: Long, memberIds: List<Long>) {
            val shown = shown(openedId, memberIds)
            settings.change(openedId, memberIds) {
                setFlags.awaitSetSortingModeOrFlipOrder(shown, shown.effectiveSorting(preferences))
            }
        }
    }

    private companion object {
        const val LEAD = 1L
        const val SIBLING = 2L
        val GROUP = listOf(LEAD, SIBLING)
    }
}
