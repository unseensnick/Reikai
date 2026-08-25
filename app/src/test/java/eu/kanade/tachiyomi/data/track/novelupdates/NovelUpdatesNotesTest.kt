package eu.kanade.tachiyomi.data.track.novelupdates

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Test

/**
 * NovelUpdates keeps reading progress inside the user's own note, so these cases are about not
 * destroying text a person wrote. The reference fork loses it three ways: a note containing a quote
 * is truncated, an unparseable response is treated as an empty note and overwrites everything, and
 * escapes compound because the captured text is re-posted still escaped.
 */
class NovelUpdatesNotesTest {

    private val json = AppBindings.providesJson()

    private fun payload(notes: String) = """{"notes":"$notes","tags":"fantasy"}0"""

    @Test
    fun `a plain note and its tags parse`() {
        val parsed = parseNotesPayload(payload("re-reading before book 5"), json)

        parsed?.notes shouldBe "re-reading before book 5"
        parsed?.tags shouldBe "fantasy"
    }

    /** The case the reference fork truncates: its regex stops at the first escaped quote. */
    @Test
    fun `a note containing a quote survives intact`() {
        val parsed = parseNotesPayload(payload("""the \"good\" arc starts here"""), json)

        parsed?.notes shouldBe """the "good" arc starts here"""
    }

    /** WordPress appends a bare 0 after the JSON, and the note itself may contain braces. */
    @Test
    fun `the trailing zero is stripped without eating the note`() {
        parseNotesPayload("""{"notes":"vol {1} was best","tags":""}0""", json)?.notes shouldBe
            "vol {1} was best"
    }

    /** Null means "do not write". Treating it as an empty note is what overwrites the user's text. */
    @Test
    fun `an unparseable body yields null rather than an empty note`() {
        parseNotesPayload("<html>challenge page</html>", json) shouldBe null
        parseNotesPayload("", json) shouldBe null
    }

    @Test
    fun `progress reads back out of a note`() {
        progressFrom("total chapters read: 412") shouldBe 412
        progressFrom("Total  Chapters  Read:  7") shouldBe 7
    }

    /** Absent progress is null, never 0: 0 would present a parse miss as "nothing read". */
    @Test
    fun `a note with no progress line reads as unknown`() {
        progressFrom("just my thoughts") shouldBe null
    }

    @Test
    fun `an existing progress line is rewritten in place`() {
        notesWithProgress("great so far<br/>total chapters read: 10", 42) shouldBe
            "great so far<br/>total chapters read: 42"
    }

    @Test
    fun `a note without progress keeps its text and gains a line`() {
        val updated = notesWithProgress("dropped at the tournament arc", 88)

        updated shouldContain "dropped at the tournament arc"
        updated shouldBe "dropped at the tournament arc<br/>total chapters read: 88"
    }

    @Test
    fun `an empty note becomes just the progress line`() {
        notesWithProgress("", 3) shouldBe "total chapters read: 3"
    }

    /** Writing twice must not stack lines, which is how a note fills with duplicates over time. */
    @Test
    fun `repeated writes leave exactly one progress line`() {
        val once = notesWithProgress("my note", 1)
        val twice = notesWithProgress(once, 2)
        val thrice = notesWithProgress(twice, 3)

        thrice shouldBe "my note<br/>total chapters read: 3"
    }
}
