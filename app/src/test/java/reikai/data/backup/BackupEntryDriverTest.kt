package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import reikai.domain.db.Transactions

/**
 * The backup driver both content types run: which series are backed up, which optional parts each
 * carries, how merge groups become refs, and how a restore batch contains a bad entry.
 */
class BackupEntryDriverTest {

    enum class Part(val off: (BackupOptions) -> BackupOptions) {
        CHAPTERS({ it.copy(chapters = false) }),
        CATEGORIES({ it.copy(categories = false) }),
        TRACKING({ it.copy(tracking = false) }),
        HISTORY({ it.copy(history = false) }),
        CUSTOM_INFO({ it.copy(customInfo = false) }),
    }

    /**
     * Writes each part's name onto every entry's set, so a test reads back which parts ran, and records the
     * size of each chunk a part was handed.
     */
    private class FakeParts(
        private val favorites: List<String> = listOf("fav"),
        private val readOnly: List<String> = listOf("read"),
        private val groupMembers: List<String> = emptyList(),
    ) : BackupEntryParts<String, MutableSet<String>> {
        val chapterChunks = mutableListOf<Int>()

        override suspend fun favorites() = favorites
        override suspend fun readNotInLibrary() = readOnly
        override suspend fun groupMembersOutsideLibrary() = groupMembers
        override suspend fun base(entries: List<String>) = entries.map { mutableSetOf("base:$it") }
        override suspend fun chapters(batch: List<Pair<String, MutableSet<String>>>) {
            chapterChunks += batch.size
            mark(batch, Part.CHAPTERS)
        }
        override suspend fun categories(batch: List<Pair<String, MutableSet<String>>>) = mark(batch, Part.CATEGORIES)
        override suspend fun tracking(batch: List<Pair<String, MutableSet<String>>>) = mark(batch, Part.TRACKING)
        override suspend fun history(batch: List<Pair<String, MutableSet<String>>>) = mark(batch, Part.HISTORY)
        override suspend fun customInfo(batch: List<Pair<String, MutableSet<String>>>) = mark(batch, Part.CUSTOM_INFO)

        private fun mark(batch: List<Pair<String, MutableSet<String>>>, part: Part) {
            batch.forEach { (_, backup) -> backup += part.name }
        }
    }

    @ParameterizedTest
    @EnumSource(Part::class)
    fun `an option that is on writes its part`(part: Part) = runTest {
        (part.name in BackupOptions().backupChunk(listOf("fav"), FakeParts()).single()) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Part::class)
    fun `an option that is off leaves its part out`(part: Part) = runTest {
        (part.name in part.off(BackupOptions()).backupChunk(listOf("fav"), FakeParts()).single()) shouldBe false
    }

    @Test
    fun `every option off still writes the entry itself`() = runTest {
        val allOff = Part.entries.fold(BackupOptions()) { options, part -> part.off(options) }
        allOff.backupChunk(listOf("fav"), FakeParts()) shouldBe listOf(mutableSetOf("base:fav"))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, BACKUP_CHUNK, BACKUP_CHUNK + 1])
    fun `every series comes out once and in order across chunks`(count: Int) = runTest {
        val favorites = List(count) { "s$it" }
        BackupOptions(readEntries = false).backupEntries(FakeParts(favorites = favorites))
            .toList().map { it.first() } shouldBe favorites.map { "base:$it" }
    }

    @Test
    fun `a part reads a whole chunk at a time`() = runTest {
        val parts = FakeParts(favorites = List(BACKUP_CHUNK + 1) { "s$it" })
        BackupOptions(readEntries = false).backupEntries(parts).toList()
        parts.chapterChunks shouldBe listOf(BACKUP_CHUNK, 1)
    }

    @Test
    fun `all read entries adds series outside the library after the favorites`() = runTest {
        BackupOptions().backupEntries(FakeParts()).toList().map { it.first() } shouldBe listOf("base:fav", "base:read")
    }

    @Test
    fun `without all read entries only the favorites are backed up`() = runTest {
        BackupOptions(readEntries = false).backupEntries(FakeParts()).toList().map { it.first() } shouldBe
            listOf("base:fav")
    }

    @Test
    fun `a merge group member outside the library is backed up without all read entries`() = runTest {
        // Its group is written whatever that option says, and restore resolves a ref only against a row.
        BackupOptions(readEntries = false).backupEntries(FakeParts(groupMembers = listOf("member")))
            .toList().map { it.first() } shouldBe listOf("base:fav", "base:member")
    }

    @Test
    fun `a merge group member with read progress is backed up once`() = runTest {
        BackupOptions().backupEntries(FakeParts(groupMembers = listOf("read")))
            .toList().map { it.first() } shouldBe listOf("base:fav", "base:read")
    }

    @Test
    fun `a merge group keeps every member that resolves`() = runTest {
        mergeGroupRefs(mapOf(1L to 9L, 2L to 9L, 3L to 9L)) { id -> "m$id".takeIf { id != 3L } } shouldBe
            listOf(listOf("m1", "m2"))
    }

    @Test
    fun `a merge group left with one resolved member is dropped`() = runTest {
        mergeGroupRefs(mapOf(1L to 9L, 2L to 9L)) { id -> "m$id".takeIf { id == 1L } } shouldBe emptyList()
    }

    /** Commits a batch's writes only when the whole block finishes, the way a rolled-back transaction does. */
    private class RollbackTransactions : Transactions {
        val committed = mutableListOf<String>()
        val pending = mutableListOf<String>()

        override suspend fun <T> run(block: suspend () -> T): T {
            pending.clear()
            val result = block()
            committed += pending
            pending.clear()
            return result
        }
    }

    @Test
    fun `a batch with no failures restores every entry once`() = runTest {
        val tx = RollbackTransactions()
        restoreBatch(listOf("a", "b"), tx) { tx.pending += it }
        tx.committed shouldBe listOf("a", "b")
    }

    @Test
    fun `one bad entry does not lose the rest of its batch`() = runTest {
        val tx = RollbackTransactions()
        val restored = mutableListOf<String>()
        restoreBatch(listOf("a", "bad", "c"), tx) {
            if (it == "bad") error("broken")
            tx.pending += it
            restored += it
        }
        // The batch rolled back, then the retry restored the good entries one at a time.
        restored.takeLast(2) shouldBe listOf("a", "c")
    }

    @Test
    fun `only the bad entry is reported as failed`() = runTest {
        val failures = restoreBatch(listOf("a", "bad", "c"), RollbackTransactions()) {
            if (it == "bad") error("broken")
        }
        failures.map { (entry, e) -> entry to e.message } shouldBe listOf("bad" to "broken")
    }

    @Test
    fun `a cancelled restore is not retried entry by entry`() = runTest {
        val attempts = mutableListOf<String>()
        launch {
            restoreBatch(listOf("a", "b", "c"), RollbackTransactions()) {
                attempts += it
                if (it == "b") currentCoroutineContext().cancel()
            }
        }.join()
        attempts shouldBe listOf("a", "b")
    }
}
