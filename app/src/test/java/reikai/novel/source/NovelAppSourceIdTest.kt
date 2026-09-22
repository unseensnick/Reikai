package reikai.novel.source

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelAppSourceIdTest {

    @Test
    fun `a tachiyomi-format app's source is named by its number under its prefix`() {
        Extension.Kind.TACHIYOMI_NOVEL.novelSourceId(7) shouldBe "tachiyomi:7"
    }

    @Test
    fun `an IReader app's source with the same number gets an id of its own`() {
        Extension.Kind.IREADER.novelSourceId(7) shouldBe "ireader:7"
    }

    @Test
    fun `a manga app's source keeps its bare number`() {
        Extension.Kind.MANGA.novelSourceId(7) shouldBe null
    }

    @Test
    fun `an IReader app is labelled IReader`() {
        Extension.Kind.IREADER.novelFormat shouldBe NovelExtensionFormat.IREADER
    }

    @Test
    fun `an IReader id names an installed app`() {
        isNovelAppSourceId("ireader:7") shouldBe true
    }

    @Test
    fun `a plugin id names no app`() {
        isNovelAppSourceId("novelbin") shouldBe false
    }
}
