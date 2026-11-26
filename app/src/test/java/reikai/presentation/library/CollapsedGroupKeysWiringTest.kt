package reikai.presentation.library

import androidx.test.core.app.ApplicationProvider
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.collections.shouldContain
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import mihon.core.migration.migrations.CollapsedGroupKeysMigration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.backup.AppPreferenceCarry
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/** The upgrade and a restore of an older backup both give a collapse key saved under a label its neutral key. */
@RunWith(RobolectricTestRunner::class)
class CollapsedGroupKeysWiringTest {

    private val store = InMemoryPreferenceStore()
    private val libraryPreferences = ReikaiLibraryPreferences(store)
    private val collapsed = libraryPreferences.collapsedDynamicCategories
    private val neutralUnknown = normalizeDynamicKey(labelledGroupName(DynamicGroupKeys.UNKNOWN, ""))

    @Test
    fun `the upgrade gives a saved label key its neutral key`() = runTest {
        collapsed.set(setOf("unknown"))

        CollapsedGroupKeysMigration(ApplicationProvider.getApplicationContext(), libraryPreferences)
            .invoke(MigrationContext(dryrun = false, previousVersion = 210))

        collapsed.get() shouldContain neutralUnknown
    }

    @Test
    fun `a restore of an older backup gives its label keys their neutral keys`() = runTest {
        val carry = AppPreferenceCarry(
            NovelPreferences(store),
            SourcePreferences(store),
            NetworkPreferences(store, isDebugBuild = false),
            TrackPreferences(store),
            mockk(relaxed = true),
            SecurityPreferences(store),
            PrivacyPreferences(store),
            mockk(relaxed = true),
            ApplicationProvider.getApplicationContext(),
            libraryPreferences,
        )

        carry.restore(listOf(BackupPreference(collapsed.key(), StringSetPreferenceValue(setOf("unknown"))))) {}

        collapsed.get() shouldContain neutralUnknown
    }
}
