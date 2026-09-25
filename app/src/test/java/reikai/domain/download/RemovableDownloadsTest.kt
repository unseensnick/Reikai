package reikai.domain.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.chapter.model.Chapter

/**
 * The downloads a delete may remove, pinned once over both chapter models: a manual delete of either
 * type runs this, so a bookmarked chapter or one in a kept category survives on both or on neither.
 */
class RemovableDownloadsTest {

    /** One content type's chapter model, reduced to what the rule reads. */
    interface Shape {
        suspend fun removable(
            chapters: List<Triple<String, Boolean, Boolean>>,
            excluded: Set<String>,
            allowBookmarked: Boolean,
        ): List<String>
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    fun `a bookmarked chapter stays unless bookmarked deletes are allowed`(shape: Shape) = runTest {
        shape.removable(listOf(Triple("a", false, true), Triple("b", false, false)), emptySet(), false) shouldBe
            listOf("b")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    fun `a bookmarked chapter goes when bookmarked deletes are allowed`(shape: Shape) = runTest {
        shape.removable(listOf(Triple("a", false, true)), emptySet(), true) shouldBe listOf("a")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    fun `in a kept category only unread chapters go`(shape: Shape) = runTest {
        shape.removable(listOf(Triple("read", true, false), Triple("unread", false, false)), setOf("0"), true) shouldBe
            listOf("unread")
    }

    companion object {
        private val manga = object : Shape {
            override suspend fun removable(
                chapters: List<Triple<String, Boolean, Boolean>>,
                excluded: Set<String>,
                allowBookmarked: Boolean,
            ) = removableDownloads(
                chapters.map { (name, read, bookmark) ->
                    Chapter.create().copy(name = name, read = read, bookmark = bookmark)
                },
                excluded,
                allowBookmarked,
                isRead = Chapter::read,
                isBookmarked = Chapter::bookmark,
                categoryIds = { emptyList() },
            ).map { it.name }

            override fun toString() = "manga"
        }

        private val novel = object : Shape {
            override suspend fun removable(
                chapters: List<Triple<String, Boolean, Boolean>>,
                excluded: Set<String>,
                allowBookmarked: Boolean,
            ) = removableDownloads(
                chapters.map { (name, read, bookmark) -> novelChapter(name, read, bookmark) },
                excluded,
                allowBookmarked,
                isRead = NovelChapter::read,
                isBookmarked = NovelChapter::bookmark,
                categoryIds = { emptyList() },
            ).map { it.name }

            override fun toString() = "novel"
        }

        private fun novelChapter(name: String, read: Boolean, bookmark: Boolean) = NovelChapter(
            id = 1L,
            novelId = 1L,
            url = name,
            name = name,
            read = read,
            bookmark = bookmark,
            lastTextProgress = 0L,
            chapterNumber = 1.0,
            sourceOrder = 0L,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )

        @JvmStatic
        fun shapes() = listOf(manga, novel)
    }
}
