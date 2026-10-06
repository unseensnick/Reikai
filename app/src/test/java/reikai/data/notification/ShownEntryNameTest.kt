package reikai.data.notification

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ShownEntryNameTest {

    @Test
    fun `a notification names the series while content is shown`() {
        shownEntryName("A Novel", hideAll = false, hideAdult = false, isAdult = true) shouldBe "A Novel"
    }

    @Test
    fun `a notification names nothing while content is hidden`() {
        shownEntryName("A Novel", hideAll = true, hideAdult = false, isAdult = false) shouldBe null
    }

    @Test
    fun `a notification names no adult series while adult content is hidden`() {
        shownEntryName("A Novel", hideAll = false, hideAdult = true, isAdult = true) shouldBe null
    }

    @Test
    fun `a notification still names a series that is not adult while adult content is hidden`() {
        shownEntryName("A Novel", hideAll = false, hideAdult = true, isAdult = false) shouldBe "A Novel"
    }

    @Test
    fun `a batch hides only its adult entries while adult content is hidden`() = runTest {
        hiddenEntryIds(listOf(1L, 2L, 3L), hideAll = false, hideAdult = true, id = { it }) { setOf(2L) } shouldBe
            setOf(2L)
    }

    @Test
    fun `a batch hides every entry while content is hidden, without asking which are adult`() = runTest {
        hiddenEntryIds(listOf(1L, 2L), hideAll = true, hideAdult = true, id = { it }) { error("asked") } shouldBe
            setOf(1L, 2L)
    }

    @Test
    fun `a batch hides nothing while both switches are off`() = runTest {
        hiddenEntryIds(listOf(1L, 2L), hideAll = false, hideAdult = false, id = { it }) { error("asked") } shouldBe
            emptySet()
    }

    @Test
    fun `a download error does not ask which entries are adult while adult content is shown`() = runTest {
        isHiddenAdult(1L, hideAdult = false, id = { it }) { error("asked") } shouldBe false
    }

    @Test
    fun `a download error hides an adult entry while adult content is hidden`() = runTest {
        isHiddenAdult(1L, hideAdult = true, id = { it }) { setOf(1L) } shouldBe true
    }

    @Test
    fun `a download error still shows an entry that is not adult while adult content is hidden`() = runTest {
        isHiddenAdult(1L, hideAdult = true, id = { it }) { emptySet() } shouldBe false
    }

    @Test
    fun `a download error with no entry does not ask which entries are adult`() = runTest {
        isHiddenAdult<Long>(null, hideAdult = true, id = { it }) { error("asked") } shouldBe false
    }
}
