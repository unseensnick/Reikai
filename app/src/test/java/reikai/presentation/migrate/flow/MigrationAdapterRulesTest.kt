package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.Novel
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
}
