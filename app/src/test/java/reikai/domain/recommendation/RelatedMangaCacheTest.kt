package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

class RelatedMangaCacheTest {

    private fun candidates(vararg urls: String) = urls.map { url ->
        RelatedMangaCandidate(
            sourceId = 1L,
            trackerName = null,
            manga = SManga.create().apply {
                this.url = url
                title = url
            },
            origin = RecommendationOrigin.SourceNative("source"),
        )
    }

    private val full = candidates("a", "b", "c")

    @Test
    fun `a streamed snapshot of a refresh does not replace a complete pool`() {
        val cache = RelatedMangaCache()
        cache.put(ID, full.take(2), full)
        val seeded = cache.get(ID)

        cache.put(ID, candidates("x"), candidates("x"), isComplete = false)

        cache.get(ID) shouldBeSameInstanceAs seeded
    }

    @Test
    fun `an empty refresh keeps the pool and its fetch time, so the next open retries`() {
        val cache = RelatedMangaCache()
        cache.put(ID, full.take(2), full)
        val seeded = cache.get(ID)

        cache.put(ID, emptyList(), emptyList())

        cache.get(ID) shouldBeSameInstanceAs seeded
    }

    @Test
    fun `a complete refresh replaces the pool`() {
        val cache = RelatedMangaCache()
        cache.put(ID, full.take(2), full)

        cache.put(ID, candidates("x"), candidates("x"))

        cache.get(ID)?.fullPool shouldBe candidates("x")
    }

    @Test
    fun `an empty result is stored when nothing was cached`() {
        val cache = RelatedMangaCache()

        cache.put(ID, emptyList(), emptyList())

        cache.get(ID)!!.fullPool.shouldBeEmpty()
    }

    private companion object {
        const val ID = 1L
    }
}
