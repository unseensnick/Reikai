package mihon.core.migration.migrations

import eu.kanade.tachiyomi.BuildConfig
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.Migrator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/** Every earlier Reikai build named chapter files with the hash, so upgrading to this one keeps it on. */
class ChapterNameSuffixMigrationTest {

    private val libraryPreferences = LibraryPreferences(EmittingPreferenceStore())

    @AfterEach
    fun releaseMigrator() {
        Migrator.release()
    }

    @ParameterizedTest(name = "upgrading from {0}")
    @ValueSource(ints = [185, 198])
    fun `an upgrade keeps the hash suffix`(previousVersion: Int) = runTest {
        Migrator.initialize(
            old = previousVersion,
            new = BuildConfig.VERSION_CODE,
            migrations = listOf(ChapterNameSuffixMigration(libraryPreferences)),
            onMigrationComplete = {},
        )

        Migrator.await()

        libraryPreferences.enableChapterNameHash.get() shouldBe true
    }
}
