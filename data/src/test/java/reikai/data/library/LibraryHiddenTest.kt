package reikai.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.library.StoredType
import reikai.domain.library.contentType
import reikai.domain.novel.model.NovelUpdate
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.MangaUpdate
import kotlin.time.Clock

/**
 * A hide follows its entry: out of the library and out of the database, and a group rejoined or joined
 * by a genuine merge is shown, while a swap, a reorder and an undone split keep it hidden. Both content
 * types, over a created schema with foreign keys on, as production runs it.
 */
class LibraryHiddenTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var hides: LibraryHiddenRepositoryImpl
    private lateinit var groups: MergeGroupRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            database = DatabaseBindings.providesDatabase(driver)
            hides = LibraryHiddenRepositoryImpl(database)
            groups = MergeGroupRepositoryImpl(database)
            listOf(ID, SIBLING, NEWCOMER, PARTNER).forEach { seed(it) }
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `leaving the library drops the hide`(type: StoredType) = runTest {
        hides.hide(type, listOf(ID))
        favourite(type, ID, on = false)

        hidden(type) shouldBe emptySet()
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `an update that leaves the favourite alone keeps the hide`(type: StoredType) = runTest {
        hides.hide(type, listOf(ID))
        notes(type, ID)

        hidden(type) shouldBe setOf(ID)
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `deleting the entry deletes its hide`(type: StoredType) = runTest {
        hides.hide(type, listOf(ID))
        deleteEntry(type, ID)

        hidden(type) shouldBe emptySet()
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `re-adding a removed member shows its whole group`(type: StoredType) = runTest {
        hiddenPair(type)
        favourite(type, SIBLING, on = false)
        favourite(type, SIBLING, on = true)

        hidden(type) shouldBe emptySet()
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `a new member merged into a hidden group shows the group`(type: StoredType) = runTest {
        hiddenPair(type)
        groups.merge(type.contentType, listOf(ID, NEWCOMER))

        hidden(type) shouldBe emptySet()
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `merging two hidden entries into a new group shows them`(type: StoredType) = runTest {
        hides.hide(type, listOf(ID, SIBLING))
        groups.createGroup(type.contentType, listOf(ID, SIBLING))

        hidden(type) shouldBe emptySet()
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `a migration target taking a member's place is a swap that keeps the hide`(type: StoredType) = runTest {
        hiddenPair(type)
        groups.replaceInGroup(type.contentType, SIBLING, NEWCOMER) {}

        hidden(type) shouldBe setOf(ID, SIBLING)
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `a migration target bringing its own group shows the group it joins`(type: StoredType) = runTest {
        hiddenPair(type)
        groups.createGroup(type.contentType, listOf(NEWCOMER, PARTNER))
        hides.hide(type, listOf(NEWCOMER, PARTNER))
        groups.replaceInGroup(type.contentType, SIBLING, NEWCOMER) {}

        // The replaced member left the group, so its own row is the one left; the favourite swap drops it.
        hidden(type) shouldBe setOf(SIBLING)
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `reordering a hidden group's sources keeps it hidden`(type: StoredType) = runTest {
        hiddenPair(type)
        groups.setSourceOrder(
            type.contentType,
            checkNotNull(groups.getGroupId(type.contentType, ID)),
            listOf(SIBLING, ID),
        )

        hidden(type) shouldBe setOf(ID, SIBLING)
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `undoing a split of a hidden group keeps it hidden`(type: StoredType) = runTest {
        hiddenPair(type)
        groups.removeFromGroup(type.contentType, listOf(SIBLING))
        groups.materializeGroup(type.contentType, listOf(ID, SIBLING), overrideSourceRanking = false)

        hidden(type) shouldBe setOf(ID, SIBLING)
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `the favourite trigger and the join's query clear the same rows`(type: StoredType) = runTest {
        val group = checkNotNull(groups.createGroup(type.contentType, listOf(ID, SIBLING)))
        groups.createGroup(type.contentType, listOf(NEWCOMER, PARTNER))
        val everyone = listOf(ID, SIBLING, NEWCOMER, PARTNER)

        hides.hide(type, everyone)
        favourite(type, SIBLING, on = false)
        hides.hide(type, listOf(SIBLING))
        favourite(type, SIBLING, on = true)
        val byTrigger = hidden(type)

        hides.hide(type, everyone)
        showGroup(type, group)
        val byQuery = hidden(type)

        (byTrigger to byQuery) shouldBe (setOf(NEWCOMER, PARTNER) to setOf(NEWCOMER, PARTNER))
    }

    @ParameterizedTest
    @EnumSource(value = StoredType::class, names = ["MANGA", "NOVELS"])
    fun `the hidden set re-emits when the trigger clears a group`(type: StoredType) = runTest {
        hiddenPair(type)
        favourite(type, SIBLING, on = false)
        val emissions = Channel<Set<Long>>(Channel.UNLIMITED)
        // The query runs in its collector's context, here the test's, so a missing emission times out virtually.
        val collector = launch { hides.subscribe(type).collect { emissions.send(it) } }
        val first = withTimeout(5_000) { emissions.receive() }
        favourite(type, SIBLING, on = true)
        val next = withTimeout(5_000) { emissions.receive() }
        collector.cancel()

        (first to next) shouldBe (setOf(ID) to emptySet())
    }

    /** [ID] and [SIBLING] in one group, both hidden. */
    private suspend fun hiddenPair(type: StoredType) {
        groups.createGroup(type.contentType, listOf(ID, SIBLING))
        hides.hide(type, listOf(ID, SIBLING))
    }

    private suspend fun hidden(type: StoredType): Set<Long> = hides.subscribe(type).first()

    private suspend fun exec(sql: String) = driver.execute(null, sql, 0).await()

    private suspend fun seed(id: Long) {
        exec(
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                "($id, 1, 'm$id', 'manga', 0, 0, 0, 0, 0, 1000, 0, 0, '', '{}')",
        )
        exec(
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                "VALUES ($id, 'src', 'n$id', 'novel', 0, 0, 0, 1000)",
        )
    }

    private suspend fun favourite(type: StoredType, id: Long, on: Boolean) {
        val at = if (on) Clock.System.now() else null
        when (type) {
            StoredType.NOVELS -> NovelRepositoryImpl(database).update(NovelUpdate(id) { favoriteAt = at })
            else -> MangaRepositoryImpl(database).update(MangaUpdate(id) { favoriteAt = at })
        }
    }

    private suspend fun notes(type: StoredType, id: Long) {
        when (type) {
            StoredType.NOVELS -> NovelRepositoryImpl(database).update(NovelUpdate(id) { notes = "note" })
            else -> MangaRepositoryImpl(database).update(MangaUpdate(id) { notes = "note" })
        }
    }

    private suspend fun showGroup(type: StoredType, groupId: Long) = when (type) {
        StoredType.NOVELS -> database.library_hiddenQueries.showNovelGroup(groupId)
        else -> database.library_hiddenQueries.showMangaGroup(groupId)
    }

    private suspend fun deleteEntry(type: StoredType, id: Long) = when (type) {
        StoredType.NOVELS -> exec("DELETE FROM novels WHERE _id = $id")
        else -> exec("DELETE FROM manga WHERE id = $id")
    }

    private companion object {
        const val ID = 7L
        const val SIBLING = 8L
        const val NEWCOMER = 9L
        const val PARTNER = 10L
    }
}
