package reikai.data.track

import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.parseResponse
import io.kotest.matchers.shouldBe
import mihon.graphql.kitsu.ReikaiKitsuGetMangaMetadataQuery
import mihon.graphql.shikimori.ReikaiShikimoriGetMangaMetadataQuery
import okio.Buffer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.details.isMissingOnTracker

/**
 * Kitsu and Shikimori answer an id they have no entry for with HTTP 200 and an empty result (probed live),
 * never a 404, so "Fill from tracker" reads that answer as missing; a GraphQL error is a failure instead.
 */
class TrackerEntryResponseTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("trackers")
    fun `an id with no entry reads as missing on the tracker`(tracker: GraphQlTracker) {
        failureOf { tracker.entry(tracker.noEntryAnswer) }?.let(::isMissingOnTracker) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trackers")
    fun `a GraphQL error fails without reading as missing`(tracker: GraphQlTracker) {
        failureOf { tracker.entry("""{"errors":[{"message":"Internal error"}],"data":null}""") }
            ?.let(::isMissingOnTracker) shouldBe false
    }

    private fun failureOf(block: () -> Any): Throwable? = runCatching(block).exceptionOrNull()

    class GraphQlTracker(private val name: String, val noEntryAnswer: String, val entry: (String) -> Any) {
        override fun toString() = name
    }

    companion object {
        private fun <D : Operation.Data> Operation<D>.answer(json: String) =
            parseResponse(Buffer().writeUtf8(json).jsonReader())

        // Each picks the entry as its tracker's getMangaMetadata does.
        @JvmStatic
        fun trackers() = listOf(
            GraphQlTracker("Kitsu", """{"data":{"findMangaById":null}}""") { json ->
                ReikaiKitsuGetMangaMetadataQuery(id = "1").answer(json)
                    .trackerEntryOrThrow("Kitsu") { it.findMangaById }
            },
            GraphQlTracker("Shikimori", """{"data":{"mangas":[]}}""") { json ->
                ReikaiShikimoriGetMangaMetadataQuery(ids = "1").answer(json)
                    .trackerEntryOrThrow("Shikimori") { it.mangas.firstOrNull() }
            },
        )
    }
}
