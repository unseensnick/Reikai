package reikai.domain.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

class ExtensionsInNoticeTest {

    private val manga = listOf("manga")
    private val novels = listOf("novel")

    @Test
    fun `the notice covers both types while both show`() {
        extensionsInNotice(ContentType.ALL, manga, novels) shouldBe listOf("manga", "novel")
    }

    @Test
    fun `hiding novels takes them off the notice`() {
        extensionsInNotice(ContentType.MANGA, manga, novels) shouldBe listOf("manga")
    }

    @Test
    fun `hiding manga takes it off the notice`() {
        extensionsInNotice(ContentType.NOVELS, manga, novels) shouldBe listOf("novel")
    }
}
