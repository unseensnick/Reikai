package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
        follows: Boolean = false,
    ) = isSameLaunch(requestedEntry, requestedChapter, requestedSourceScoped, openEntry, openChapter, false, follows)

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

    /** Continue while read aloud plays names the resume chapter, but asks for wherever read aloud is. */
    @Test
    fun `a launch following read aloud for the open entry is the same launch`() {
        launch(
            EntryId.Novel(1L),
            requestedChapter = 5L,
            openEntry = EntryId.Novel(1L),
            openChapter = 6L,
            follows = true,
        ) shouldBe true
    }

    /** Continue on one novel while another is read aloud asks for that novel's resume chapter. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("follows")
    fun `a launch follows read aloud only into the series it reads`(
        entry: EntryId,
        sessionSourceScoped: Boolean,
        follows: Boolean,
    ) {
        followsReadAloud(true, entry, speaking(sessionSourceScoped)) shouldBe follows
    }

    /** A follow may have opened the reader in read aloud's view, which is the view a new launch is compared with. */
    @Test
    fun `the open reader's own view is what a launch is compared with`() {
        openView(EntryId.Novel(2L), intentSourceScoped = false, novelView = 1L to true) shouldBe
            (EntryId.Novel(1L) to true)
    }

    @Test
    fun `a manga reader is compared by its launch`() {
        openView(EntryId.Manga(2L), intentSourceScoped = false, novelView = null) shouldBe (EntryId.Manga(2L) to false)
    }

    /** The notification names the view of the reader read aloud speaks through, so tapping it changes nothing. */
    @Test
    fun `a notification tap into the reader read aloud speaks through is the same launch`() {
        val series = ReadAloudSeries(novelId = 1L, memberIds = listOf(1L, 2L), sourceScoped = false)
        val (novelId, sourceScoped) = series.openedAs(readerView = 2L to false)
        val (openEntry, openSourceScoped) = openView(EntryId.Novel(1L), false, novelView = 2L to false)

        isSameLaunch(EntryId.Novel(novelId), 5L, sourceScoped, openEntry, 5L, openSourceScoped) shouldBe true
    }

    companion object {
        private fun speaking(sourceScoped: Boolean) =
            ReadAloudSessionState(ReadAloudSeries(novelId = 1L, memberIds = listOf(1L, 2L), sourceScoped))

        @JvmStatic
        fun follows() = listOf(
            Arguments.of(EntryId.Novel(2L), false, true),
            // In another view of the series, which the follow then takes on.
            Arguments.of(EntryId.Novel(2L), true, true),
            Arguments.of(EntryId.Novel(3L), false, false),
            Arguments.of(EntryId.Manga(1L), false, false),
        )
    }
}
