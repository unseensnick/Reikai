package reikai.novel.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.chapter.ArrivingChapter
import reikai.domain.chapter.StoredChapter
import reikai.domain.chapter.chapterArrivals
import reikai.novel.local.epub.EpubArchive
import reikai.novel.local.epub.EpubBook
import reikai.novel.local.epub.EpubChapters
import reikai.novel.local.epub.epub
import reikai.novel.local.epub.nav
import reikai.novel.local.epub.opf
import reikai.novel.local.epub.xhtml
import kotlin.time.Instant

/**
 * A book updated with a chapter added partway through keeps every other chapter's read state where it
 * was: a sync keeps a chapter whose address it lists again, and hands a removed chapter's state to one
 * added under its number.
 */
class LocalNovelChapterIdentityTest {

    private val fill = "lorem ".repeat(30).trim()

    private fun book(vararg chapters: Pair<String, String>): EpubArchive = epub(
        mapOf(
            "OEBPS/content.opf" to opf(chapters.map { (_, file) -> file to "$file.xhtml" }, nav = "nav.xhtml"),
            "OEBPS/nav.xhtml" to nav(*chapters.map { (title, file) -> title to "$file.xhtml" }.toTypedArray()),
        ) + chapters.associate { (title, file) -> "OEBPS/$file.xhtml" to xhtml("<p>$title $fill</p>") },
    )

    private fun listing(archive: EpubArchive) =
        LocalNovelSource.localChapters(
            "Saga",
            listOf("Book.epub" to EpubChapters.list(EpubBook.parse(archive), archive)),
        )

    @Test
    fun `a chapter added partway through a book hands no read state to another chapter`() {
        val before = listing(book("One" to "a", "Two" to "b", "Three" to "c"))
        val after = listing(book("One" to "a", "New" to "x", "Two" to "b", "Three" to "c"))
        val read = setOf("One", "Three")
        val readAt = before.associate { it.path to (it.ref.title in read) }
        val added = after.filter { it.path !in readAt }
        val removed = before.filter { chapter -> after.none { it.path == chapter.path } }
        val arrivals = chapterArrivals(
            added = added.map { ArrivingChapter(it.number) },
            stored = after.filter { it.path in readAt }.map { StoredChapter(it.number, readAt.getValue(it.path)) },
            removed = removed.map { StoredChapter(it.number, readAt.getValue(it.path)) },
            markDuplicateAsRead = false,
            now = Instant.fromEpochMilliseconds(0),
        )

        val readAfter = after.associate { chapter ->
            chapter.ref.title to (readAt[chapter.path] ?: arrivals[added.indexOf(chapter)].read)
        }

        readAfter shouldBe mapOf("One" to true, "New" to false, "Two" to false, "Three" to true)
    }
}
