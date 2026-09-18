package mihon.core.migration.migrations

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.Test
import reikai.domain.novel.DEAD_READER_TAP_TO_SCROLL_KEY
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelTapLayout
import reikai.presentation.recents.EmittingPreferenceStore

class NovelTapLayoutMigrationTest {

    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)
    private val migration = NovelTapLayoutMigration(store, novelPreferences)

    @Test
    fun `tap to scroll switched on becomes the top and bottom layout`() = runTest {
        store.getBoolean(DEAD_READER_TAP_TO_SCROLL_KEY, false).set(true)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 193))

        novelPreferences.readerTapLayout().get() shouldBe NovelTapLayout.THIRDS
    }

    /** Off toggled the chrome wherever the page was tapped, which is what Disabled does. */
    @Test
    fun `tap to scroll switched off becomes the disabled layout`() = runTest {
        store.getBoolean(DEAD_READER_TAP_TO_SCROLL_KEY, false).set(false)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 193))

        novelPreferences.readerTapLayout().get() shouldBe NovelTapLayout.DISABLED
    }

    /** Disabled is also the default, so only the stored value shows the off choice was carried. */
    @Test
    fun `tap to scroll switched off is stored rather than left to the default`() = runTest {
        store.getBoolean(DEAD_READER_TAP_TO_SCROLL_KEY, false).set(false)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 193))

        novelPreferences.readerTapLayout().isSet() shouldBe true
    }

    @Test
    fun `the retired switch is cleared once it has been carried over`() = runTest {
        store.getBoolean(DEAD_READER_TAP_TO_SCROLL_KEY, false).set(true)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 193))

        store.getBoolean(DEAD_READER_TAP_TO_SCROLL_KEY, false).isSet() shouldBe false
    }

    @Test
    fun `an untouched switch leaves the layout at its default`() = runTest {
        migration.invoke(MigrationContext(dryrun = false, previousVersion = 193))

        novelPreferences.readerTapLayout().isSet() shouldBe false
    }
}
