package mihon.core.migration.migrations

import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.presentation.recents.EmittingPreferenceStore

class MigrationCustomInfoFlagMigrationTest {

    private val store = EmittingPreferenceStore()
    private val sourcePreferences = SourcePreferences(store)
    private val novelPreferences = NovelPreferences(store)
    private val migration = MigrationCustomInfoFlagMigration(sourcePreferences, novelPreferences)

    private suspend fun migrate() = migration.invoke(MigrationContext(dryrun = false, previousVersion = 209))

    @Test
    fun `a stored manga mask gains the custom info option and keeps the rest`() = runTest {
        sourcePreferences.migrationFlags.set(setOf(MigrationFlag.CHAPTER))

        migrate()

        sourcePreferences.migrationFlags.get() shouldBe setOf(MigrationFlag.CHAPTER, MigrationFlag.CUSTOM_INFO)
    }

    @Test
    fun `a stored novel mask gains the custom info option and keeps the rest`() = runTest {
        novelPreferences.novelMigrationFlags().set(NovelMigrationFlag.CHAPTER.bit)

        migrate()

        novelPreferences.novelMigrationFlags().get() shouldBe
            (NovelMigrationFlag.CHAPTER.bit or NovelMigrationFlag.CUSTOM_INFO.bit)
    }

    @Test
    fun `unstored masks stay unstored, since their defaults carry the option`() = runTest {
        migrate()

        listOf(sourcePreferences.migrationFlags.isSet(), novelPreferences.novelMigrationFlags().isSet()) shouldBe
            listOf(false, false)
    }
}
