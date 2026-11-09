package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MergeMemberNameTest {

    @Test
    fun `a local series is named by its own title`() {
        mergeMemberName("Local source", "Volume Comic", isLocal = true) shouldBe "Volume Comic"
    }

    @Test
    fun `an online member keeps its source's name`() {
        mergeMemberName("Some Source", "Volume Comic", isLocal = false) shouldBe "Some Source"
    }

    @Test
    fun `a local series without a title falls back to the source name`() {
        mergeMemberName("Local source", "", isLocal = true) shouldBe "Local source"
    }
}
