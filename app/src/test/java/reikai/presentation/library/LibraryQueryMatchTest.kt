package reikai.presentation.library

import io.kotest.matchers.shouldBe
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.api.Test

/**
 * The shared query kernel, exercised over a plain row type rather than `LibraryItem`, since the kernel is
 * pure over its accessors. Pins the two things that are Reikai's rather than upstream's: an inapplicable
 * field is false before negation, and the source key is matched as a String on both content types.
 */
class LibraryQueryMatchTest {

    private data class Row(
        val id: Long = 1L,
        val title: String = "Lord of the Mysteries",
        val author: String? = "Cuttlefish",
        val genre: List<String>? = listOf("Fantasy"),
        val sourceName: String = "novel arrow",
        val sourceKey: String = "novelarrow",
        val notes: String? = "",
        val unread: Long = 3L,
        /** Null models a novel, which has neither concept. */
        val interval: Int? = null,
        val nextUpdate: Long? = null,
    )

    /** Stands in for the id set each library resolves once per query from its own chapter table. */
    private val chapterMatches = mapOf("clown" to setOf(1L), "seer" to setOf(2L))

    private val fields = LibraryQueryFields<Row>(
        id = { it.id },
        title = { it.title },
        author = { it.author },
        artist = { null },
        description = { null },
        notes = { it.notes },
        genre = { it.genre },
        sourceName = { it.sourceName },
        sourceKey = { it.sourceKey },
        sourceLanguage = { "en" },
        isLocal = { false },
        unreadCount = { it.unread },
        readCount = { 0L },
        totalChapters = { 10L },
        dateAdded = { 0L },
        fetchInterval = { it.interval },
        nextUpdate = { it.nextUpdate },
        matchesChapter = { row, term -> chapterMatches[term]?.contains(row.id) },
    )

    private fun matches(query: String, row: Row = Row()) =
        libraryQueryMatches(QueryNode.from(query), row, fields)

    /** The same fields with an override on row 1, standing in for a renamed entry. */
    private val overlaidFields = LibraryQueryFields<Row>(
        id = fields.id,
        title = { if (it.id == 1L) "Renamed Entry" else it.title },
        author = { if (it.id == 1L) "New Author" else it.author },
        artist = fields.artist,
        description = fields.description,
        notes = fields.notes,
        genre = { if (it.id == 1L) listOf("Renamed Genre") else it.genre },
        sourceName = fields.sourceName,
        sourceKey = fields.sourceKey,
        sourceLanguage = fields.sourceLanguage,
        isLocal = fields.isLocal,
        unreadCount = fields.unreadCount,
        readCount = fields.readCount,
        totalChapters = fields.totalChapters,
        dateAdded = fields.dateAdded,
        fetchInterval = fields.fetchInterval,
        nextUpdate = fields.nextUpdate,
        matchesChapter = fields.matchesChapter,
    )

    private fun matchesOverlaid(query: String) =
        libraryQueryMatches(QueryNode.from(query), Row(), overlaidFields)

    @Test
    fun `a bare word sweeps the text fields`() {
        matches("mysteries") shouldBe true
    }

    @Test
    fun `an inapplicable comparison is false`() {
        matches("nu<2030-01-01") shouldBe false
    }

    @Test
    fun `an inapplicable comparison stays false when negated`() {
        // The whole point of the gate: a novel must not be pulled in by a field it cannot answer.
        matches("-nu<2030-01-01") shouldBe false
    }

    @Test
    fun `a field the row can answer still follows the absent-then-negate convention`() {
        // artist is null here, so `-artist:x` keeps the row, matching upstream's semantics.
        matches("-artist:hoshino") shouldBe true
    }

    @Test
    fun `srcid matches the source key exactly on either content type`() {
        matches("srcid:novelarrow") shouldBe true
        matches("srcid:novel") shouldBe false
    }

    @Test
    fun `source matches the display name, not the key`() {
        matches("source:arrow") shouldBe true
    }

    @Test
    fun `id compares numerically`() {
        matches("id=1") shouldBe true
        matches("id=2") shouldBe false
    }

    @Test
    fun `comparisons the row can answer work`() {
        matches("unread>2") shouldBe true
        matches("unread>5") shouldBe false
    }

    @Test
    fun `an unknown field degrades to a plain text term`() {
        // Upstream's parser behaviour, worth pinning because it is what a typo does.
        matches("nonsense:mysteries") shouldBe false
    }

    @Test
    fun `chapter matches through the resolved id set`() {
        matches("chapter:clown") shouldBe true
        matches("chapter:seer") shouldBe false
        matches("chapter:clown", Row(id = 2L)) shouldBe false
    }

    @Test
    fun `a term with no resolved lookup does not match`() {
        matches("chapter:nothingresolvedthis") shouldBe false
    }

    @Test
    fun `a bare word never sweeps chapter names`() {
        // CHAPTER is fieldOnly, so a plain search must never pay a chapter lookup.
        matches("clown") shouldBe false
    }

    @Test
    fun `chapter terms are extracted from the parsed tree, quoting and negation included`() {
        QueryNode.from("chapter:clown").chapterSearchTerms() shouldBe setOf("clown")
        QueryNode.from("-chapter:\"the clown\"").chapterSearchTerms() shouldBe setOf("the clown")
        QueryNode.from("chapter:a || (chapter:b fantasy)").chapterSearchTerms() shouldBe setOf("a", "b")
        QueryNode.from("fantasy").chapterSearchTerms() shouldBe emptySet()
    }

    @Test
    fun `a custom override is what search matches`() {
        // The name on the card wins over the source's.
        matchesOverlaid("renamed") shouldBe true
        matchesOverlaid("title:renamed") shouldBe true
        matchesOverlaid("author:\"new author\"") shouldBe true
        matchesOverlaid("genre:renamed") shouldBe true
    }

    @Test
    fun `a custom override replaces the source value rather than adding to it`() {
        // Searching the source title of a renamed entry no longer finds it, which is the tradeoff.
        matchesOverlaid("mysteries") shouldBe false
        matchesOverlaid("author:cuttlefish") shouldBe false
    }

    @Test
    fun `an unoverridden field still reads the source value`() {
        matchesOverlaid("srcid:novelarrow") shouldBe true
    }

    @Test
    fun `boolean operators and negation compose`() {
        matches("fantasy || horror") shouldBe true
        matches("fantasy -mysteries") shouldBe false
        matches("\"lord of the\"") shouldBe true
    }
}
