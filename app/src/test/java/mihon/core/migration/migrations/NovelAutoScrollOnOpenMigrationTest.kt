package mihon.core.migration.migrations

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.Test
import reikai.domain.novel.DEAD_READER_AUTO_SCROLL_KEY
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

class NovelAutoScrollOnOpenMigrationTest {

    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)
    private val migration = NovelAutoScrollOnOpenMigration(store, novelPreferences)

    @Test
    fun `a switch left on becomes start on open`() = runTest {
        store.getBoolean(DEAD_READER_AUTO_SCROLL_KEY, false).set(true)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 196))

        novelPreferences.readerAutoScrollOnOpen().get() shouldBe true
    }

    @Test
    fun `the retired switch is cleared once it has been carried over`() = runTest {
        store.getBoolean(DEAD_READER_AUTO_SCROLL_KEY, false).set(true)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 196))

        store.getBoolean(DEAD_READER_AUTO_SCROLL_KEY, false).isSet() shouldBe false
    }

    @Test
    fun `an untouched switch leaves start on open at its default`() = runTest {
        migration.invoke(MigrationContext(dryrun = false, previousVersion = 196))

        novelPreferences.readerAutoScrollOnOpen().isSet() shouldBe false
    }
}
