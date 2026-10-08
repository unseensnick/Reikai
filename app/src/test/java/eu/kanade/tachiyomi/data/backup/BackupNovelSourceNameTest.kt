package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.novelSourceName
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The pre-restore warning and the restore's progress both name a novel source this way. */
class BackupNovelSourceNameTest {

    private val recorded = mapOf("named" to "Novel Site", "blank" to "")

    @Test
    fun `a source is named as the backup recorded it, or by its id where it recorded none`() {
        listOf("named", "blank", "unlisted").map(recorded::novelSourceName) shouldBe
            listOf("Novel Site", "blank", "unlisted")
    }
}
