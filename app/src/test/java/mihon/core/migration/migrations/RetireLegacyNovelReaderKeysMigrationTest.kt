package mihon.core.migration.migrations

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.domain.novel.DEAD_READER_TTS_BUTTON_KEYS
import reikai.domain.novel.DEAD_READER_TTS_ENABLED_KEY
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

class RetireLegacyNovelReaderKeysMigrationTest {

    private val store = EmittingPreferenceStore()
    private val migration = RetireLegacyNovelReaderKeysMigration(store)

    private fun storedKeys() = listOf(
        store.getBoolean(DEAD_READER_TTS_ENABLED_KEY, false).isSet(),
    ) + DEAD_READER_TTS_BUTTON_KEYS.map { store.getInt(it, Int.MIN_VALUE).isSet() }

    private fun storeAll() {
        store.getBoolean(DEAD_READER_TTS_ENABLED_KEY, false).set(true)
        DEAD_READER_TTS_BUTTON_KEYS.forEach { store.getInt(it, Int.MIN_VALUE).set(120) }
    }

    @Test
    @DisplayName("an upgrade deletes the read-aloud switch and both control position keys")
    fun upgradeDeletesKeys() = runTest {
        storeAll()

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 192))

        storedKeys() shouldBe listOf(false, false, false)
    }

    @Test
    @DisplayName("the read-aloud button migration still sees the switch, since it runs first")
    fun runsAfterReadAloudButtonMigration() = runTest {
        (migration.version > AddReadAloudBottomButtonMigration(store, NovelPreferences(store)).version) shouldBe true
    }
}
