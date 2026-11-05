package reikai.data.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LocalImportGroupKeyTest {

    @Test
    fun `series that differ only in case and punctuation are one series`() {
        listOf("The Saga: Book", "the saga book", "THE SAGA - BOOK").map(LocalImport::groupKey).distinct().size shouldBe
            1
    }
}
