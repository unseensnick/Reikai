package reikai.presentation.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryQueryMatchTest {

    private fun match(
        query: String,
        id: Long = 1L,
        title: String = "",
        author: String? = null,
        artist: String? = null,
        description: String? = null,
        genre: List<String>? = null,
        sourceName: String = "",
        matchesSourceTerm: (String) -> Boolean = { false },
    ): Boolean = libraryQueryMatches(
        query, id, title, author, artist, description, genre, sourceName, matchesSourceTerm,
    )

    @Test
    fun `id prefix matches the exact entry id`() {
        match("id:42", id = 42L) shouldBe true
        match("id:42", id = 7L) shouldBe false
        match("id:notanumber", id = 42L) shouldBe false
    }

    @Test
    fun `src prefix delegates to the source-term matcher`() {
        match("src:webtoons", matchesSourceTerm = { it == "webtoons" }) shouldBe true
        match("src:other", matchesSourceTerm = { it == "webtoons" }) shouldBe false
    }

    @Test
    fun `plain text matches title, author and description case-insensitively`() {
        match("dawn", title = "Break of Dawn") shouldBe true
        match("alice", author = "Alice Writer") shouldBe true
        match("epic", description = "An EPIC saga") shouldBe true
        match("missing", title = "Something Else") shouldBe false
    }

    @Test
    fun `a comma term matches the source name or a genre`() {
        match("action", genre = listOf("Action", "Romance")) shouldBe true
        match("webtoons", sourceName = "Webtoons") shouldBe true
    }

    @Test
    fun `a negated term excludes matching entries`() {
        match("-action", genre = listOf("Action")) shouldBe false
        match("-action", genre = listOf("Romance")) shouldBe true
    }

    @Test
    fun `comma-separated terms must all match`() {
        match("action, romance", genre = listOf("Action", "Romance")) shouldBe true
        match("action, horror", genre = listOf("Action", "Romance")) shouldBe false
        match("action, -horror", genre = listOf("Action", "Romance")) shouldBe true
    }
}
