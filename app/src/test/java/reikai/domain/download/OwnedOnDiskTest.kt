package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OwnedOnDiskTest {

    @Test
    fun `a chapter whose owner is missing is not on disk`() {
        // Chapter id to owner id; owner 2 is no longer stored, though its folder would answer.
        val chapters = listOf(10L to 1L, 20L to 2L)

        ownedOnDisk(
            chapters,
            ownerKey = { it.second },
            ownerOf = { it.second.takeIf { owner -> owner == 1L } },
            isLocal = { false },
            id = { it.first },
        ) { _, owned -> owned.map { it.first } } shouldBe setOf(10L)
    }
}
