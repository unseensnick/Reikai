package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val ANILIST = 1L
private const val MAL = 2L
private const val SOURCE = 99L

class RecommendationHideFilterTest {

    private fun candidate(title: String, trackerId: Long? = null, remoteId: Long? = null, url: String = title) =
        RelatedMangaCandidate(
            sourceId = SOURCE,
            manga = SManga.create().apply {
                this.url = url
                this.title = title
            },
            trackerId = trackerId,
            remoteId = remoteId,
            origin = RecommendationOrigin.SourceNative("test"),
        )

    private fun index(
        sourceKeys: Set<Pair<String, Long>> = emptySet(),
        pairs: Set<Pair<Long, Long>> = emptySet(),
        anilistIds: Set<Long> = emptySet(),
        malIds: Set<Long> = emptySet(),
        titles: Set<String> = emptySet(),
    ) = RecommendationHideFilter.Index(sourceKeys, pairs, anilistIds, malIds, titles)

    private fun filter(
        inLibrary: RecommendationHideFilter.Index,
        status: RecommendationHideFilter.Index = index(),
        hidesInLibrary: Boolean = true,
    ) = RecommendationHideFilter(inLibrary, hidesInLibrary, status, anilistTrackerId = ANILIST, malTrackerId = MAL)

    @Test
    fun `exact tracker id match hides the candidate`() {
        val f = filter(index(pairs = setOf(ANILIST to 100L)))
        f.shouldHide(candidate("Whatever", trackerId = ANILIST, remoteId = 100L)) shouldBe true
    }

    @Test
    fun `cross-tracker anilist id hides an anilist candidate tracked elsewhere`() {
        // The user tracks it on MAL, which recorded the AniList cross-ref; the candidate is AniList-origin.
        val f = filter(index(anilistIds = setOf(100L)))
        f.shouldHide(candidate("Whatever", trackerId = ANILIST, remoteId = 100L)) shouldBe true
    }

    @Test
    fun `a source-native candidate is hidden by normalized title`() {
        val f = filter(index(titles = setOf(TitleNormalizer.normalize("The Villainess Turns the Hourglass"))))
        f.shouldHide(candidate("The Villainess Turns the Hourglass!")) shouldBe true
    }

    @Test
    fun `a tracker candidate whose id is unknown still hides by title`() {
        val f = filter(index(titles = setOf(TitleNormalizer.normalize("Solo Leveling"))))
        f.shouldHide(candidate("Solo Leveling", trackerId = ANILIST, remoteId = 777L)) shouldBe true
    }

    @Test
    fun `an unrelated candidate is not hidden`() {
        val f = filter(index(pairs = setOf(ANILIST to 100L), titles = setOf(TitleNormalizer.normalize("Other"))))
        f.shouldHide(candidate("Unrelated", trackerId = ANILIST, remoteId = 200L)) shouldBe false
    }

    @Test
    fun `a library row is matched by url and source under another title`() {
        val f = filter(index(sourceKeys = setOf("/series/1" to SOURCE)))
        f.isInLibrary(candidate("Listed Title", url = "/series/1")) shouldBe true
    }

    @Test
    fun `the same url under another source is not the library's row`() {
        val f = filter(index(sourceKeys = setOf("/series/1" to SOURCE + 1)))
        f.isInLibrary(candidate("Listed Title", url = "/series/1")) shouldBe false
    }

    @Test
    fun `a library title the source lists under another url is still in the library`() {
        val f =
            filter(
                index(
                    sourceKeys = setOf("/series/1/slug" to SOURCE),
                    titles = setOf(TitleNormalizer.normalize("Series")),
                ),
            )
        f.isInLibrary(candidate("Series", url = "/series/1")) shouldBe true
    }

    @Test
    fun `with the library filter off a library title is marked but not hidden`() {
        val f = filter(index(titles = setOf(TitleNormalizer.normalize("Series"))), hidesInLibrary = false)
        f.shouldHide(candidate("Series")) shouldBe false
    }

    @Test
    fun `a status match hides with the library filter off`() {
        val f = filter(inLibrary = index(), status = index(pairs = setOf(ANILIST to 100L)), hidesInLibrary = false)
        f.shouldHide(candidate("Completed", trackerId = ANILIST, remoteId = 100L)) shouldBe true
    }

    @Test
    fun `the status index hides independently of the in-library index`() {
        val f = filter(inLibrary = index(), status = index(pairs = setOf(ANILIST to 100L)))
        f.shouldHide(candidate("Completed", trackerId = ANILIST, remoteId = 100L)) shouldBe true
    }
}
