package reikai.presentation.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.recommendation.RECOMMENDS_SOURCE
import reikai.domain.recommendation.RecommendationOrigin
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.presentation.browse.globalsearch.EntryGlobalSearchScreen

class RelatedDestinationTest {

    private val candidate = RelatedMangaCandidate(
        sourceId = RECOMMENDS_SOURCE,
        manga = SManga.create().apply {
            url = "/a"
            title = "A title"
        },
        origin = RecommendationOrigin.Tracker("tracker"),
    )

    @Test
    fun `a card resolved to a stored manga opens that manga`() {
        (relatedDestination(candidate, localId = 7L) as? MangaScreen)?.mangaId shouldBe 7L
    }

    @Test
    fun `a card with nothing to open searches manga for its title`() {
        (relatedDestination(candidate, localId = null) as? EntryGlobalSearchScreen)
            ?.let { it.searchQuery to it.scopedContentType } shouldBe ("A title" to ContentType.MANGA)
    }
}
