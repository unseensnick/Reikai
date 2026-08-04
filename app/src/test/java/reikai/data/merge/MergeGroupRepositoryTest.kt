package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import tachiyomi.data.Chapters
import tachiyomi.data.Custom_manga_info
import tachiyomi.data.Custom_novel_info
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Novels
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

/**
 * Proves the persisted merge-group storage round-trips and that the FK cascade removes membership when
 * a parent entry is deleted (the fix for the pref-era id-reuse capture). Establishes the reusable
 * pattern for testing this schema: a pure-JVM in-memory SQLite DB built from [Database.Schema] with
 * foreign keys enabled, so later schema work can reuse it.
 */
class MergeGroupRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: MergeGroupRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            // Cascade only fires with foreign keys on; the app enables the same pragma in AppModule.
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            database = Database(
                driver = driver,
                historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
                novelsAdapter = Novels.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
                custom_manga_infoAdapter = Custom_manga_info.Adapter(genreAdapter = StringListColumnAdapter),
                custom_novel_infoAdapter = Custom_novel_info.Adapter(genreAdapter = StringListColumnAdapter),
            )
            repository = MergeGroupRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `createGroup persists membership and reads it back in order`() = runTest {
        insertManga(1)
        insertManga(2)

        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(1L, 2L)
        repository.getGroupId(ContentType.MANGA, 1) shouldBe groupId
        repository.getGroupId(ContentType.MANGA, 2) shouldBe groupId
        val group = repository.getGroup(groupId)!!
        group.contentType shouldBe ContentType.MANGA
        group.overrideSourceRanking shouldBe false
    }

    @Test
    fun `createGroup returns null for fewer than two distinct ids`() = runTest {
        insertManga(1)

        repository.createGroup(ContentType.MANGA, listOf(1)) shouldBe null
        repository.createGroup(ContentType.MANGA, emptyList()) shouldBe null
        repository.createGroup(ContentType.MANGA, listOf(1, 1)) shouldBe null
    }

    @Test
    fun `deleting a manga cascades its membership away`() = runTest {
        insertManga(1)
        insertManga(2)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        driver.execute(null, "DELETE FROM mangas WHERE _id = 1", 0).await()

        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(2L)
    }

    @Test
    fun `a manga cannot belong to two groups`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        // Manga 1 is already a member; UNIQUE(manga_id) rejects re-grouping it into another group.
        shouldThrow<Exception> { repository.createGroup(ContentType.MANGA, listOf(1, 3)) }
    }

    @Test
    fun `dissolveGroup removes the group and all members`() = runTest {
        insertManga(1)
        insertManga(2)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        repository.dissolveGroup(groupId)

        repository.getGroup(groupId).shouldBeNull()
        repository.getMembers(ContentType.MANGA, groupId) shouldBe emptyList()
        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
    }

    @Test
    fun `novel groups persist and cascade like manga`() = runTest {
        insertNovel(1)
        insertNovel(2)

        val groupId = repository.createGroup(ContentType.NOVELS, listOf(1, 2))!!
        repository.getMembers(ContentType.NOVELS, groupId) shouldBe listOf(1L, 2L)
        repository.getGroup(groupId)!!.contentType shouldBe ContentType.NOVELS

        driver.execute(null, "DELETE FROM novels WHERE _id = 1", 0).await()

        repository.getGroupId(ContentType.NOVELS, 1).shouldBeNull()
        repository.getMembers(ContentType.NOVELS, groupId) shouldBe listOf(2L)
    }

    @Test
    fun `merge combines ungrouped entries into one group`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)

        val groupId = repository.merge(ContentType.MANGA, listOf(1, 2, 3))!!

        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `merge absorbs the existing groups its ids already belong to`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        insertManga(4)
        val g1 = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        val g2 = repository.createGroup(ContentType.MANGA, listOf(3, 4))!!

        // Merging one member of each group pulls in every hidden member.
        val merged = repository.merge(ContentType.MANGA, listOf(1, 3))!!

        repository.getMembers(ContentType.MANGA, merged) shouldBe listOf(1L, 2L, 3L, 4L)
        // The first id's group is the one that SURVIVES, keeping its row and everything on it; only
        // the absorbed one goes. Rebuilding into a fresh row is what used to drop the group's own
        // columns, so this pins the identity, not just the membership.
        merged shouldBe g1
        repository.getGroup(g2).shouldBeNull()
    }

    @Test
    fun `merge appends a newcomer to a hand-ordered group instead of re-trunking it`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        // The user dragged 2 to the trunk.
        repository.setSourceOrder(ContentType.MANGA, groupId, listOf(2, 1))

        // "Add to existing group" names the newcomer first, as every one of those call sites does.
        val merged = repository.merge(ContentType.MANGA, listOf(3, 1))!!

        // Argument order must NOT decide here: the newcomer goes last and the hand-set trunk stays.
        repository.getMembers(ContentType.MANGA, merged) shouldBe listOf(2L, 1L, 3L)
        repository.getGroup(merged)!!.overrideSourceRanking shouldBe true
    }

    @Test
    fun `merge does not turn an absorbed group's ranking into the merged group's`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        insertManga(4)
        val plain = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        val ranked = repository.createGroup(ContentType.MANGA, listOf(3, 4))!!
        repository.setSourceOrder(ContentType.MANGA, ranked, listOf(4, 3))

        // The unranked group is named first, so it survives and its (absent) ranking wins.
        val merged = repository.merge(ContentType.MANGA, listOf(1, 3))!!

        // Carrying the flag across would hand 2 and 4 a per-group ranking nobody ever set, with no
        // way back except discarding the real one too.
        repository.getGroup(merged)!!.overrideSourceRanking shouldBe false
        repository.getGroup(ranked).shouldBeNull()
    }

    @Test
    fun `merge renumbers priorities so a later arrival cannot land mid-order`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        insertManga(4)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2, 3))!!
        repository.setSourceOrder(ContentType.MANGA, groupId, listOf(1, 2, 3))
        // Leaves 1 at priority 0 and 3 at priority 2, with the 1 slot free.
        repository.removeFromGroup(ContentType.MANGA, listOf(2))

        repository.merge(ContentType.MANGA, listOf(1, 4))

        // Without the renumber the newcomer takes the default priority and sorts into the hole.
        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(1L, 3L, 4L)
    }

    @Test
    fun `merge returns null for fewer than two ids`() = runTest {
        insertManga(1)

        repository.merge(ContentType.MANGA, listOf(1)) shouldBe null
    }

    @Test
    fun `removeFromGroup keeps two or more survivors grouped`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2, 3))!!

        val survivors = repository.removeFromGroup(ContentType.MANGA, listOf(1))

        survivors shouldBe listOf(2L, 3L)
        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
        repository.getGroupId(ContentType.MANGA, 2) shouldBe groupId
    }

    @Test
    fun `removeFromGroup dissolves the group when one survivor remains`() = runTest {
        insertManga(1)
        insertManga(2)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        val survivors = repository.removeFromGroup(ContentType.MANGA, listOf(1))

        survivors shouldBe listOf(2L)
        repository.getGroup(groupId).shouldBeNull()
        repository.getGroupId(ContentType.MANGA, 2).shouldBeNull()
    }

    @Test
    fun `replaceInGroup puts the target in the source's place`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        repository.replaceInGroup(ContentType.MANGA, oldId = 1, newId = 3)

        // The arriving entry takes the outgoing one's slot, so migrating the trunk keeps the trunk.
        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(3L, 2L)
        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
    }

    @Test
    fun `replaceInGroup dissolves the group when the target was already its only sibling`() = runTest {
        insertManga(1)
        insertManga(2)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        // Migrating onto the entry it was merged with: the target would be left alone in the group.
        repository.replaceInGroup(ContentType.MANGA, oldId = 1, newId = 2)

        repository.getGroup(groupId).shouldBeNull()
        repository.getGroupId(ContentType.MANGA, 2).shouldBeNull()
    }

    @Test
    fun `replaceInGroup hands a dissolving group to the hook, with the group as it stood`() = runTest {
        insertManga(1)
        insertManga(2)
        val handedOut = mutableListOf<List<Long>>()
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        // Migrating onto a sibling really does break the group up, so each member has to be handed
        // its own copy of the shared tracker binding first. The hook sees the PRE-state, including
        // the departing member, since that is the group whose sharing is ending.
        repository.replaceInGroup(ContentType.MANGA, oldId = 1, newId = 2) { handedOut += it }

        handedOut shouldBe listOf(listOf(1L, 2L))
    }

    @Test
    fun `replaceInGroup does not call the hook when it only absorbs a group`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        insertManga(4)
        val handedOut = mutableListOf<List<Long>>()
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        repository.createGroup(ContentType.MANGA, listOf(3, 4))!!

        repository.replaceInGroup(ContentType.MANGA, oldId = 1, newId = 3) { handedOut += it }

        // The absorbed group's row goes, but its members stay merged, so nothing stopped sharing and
        // handing out copies here would be noise.
        handedOut.shouldBeEmpty()
    }

    @Test
    fun `replaceInGroup brings the target's own group along`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        insertManga(4)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        val targetGroup = repository.createGroup(ContentType.MANGA, listOf(3, 4))!!

        repository.replaceInGroup(ContentType.MANGA, oldId = 1, newId = 3)

        // The whole arriving group lands in the outgoing member's slot, keeping its own order.
        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(3L, 4L, 2L)
        repository.getGroup(targetGroup).shouldBeNull()
    }

    @Test
    fun `replaceInGroup is a no-op when the source is ungrouped`() = runTest {
        insertManga(1)
        insertManga(2)

        repository.replaceInGroup(ContentType.MANGA, oldId = 1, newId = 2)

        repository.getGroupId(ContentType.MANGA, 2).shouldBeNull()
    }

    @Test
    fun `dissolve ungroups every member`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        repository.createGroup(ContentType.MANGA, listOf(1, 2, 3))!!

        repository.dissolve(ContentType.MANGA, 2)

        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
        repository.getGroupId(ContentType.MANGA, 3).shouldBeNull()
    }

    @Test
    fun `clearAll dissolves every group of that type only`() = runTest {
        insertManga(1)
        insertManga(2)
        insertNovel(1)
        insertNovel(2)
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        val novelGroup = repository.createGroup(ContentType.NOVELS, listOf(1, 2))!!

        repository.clearAll(ContentType.MANGA)

        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
        repository.getGroupId(ContentType.NOVELS, 1) shouldBe novelGroup
    }

    @Test
    fun `getAllMemberships maps each member to its group`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        repository.getAllMemberships(ContentType.MANGA) shouldBe mapOf(1L to groupId, 2L to groupId)
    }

    @Test
    fun `setSourceOrder turns the override on and reorders the members`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2, 3))!!
        repository.getGroup(groupId)!!.overrideSourceRanking shouldBe false

        repository.setSourceOrder(ContentType.MANGA, groupId, listOf(3, 1, 2))

        repository.getGroup(groupId)!!.overrideSourceRanking shouldBe true
        // getMembers reads ORDER BY source_priority, so it now reflects the persisted order.
        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(3L, 1L, 2L)
    }

    @Test
    fun `clearSourceOrder turns the override off and restores insertion order`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2, 3))!!
        repository.setSourceOrder(ContentType.MANGA, groupId, listOf(3, 2, 1))

        repository.clearSourceOrder(ContentType.MANGA, groupId)

        repository.getGroup(groupId)!!.overrideSourceRanking shouldBe false
        // All priorities reset to 0, so the tie breaks on _id (insertion order) again.
        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `setSourceOrder is scoped to its own group`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        insertManga(4)
        val target = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        val other = repository.createGroup(ContentType.MANGA, listOf(3, 4))!!

        repository.setSourceOrder(ContentType.MANGA, target, listOf(2, 1))

        repository.getGroup(other)!!.overrideSourceRanking shouldBe false
        repository.getMembers(ContentType.MANGA, other) shouldBe listOf(3L, 4L)
    }

    @Test
    fun `merge keeps a hand-set source order and its override`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        repository.setSourceOrder(ContentType.MANGA, groupId, listOf(2, 1))

        val merged = repository.merge(ContentType.MANGA, listOf(2, 3))!!

        // Absorbing the group keeps its ranking, and the override survives instead of the merged
        // group silently falling back to the global preferred-source list.
        repository.getMembers(ContentType.MANGA, merged) shouldBe listOf(2L, 1L, 3L)
        repository.getGroup(merged)!!.overrideSourceRanking shouldBe true
    }

    @Test
    fun `merge without an absorbed override leaves the override off`() = runTest {
        insertManga(1)
        insertManga(2)

        val merged = repository.merge(ContentType.MANGA, listOf(1, 2))!!

        repository.getGroup(merged)!!.overrideSourceRanking shouldBe false
    }

    @Test
    fun `merge follows the order the ids are passed in`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)

        val merged = repository.merge(ContentType.MANGA, listOf(3, 1, 2))!!

        repository.getMembers(ContentType.MANGA, merged) shouldBe listOf(3L, 1L, 2L)
    }

    @Test
    fun `undoing a split restores the order and the override`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2, 3))!!
        repository.setSourceOrder(ContentType.MANGA, groupId, listOf(3, 1, 2))
        val before = repository.getMembers(ContentType.MANGA, groupId)

        // What the details screen does: capture the group, split the middle source out, then Undo
        // materialises the captured group. Not a merge: a merge appends, so it would return the
        // split member to the end instead of the middle it came from.
        repository.removeFromGroup(ContentType.MANGA, listOf(1))
        val restored = repository.materializeGroup(ContentType.MANGA, before, overrideSourceRanking = true)!!

        repository.getMembers(ContentType.MANGA, restored) shouldBe before
        repository.getGroup(restored)!!.overrideSourceRanking shouldBe true
    }

    @Test
    fun `undoing a split of a PAIR restores the override the dissolve destroyed`() = runTest {
        insertManga(1)
        insertManga(2)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        repository.setSourceOrder(ContentType.MANGA, groupId, listOf(2, 1))
        val before = repository.getMembers(ContentType.MANGA, groupId)

        // The discriminating shape: a pair shrinks below two members, so the group ROW is deleted and
        // the flag goes with it. Anything that reads the flag after the split reads nothing, which is
        // why the caller has to have captured it first.
        repository.removeFromGroup(ContentType.MANGA, listOf(1))
        repository.getGroup(groupId).shouldBeNull()
        val restored = repository.materializeGroup(ContentType.MANGA, before, overrideSourceRanking = true)!!

        repository.getMembers(ContentType.MANGA, restored) shouldBe before
        repository.getGroup(restored)!!.overrideSourceRanking shouldBe true
    }

    @Test
    fun `materializeGroup replaces a prior grouping rather than folding into it`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        // Stating the whole group is the point: a merge would have absorbed 2 as a hidden member.
        val materialized = repository.materializeGroup(ContentType.MANGA, listOf(3, 1), false)!!

        repository.getMembers(ContentType.MANGA, materialized) shouldBe listOf(3L, 1L)
        repository.getGroupId(ContentType.MANGA, 2).shouldBeNull()
    }

    @Test
    fun `merge keeps an absorbed order for novels too`() = runTest {
        insertNovel(1)
        insertNovel(2)
        insertNovel(3)
        val groupId = repository.createGroup(ContentType.NOVELS, listOf(1, 2))!!
        repository.setSourceOrder(ContentType.NOVELS, groupId, listOf(2, 1))

        val merged = repository.merge(ContentType.NOVELS, listOf(3, 2))!!

        // Same rule as manga: the group that survives keeps its trunk, the newcomer appends.
        repository.getMembers(ContentType.NOVELS, merged) shouldBe listOf(2L, 1L, 3L)
        repository.getGroup(merged)!!.overrideSourceRanking shouldBe true
    }

    @Test
    fun `replaceInGroup keeps the rest of a hand-set order`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        insertManga(4)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2, 3))!!
        repository.setSourceOrder(ContentType.MANGA, groupId, listOf(2, 3, 1))

        // Replacing the LAST member is the discriminating case: an unseated fresh row carries the
        // default priority, which would sort it to the front of the group instead of into the slot.
        repository.replaceInGroup(ContentType.MANGA, oldId = 1, newId = 4)

        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(2L, 3L, 4L)
        repository.getGroup(groupId)!!.overrideSourceRanking shouldBe true
    }

    @Test
    fun `addMembers appends to an ordered group`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        repository.setSourceOrder(ContentType.MANGA, groupId, listOf(2, 1))

        repository.addMembers(ContentType.MANGA, groupId, listOf(3))

        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(2L, 1L, 3L)
    }

    @Test
    fun `setSourceOrder works for novels`() = runTest {
        insertNovel(1)
        insertNovel(2)
        val groupId = repository.createGroup(ContentType.NOVELS, listOf(1, 2))!!

        repository.setSourceOrder(ContentType.NOVELS, groupId, listOf(2, 1))

        repository.getGroup(groupId)!!.overrideSourceRanking shouldBe true
        repository.getMembers(ContentType.NOVELS, groupId) shouldBe listOf(2L, 1L)
    }

    // Minimal valid parent rows: only the NOT NULL columns without a default need values.
    private suspend fun insertManga(id: Long) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) " +
                "VALUES ($id, 1, 'm-url-$id', 'title', 0, 0, 0, 0, 0, 0, 0)",
            0,
        ).await()
    }

    private suspend fun insertNovel(id: Long) {
        driver.execute(
            null,
            "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags) " +
                "VALUES ($id, 'src', 'n-url-$id', 'title', 0, 0, 0, 0)",
            0,
        ).await()
    }
}
