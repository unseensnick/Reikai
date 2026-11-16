package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

/**
 * The Intent halves are a thin shim over [entryIdOf], which is where the decision that matters lives:
 * a wrong answer here opens the wrong entry rather than failing, because a manga and a novel can hold
 * the same row id.
 */
class ReaderIntentTest {

    /** A launch in the scope the open session has, unless a case says otherwise. */
    private fun launch(
        requestedEntry: EntryId,
        requestedChapter: Long,
        openEntry: EntryId,
        openChapter: Long,
        requestedSourceScoped: Boolean = false,
    ) = isSameLaunch(requestedEntry, requestedChapter, requestedSourceScoped, openEntry, openChapter, false)

    /** A new-chapter notification asks for the source's own chapters, which a group-scoped session never shows. */
    @Test
    fun `a launch asking for another scope on the chapter on screen is a new launch`() {
        launch(
            EntryId.Manga(1L),
            requestedChapter = 5L,
            openEntry = EntryId.Manga(1L),
            openChapter = 5L,
            requestedSourceScoped = true,
        ) shouldBe false
    }

    /** A chapter notification or a history row naming the chapter already on screen changes nothing. */
    @Test
    fun `a launch naming the chapter on screen is the same launch`() {
        launch(EntryId.Manga(1L), requestedChapter = 5L, openEntry = EntryId.Manga(1L), openChapter = 5L) shouldBe
            true
    }

    /** The reader moved on from the chapter it opened with, so asking for that one again is a real request. */
    @Test
    fun `a launch naming the opening chapter after the reader moved on is a new launch`() {
        launch(EntryId.Manga(1L), requestedChapter = 5L, openEntry = EntryId.Manga(1L), openChapter = 6L) shouldBe
            false
    }

    @Test
    fun `a launch naming no chapter for the open entry is the same launch`() {
        launch(EntryId.Novel(1L), requestedChapter = NO_ID, openEntry = EntryId.Novel(1L), openChapter = 6L) shouldBe
            true
    }

    @Test
    fun `a launch for another entry is a new launch`() {
        launch(EntryId.Novel(2L), requestedChapter = 5L, openEntry = EntryId.Novel(1L), openChapter = 5L) shouldBe
            false
    }

    @Test
    fun `a manga tag reads back as a manga id`() {
        entryIdOf(ContentType.MANGA.name, rawId = 7L) shouldBe EntryId.Manga(7L)
    }

    @Test
    fun `a novel tag reads back as a novel id`() {
        entryIdOf(ContentType.NOVELS.name, rawId = 7L) shouldBe EntryId.Novel(7L)
    }

    /** The same raw id under the two tags must not produce the same entry. */
    @Test
    fun `the tag is what separates the two id spaces`() {
        val manga = entryIdOf(ContentType.MANGA.name, rawId = 42L)
        val novel = entryIdOf(ContentType.NOVELS.name, rawId = 42L)

        (manga == novel) shouldBe false
    }

    @Test
    fun `a missing id is not an entry`() {
        entryIdOf(ContentType.MANGA.name, rawId = NO_ID) shouldBe null
    }

    @Test
    fun `a missing tag is not an entry`() {
        entryIdOf(typeName = null, rawId = 7L) shouldBe null
    }

    /** ALL is a library filter, so a launch carrying it names no single entry to open. */
    @Test
    fun `the all filter is not an entry`() {
        entryIdOf(ContentType.ALL.name, rawId = 7L) shouldBe null
    }

    /** An intent written by a newer build must fail closed rather than guess a type. */
    @Test
    fun `an unknown tag is not an entry`() {
        entryIdOf("AUDIOBOOKS", rawId = 7L) shouldBe null
    }
}
