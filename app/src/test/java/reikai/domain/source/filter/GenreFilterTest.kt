package reikai.domain.source.filter

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A genre tapped on a details page, matched onto a Mihon filter list for manga and novels alike. */
class GenreFilterTest {

    private class Genre(name: String) : Filter.TriState(name)
    private class Tag(name: String) : Filter.CheckBox(name)
    private class Genres(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)
    private class Tags(tags: List<Tag>) : Filter.Group<Tag>("Tags", tags)
    private class Status : Filter.Select<String>("Status", arrayOf("Any", "Ongoing", "Completed"))

    @Test
    fun `a genre in a group is included`() {
        val genres = Genres(listOf(Genre("Action"), Genre("Romance")))
        FilterList(genres).selectGenre("romance")
        genres.state.map { it.state } shouldBe listOf(Filter.TriState.STATE_IGNORE, Filter.TriState.STATE_INCLUDE)
    }

    @Test
    fun `a checkbox of that name is ticked`() {
        val tags = Tags(listOf(Tag("Isekai")))
        FilterList(tags).selectGenre("Isekai")
        tags.state.single().state shouldBe true
    }

    @Test
    fun `a select offering that name moves to it`() {
        val status = Status()
        FilterList(status).selectGenre("completed")
        status.state shouldBe 2
    }

    @Test
    fun `a match is reported`() {
        FilterList(Genres(listOf(Genre("Action")))).selectGenre("Action") shouldBe true
    }

    @Test
    fun `no filter of that name is reported and changes nothing`() {
        val genres = Genres(listOf(Genre("Action")))
        FilterList(genres).selectGenre("Horror") shouldBe false
        genres.state.single().state shouldBe Filter.TriState.STATE_IGNORE
    }

    @Test
    fun `only the first filter of that name is turned on`() {
        val first = Genres(listOf(Genre("Drama")))
        val second = Tags(listOf(Tag("Drama")))
        FilterList(first, second).selectGenre("Drama")
        second.state.single().state shouldBe false
    }
}
