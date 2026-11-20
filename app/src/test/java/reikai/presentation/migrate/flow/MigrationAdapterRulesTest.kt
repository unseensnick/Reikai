package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.Novel
import reikai.presentation.migrate.PickMember
import tachiyomi.domain.manga.model.Manga

/** The rules both migration adapters call rather than restate. */
class MigrationAdapterRulesTest {

    private fun entry(payload: MigrationPayload) = MigrationEntry(
        id = EntryId.Manga(1L),
        title = "Title",
        sourceKey = "src",
        sourceName = "Source",
        chapterCount = 1,
        cover = null,
        payload = payload,
    )

    private val manga = entry(MigrationPayload.OfManga(Manga.create().copy(url = "/a")))

    @Test
    fun `a manga entry's listing on its own source is its own`() {
        manga.isOwnListing("src", "/a") shouldBe true
    }

    @Test
    fun `an entry's own listing is only its own on its own source`() {
        manga.isOwnListing("other", "/a") shouldBe false
    }

    @Test
    fun `another listing on the entry's source is not its own`() {
        manga.isOwnListing("src", "/b") shouldBe false
    }

    @Test
    fun `a novel entry's path on its own source is its own listing`() {
        entry(MigrationPayload.OfNovel(Novel.create().copy(url = "/a"))).isOwnListing("src", "/a") shouldBe true
    }

    private val candidate = MigrationCandidate(
        sourceKey = "src",
        title = "Title",
        chapterCount = 7,
        latestChapter = 7.0,
        key = "src:/a",
        handle = MangaCandidateHandle(Manga.create()),
    )

    @Test
    fun `a candidate counted from no chapters has an unknown count, not zero`() {
        candidate.withChapterCounts(emptyList<Double>()) { it }.chapterCount shouldBe null
    }

    @Test
    fun `a candidate counted from chapters carries their count and highest number`() {
        candidate.withChapterCounts(listOf(1.0, 3.0, 2.0)) { it }.let { it.chapterCount to it.latestChapter } shouldBe
            (3 to 3.0)
    }

    @Test
    fun `merge members are listed once each, in the order the groups were met`() = runTest {
        val groups = mapOf(1L to listOf(2L, 1L), 3L to listOf(1L, 3L))

        members(ids = listOf(1L, 3L), groups = groups)?.map { it.id } shouldBe listOf(2L, 1L, 3L)
    }

    @Test
    fun `a merge member whose row is gone is skipped`() = runTest {
        members(ids = listOf(1L), groups = mapOf(1L to listOf(1L, 9L)), stored = setOf(1L))
            ?.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `a selection its groups add nothing to loads no row`() = runTest {
        val loaded = mutableListOf<Long>()

        mergeGroupPickMembers(
            ids = listOf(1L, 2L),
            relatedIds = mapOf(1L to listOf(1L, 2L), 2L to listOf(1L, 2L)),
            load = { id -> id.also { loaded += it } },
            toMember = { id -> PickMember(id, "t$id", null, "s", 0, MigrationPayload.OfManga(Manga.create())) },
        )

        loaded shouldBe emptyList()
    }

    @Test
    fun `chapters and categories are always offered, the rest only when some entry has them`() {
        applicableFlagsOf(listOf(Unit), { false }, { false }, { false }, { false }) shouldBe
            setOf(MigrationDataFlag.CHAPTER, MigrationDataFlag.CATEGORY)
    }

    @Test
    fun `a custom cover, notes and downloads are offered when an entry has each`() {
        val flags = applicableFlagsOf(
            listOf("cover", "notes", "downloads", "info"),
            hasCustomCover = { it == "cover" },
            hasNotes = { it == "notes" },
            hasDownloads = { it == "downloads" },
            hasCustomInfo = { it == "info" },
        )

        flags shouldBe MigrationDataFlag.entries.toSet()
    }

    private suspend fun members(
        ids: List<Long>,
        groups: Map<Long, List<Long>>,
        stored: Set<Long> = setOf(1L, 2L, 3L),
    ) = mergeGroupPickMembers(
        ids = ids,
        relatedIds = groups,
        load = { id -> id.takeIf { it in stored } },
        toMember = { id -> PickMember(id, "t$id", null, "s", 0, MigrationPayload.OfManga(Manga.create())) },
    )
}
