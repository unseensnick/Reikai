package reikai.data.novel.update

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel

class NovelNotificationPrivacyTest {

    private val adult = Novel.create().copy(id = 1L, title = "Adult", genre = listOf("Adult"))
    private val plain = Novel.create().copy(id = 2L, title = "Plain", genre = listOf("Fantasy"))

    @Test
    fun `an adult-tagged novel goes unnamed while only adult content is hidden`() = runTest {
        hiddenNovelIds(listOf(adult, plain), hideAll = false, hideAdult = true) shouldBe setOf(1L)
    }

    @Test
    fun `every novel goes unnamed while all content is hidden`() = runTest {
        hiddenNovelIds(listOf(adult, plain), hideAll = true, hideAdult = false) shouldBe setOf(1L, 2L)
    }
}
