package reikai.novel.source.ireader

import eu.kanade.tachiyomi.source.model.Filter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import ireader.core.source.model.Filter as IReaderFilter

class IReaderFiltersTest {

    @Test
    fun `the query is not one of the sheet's filters`() {
        toMihonFilters(listOf(IReaderFilter.Title(), IReaderFilter.Note("n"))).map { it.name } shouldBe listOf("n")
    }

    @Test
    fun `the query goes to the source as a Title`() {
        val sent = toIReaderFilters(toMihonFilters(emptyList()), "bald guy")

        sent.filterIsInstance<IReaderFilter.Title>().single().value shouldBe "bald guy"
    }

    @Test
    fun `a ticked checkbox is sent as true`() {
        val check = IReaderFilter.Check("Completed")
        val sheet = toMihonFilters(listOf(check))
        (sheet.single() as Filter.CheckBox).state = true

        toIReaderFilters(sheet, "")
        check.value shouldBe true
    }

    @Test
    fun `an unticked checkbox is sent unset, as IReader starts it`() {
        val check = IReaderFilter.Check("Completed", value = true)
        val sheet = toMihonFilters(listOf(check))
        (sheet.single() as Filter.CheckBox).state = false

        toIReaderFilters(sheet, "")
        check.value shouldBe null
    }

    @Test
    fun `a check that can exclude is a tri-state`() {
        toMihonFilters(listOf(IReaderFilter.Genre("Action", allowsExclusion = true))).single()
            .shouldBeInstanceOf<Filter.TriState>()
    }

    @Test
    fun `an excluded tri-state is sent as false`() {
        val genre = IReaderFilter.Genre("Action", allowsExclusion = true)
        val sheet = toMihonFilters(listOf(genre))
        (sheet.single() as Filter.TriState).state = Filter.TriState.STATE_EXCLUDE

        toIReaderFilters(sheet, "")
        genre.value shouldBe false
    }

    @Test
    fun `a picked option is sent by its index`() {
        val select = IReaderFilter.Select("Status", arrayOf("Any", "Ongoing"))
        val sheet = toMihonFilters(listOf(select))
        (sheet.single() as Filter.Select<*>).state = 1

        toIReaderFilters(sheet, "")
        select.value shouldBe 1
    }

    @Test
    fun `a picked sort is sent with its direction`() {
        val sort = IReaderFilter.Sort("Sort", arrayOf("Latest", "Popular"))
        val sheet = toMihonFilters(listOf(sort))
        (sheet.single() as Filter.Sort).state = Filter.Sort.Selection(1, ascending = false)

        toIReaderFilters(sheet, "")
        sort.value shouldBe IReaderFilter.Sort.Selection(1, false)
    }

    @Test
    fun `a text field is sent as typed`() {
        val author = IReaderFilter.Author()
        val sheet = toMihonFilters(listOf(author))
        (sheet.single() as Filter.Text).state = "Someone"

        toIReaderFilters(sheet, "")
        author.value shouldBe "Someone"
    }

    @Test
    fun `a filter inside a group is sent too`() {
        val check = IReaderFilter.Check("Completed")
        val sheet = toMihonFilters(listOf(IReaderFilter.Group("Status", listOf(check))))
        ((sheet.single() as Filter.Group<*>).state.single() as Filter.CheckBox).state = true

        toIReaderFilters(sheet, "")
        check.value shouldBe true
    }
}
