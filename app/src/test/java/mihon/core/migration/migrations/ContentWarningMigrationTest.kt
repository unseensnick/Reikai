package mihon.core.migration.migrations

import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.recents.EmittingPreferenceStore

class ContentWarningMigrationTest {

    private val store = EmittingPreferenceStore()
    private val sourcePreferences = SourcePreferences(store)
    private val migration = ContentWarningMigration(store, sourcePreferences)

    @Test
    fun `hidden NSFW sources leave only safe extensions allowed`() = runTest {
        store.getBoolean(ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY, true).set(false)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 194))

        sourcePreferences.enabledContentWarnings.get() shouldBe setOf(ContentWarning.SAFE)
    }

    @Test
    fun `shown NSFW sources keep every warning allowed`() = runTest {
        store.getBoolean(ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY, true).set(true)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 194))

        sourcePreferences.enabledContentWarnings.get() shouldBe ContentWarning.entries.toSet()
    }

    @Test
    fun `the retired switch is cleared once it has been carried over`() = runTest {
        store.getBoolean(ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY, true).set(false)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 194))

        store.getBoolean(ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY, true).isSet() shouldBe false
    }
}
