package mihon.core.migration.migrations

import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

class AddReadAloudBottomButtonMigrationTest {

    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)
    private val migration = AddReadAloudBottomButtonMigration(novelPreferences)

    private val customised = setOf(ReaderBottomButton.ViewChapters.value, ReaderBottomButton.Autoscroll.value)
    private val readAloud = ReaderBottomButton.ReadAloud.value

    @Test
    @DisplayName("a customised bar gains the button when read-aloud was on")
    fun customisedBarGainsButton() = runTest {
        novelPreferences.readerTtsEnabled().set(true)
        novelPreferences.readerBottomButtons().set(customised)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 191))

        novelPreferences.readerBottomButtons().get() shouldBe customised + readAloud
    }

    @Test
    @DisplayName("a customised bar is left alone when read-aloud was never on")
    fun readAloudOffLeavesBar() = runTest {
        novelPreferences.readerBottomButtons().set(customised)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 191))

        novelPreferences.readerBottomButtons().get() shouldBe customised
    }

    @Test
    @DisplayName("an untouched bar stays unstored, since its defaults already carry the button")
    fun untouchedBarStaysUnset() = runTest {
        novelPreferences.readerTtsEnabled().set(true)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 191))

        novelPreferences.readerBottomButtons().isSet() shouldBe false
    }

    @Test
    @DisplayName("a fresh install writes nothing")
    fun freshInstallDoesNothing() = runTest {
        novelPreferences.readerTtsEnabled().set(true)
        novelPreferences.readerBottomButtons().set(customised)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 0))

        novelPreferences.readerBottomButtons().get() shouldBe customised
    }
}
