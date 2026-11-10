package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences

class BackedUpNovelSourcesTest {

    @Test
    fun `a source's name comes back with the site its App settings kept`() {
        val seen = BackupPreference(
            NovelPreferences.SEEN_NOVEL_SOURCES_KEY,
            StringPreferenceValue("""{"ln:x":{"name":"X","site":"https://x.example"}}"""),
        )

        backedUpNovelSources(mapOf("ln:x" to "X"), listOf(seen)).getValue("ln:x").site shouldBe "https://x.example"
    }
}
