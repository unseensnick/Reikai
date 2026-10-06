package reikai.domain.chapter

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.toNovelChapter
import reikai.novel.host.ChapterItem
import tachiyomi.domain.chapter.model.Chapter

/** The group a source names on a chapter is stored trimmed, and a blank one as none, for both types. */
class ChapterScanlatorConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter's group is stored trimmed`(type: Type) {
        type.stored(" Group ") shouldBe "Group"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a blank group is stored as none`(type: Type) {
        type.stored("  ") shouldBe null
    }

    enum class Type {
        MANGA {
            override fun stored(sent: String?) = Chapter.create().copyFromSChapter(
                SChapter.create().apply {
                    url = "/c"
                    name = "c"
                    scanlator = sent
                },
            ).scanlator
        },
        NOVEL {
            override fun stored(sent: String?) =
                ChapterItem(name = "c", path = "/c", scanlator = sent).toNovelChapter(novelId = 1L).scanlator
        },
        ;

        abstract fun stored(sent: String?): String?
    }
}
