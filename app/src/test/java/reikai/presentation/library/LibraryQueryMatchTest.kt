package reikai.presentation.library

import io.kotest.matchers.shouldBe
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.domain.category.model.Category
import kotlin.time.Instant

/**
 * The shared query kernel, exercised over a plain row type rather than `LibraryItem`, since the kernel is
 * pure over its accessors. Pins what is Reikai's rather than upstream's: the source key is matched as a
 * String on both content types.
 */
class LibraryQueryMatchTest {

    data class Row(
        val id: Long = 1L,
        val title: String = "Lord of the Mysteries",
        val author: String? = "Cuttlefish",
        val genre: List<String>? = listOf("Fantasy"),
        val sources: List<LibraryQuerySource> = listOf(source()),
        val notes: String? = "",
        val unread: Long = 3L,
        val status: Long = 1L,
        val trackers: List<String> = listOf("AniList"),
        val categories: List<Long> = listOf(5L),
        val score: Double? = 7.2,
    )

    /** Stands in for the category ids each library resolves once per query by name. */
    private val categoryMatches = mapOf("read" to setOf(5L))

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
        sources = { it.sources },
        unreadCount = { it.unread },
        readCount = { 0L },
        totalChapters = { 10L },
        dateAdded = { EPOCH },
        fetchInterval = { 0 },
        nextUpdate = { EPOCH },
        matchesChapter = { row, term -> chapterMatches[term]?.contains(row.id) },
        matchesTagTerm = { _, _ -> false },
        status = { it.status },
        trackerNames = { it.trackers },
        categoryIds = { it.categories },
        matchesCategory = { row, term -> categoryMatches[term]?.let { ids -> row.categories.any { it in ids } } },
        meanScore = { it.score },
    )

    /** The same fields with an override on row 1, standing in for a renamed entry. */
    private val overlaidFields = LibraryQueryFields<Row>(
        id = fields.id,
        title = { if (it.id == 1L) "Renamed Entry" else it.title },
        author = { if (it.id == 1L) "New Author" else it.author },
        artist = fields.artist,
        description = fields.description,
        notes = fields.notes,
        genre = { if (it.id == 1L) listOf("Renamed Genre") else it.genre },
        sources = fields.sources,
        unreadCount = fields.unreadCount,
        readCount = fields.readCount,
        totalChapters = fields.totalChapters,
        dateAdded = fields.dateAdded,
        fetchInterval = fields.fetchInterval,
        nextUpdate = fields.nextUpdate,
        matchesChapter = fields.matchesChapter,
        matchesTagTerm = fields.matchesTagTerm,
        status = fields.status,
        trackerNames = fields.trackerNames,
        categoryIds = fields.categoryIds,
        matchesCategory = fields.matchesCategory,
        meanScore = fields.meanScore,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldQueries")
    fun `a query matches the fields it names`(name: String, query: String, row: Row, expected: Boolean) {
        libraryQueryMatches(QueryNode.from(query), row, fields) shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("absentAndMergedQueries")
    fun `an absent value or a merged member is matched across every source`(
        name: String,
        query: String,
        row: Row,
        expected: Boolean,
    ) {
        libraryQueryMatches(QueryNode.from(query), row, fields) shouldBe expected
    }

    // The name on the card wins over the source's, and searching the source title of a renamed entry
    // no longer finds it, which is the tradeoff.
    @ParameterizedTest(name = "{0}")
    @MethodSource("overlaidQueries")
    fun `a custom override is what search matches`(name: String, query: String, expected: Boolean) {
        libraryQueryMatches(QueryNode.from(query), Row(), overlaidFields) shouldBe expected
    }

    /** Some galleries file their site category under a `category` tag namespace, which is not the user's. */
    @ParameterizedTest
    @ValueSource(strings = ["category:manga", "status:manga", "tracker:manga"])
    fun `a field only the library holds is never answered by a gallery tag`(query: String) {
        val galleryFields = LibraryQueryFields<Row>(
            id = fields.id,
            title = fields.title,
            author = fields.author,
            artist = fields.artist,
            description = fields.description,
            notes = fields.notes,
            genre = fields.genre,
            sources = fields.sources,
            unreadCount = fields.unreadCount,
            readCount = fields.readCount,
            totalChapters = fields.totalChapters,
            dateAdded = fields.dateAdded,
            fetchInterval = fields.fetchInterval,
            nextUpdate = fields.nextUpdate,
            matchesChapter = fields.matchesChapter,
            matchesTagTerm = { _, _ -> true },
            status = fields.status,
            trackerNames = fields.trackerNames,
            categoryIds = fields.categoryIds,
            matchesCategory = fields.matchesCategory,
            meanScore = fields.meanScore,
        )

        libraryQueryMatches(QueryNode.from(query), Row(), galleryFields) shouldBe false
    }

    @Test
    fun `a category term resolves to the category with that whole name only`() {
        val categories = listOf(category(5L, "Read"), category(6L, "Reading"))

        QueryNode.from("category:read").categoryMatches(categories) shouldBe mapOf("read" to setOf(5L))
    }

    @Test
    fun `a tracker or score term makes the query read tracks`() {
        listOf("tracker:anilist", "score>3", "title").map { QueryNode.from(it).readsTracks() } shouldBe
            listOf(true, true, false)
    }

    private fun category(id: Long, name: String) = Category(id = id, name = name, order = 0L, flags = 0L)

    @Test
    fun `chapter terms are extracted from the parsed tree, quoting and negation included`() {
        QueryNode.from("chapter:clown").chapterSearchTerms() shouldBe setOf("clown")
        QueryNode.from("-chapter:\"the clown\"").chapterSearchTerms() shouldBe setOf("the clown")
        QueryNode.from("chapter:a || (chapter:b fantasy)").chapterSearchTerms() shouldBe setOf("a", "b")
        QueryNode.from("fantasy").chapterSearchTerms() shouldBe emptySet()
    }

    companion object {
        private val EPOCH = Instant.fromEpochMilliseconds(0)

        private fun source(
            key: String = "novelarrow",
            name: String = "novel arrow",
            language: String? = "en",
            isLocal: Boolean = false,
        ) = LibraryQuerySource(key, name, language, isLocal)

        /** A merged series led by the default source, with a local Japanese member. */
        private val merged = Row(
            sources = listOf(source(), source(key = "member", name = "member site", language = "ja", isLocal = true)),
        )

        private fun case(name: String, query: String, expected: Boolean, row: Row = Row()) =
            Arguments.of(name, query, row, expected)

        @JvmStatic
        fun fieldQueries() = listOf(
            case("status matches its word", "status:ongoing", true),
            case("status matches its code", "status:1", true),
            case("tracker matches a whole tracker name", "tracker:anilist", true),
            case("tracker does not match part of a name", "tracker:ani", false),
            case("category matches through the resolved ids", "category:read", true),
            case("score compares the mean", "score>=7 score<7.5", true),
            case("an unscored entry fails a score comparison", "score>0", false, Row(score = null)),
            case("a negated score comparison finds an unscored entry", "-score>0", true, Row(score = null)),
            case("a bare word sweeps the text fields", "mysteries", true),
            // artist is null here, so `-artist:x` keeps the row, matching upstream's semantics.
            case("an absent field follows the absent-then-negate convention", "-artist:hoshino", true),
            case("srcid matches the source key exactly", "srcid:novelarrow", true),
            case("srcid does not match part of the source key", "srcid:novel", false),
            case("source matches the display name, not the key", "source:arrow", true),
            case("id compares numerically, matching", "id=1", true),
            case("id compares numerically, not matching", "id=2", false),
            case("a comparison the row satisfies matches", "unread>2", true),
            case("a comparison the row fails does not match", "unread>5", false),
            case("a negated comparison the row fails matches", "-unread>5", true),
            case("a negated comparison the row satisfies does not match", "-unread>2", false),
            // Upstream's parser behaviour, worth pinning because it is what a typo does.
            case("an unknown field degrades to a plain text term", "nonsense:mysteries", false),
            case("chapter matches through the resolved id set", "chapter:clown", true),
            case("chapter misses a term resolved to other rows", "chapter:seer", false),
            case("chapter misses a row outside the resolved id set", "chapter:clown", false, Row(id = 2L)),
            case("a term with no resolved lookup does not match", "chapter:nothingresolvedthis", false),
            // CHAPTER is fieldOnly, so a plain search must never pay a chapter lookup.
            case("a bare word never sweeps chapter names", "clown", false),
            case("a negated chapter term excludes through the id set", "-chapter:clown", false),
            case("a negated chapter term keeps a row outside the id set", "-chapter:clown", true, Row(id = 2L)),
            case("or matches either side", "fantasy || horror", true),
            case("negation excludes a matching word", "fantasy -mysteries", false),
            case("a quoted phrase matches as one term", "\"lord of the\"", true),
            case("a date before the cutoff matches", "added<2030-01-01", true),
            case("a date after the cutoff does not match", "added>2030-01-01", false),
            // genre:"" is the reachable spelling for "no value" (a bare trailing colon lexes as text).
            case("an empty quoted field value matches an absent field", "artist:\"\"", true),
            case("an empty quoted field value misses a present field", "genre:\"\"", false),
        )

        @JvmStatic
        fun absentAndMergedQueries() = listOf(
            case("an empty status asks for an unknown one", "status:\"\"", true, Row(status = 0L)),
            case("an empty tracker asks for an untracked entry", "tracker:\"\"", true, Row(trackers = emptyList())),
            case(
                "an empty category asks for an entry filed nowhere",
                "category:\"\"",
                true,
                Row(categories = listOf(0L)),
            ),
            case(
                "a source's own query finds entries on that source, whatever its key holds",
                sourceKeyQuery("ireader:42"),
                true,
                Row(sources = listOf(source(key = "ireader:42"))),
            ),
            case(
                "a source's own query leaves a source whose key only begins the same way",
                sourceKeyQuery("ireader:4"),
                false,
                Row(sources = listOf(source(key = "ireader:42"))),
            ),
            case("a merged series is found from a source other than its lead", sourceKeyQuery("member"), true, merged),
            case("excluding a merged series' member source excludes the series", "-srcid:member", false, merged),
            case("a merged series matches a member's source name", "source:\"member site\"", true, merged),
            case("a merged series matches a plain word naming a member's source", "\"member site\"", true, merged),
            case("a merged series with a local member matches source local", "source:local", true, merged),
            case("a merged series matches a member's language", "lang:ja", true, merged),
            case(
                "an absent language needs every source of a merged series to lack one",
                "lang:\"\"",
                false,
                Row(sources = listOf(source(language = ""), source(key = "b"))),
            ),
            case(
                "a merged series whose sources all lack a language matches an absent one",
                "lang:\"\"",
                true,
                Row(sources = listOf(source(language = ""), source(key = "b", language = null))),
            ),
            case(
                "an absent source name needs every source of a merged series to lack one",
                "source:\"\"",
                false,
                Row(sources = listOf(source(name = ""), source(key = "b"))),
            ),
        )

        @JvmStatic
        fun overlaidQueries() = listOf(
            Arguments.of("a bare word finds the overridden title", "renamed", true),
            Arguments.of("title finds the overridden title", "title:renamed", true),
            Arguments.of("author finds the overridden author", "author:\"new author\"", true),
            Arguments.of("genre finds the overridden genre", "genre:renamed", true),
            Arguments.of("the source title no longer finds a renamed entry", "mysteries", false),
            Arguments.of("the source author no longer finds a re-authored entry", "author:cuttlefish", false),
            Arguments.of("an unoverridden field still reads the source value", "srcid:novelarrow", true),
        )
    }
}
