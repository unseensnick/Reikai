package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.backedUpSourceName
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The restore's progress and error log name a backed-up source of either type this way. */
class BackupNovelSourceNameTest {

    @Test
    fun `a novel source is named as the backup recorded it, or by its id where it recorded none`() {
        val recorded = mapOf("named" to "Novel Site", "blank" to "")

        listOf("named", "blank", "unlisted").map(recorded::backedUpSourceName) shouldBe
            listOf("Novel Site", "blank", "unlisted")
    }

    // A manga source never stubbed is backed up with a blank name, which would leave the log line empty.
    @Test
    fun `a manga source backed up with a blank name is named by its id`() {
        val recorded = mapOf(1L to "Manga Site", 2L to "")

        listOf(1L, 2L, 3L).map(recorded::backedUpSourceName) shouldBe listOf("Manga Site", "2", "3")
    }
}
