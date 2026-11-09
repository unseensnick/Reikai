package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * Installed LN plugins and their repos follow Extension stores, as the extension apps (field 710) do,
 * so a backup with App settings off still lets a restore offer them.
 */
class BackedUpAppPreferencesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a backup writes the plugin keys with the extensions, not with app settings`(
        name: String,
        options: BackupOptions,
        written: List<String>,
    ) {
        prefs.backedUpAppPreferences(options, all).map { it.key } shouldBe written
    }

    companion object {
        private val prefs = NovelPreferences(EmittingPreferenceStore())
        private val plugins = prefs.installedPluginUrls().key()
        private val seen = prefs.seenNovelSources().key()
        private const val FONT_SIZE = "font_size"
        private val all = listOf(plugins, seen, FONT_SIZE).map { BackupPreference(it, IntPreferenceValue(1)) }

        @JvmStatic
        fun cases() = listOf(
            Arguments.of(
                "extension stores only",
                BackupOptions(appSettings = false, extensionStores = true),
                listOf(plugins, seen),
            ),
            Arguments.of(
                "app settings only",
                BackupOptions(appSettings = true, extensionStores = false),
                listOf(seen, FONT_SIZE),
            ),
            Arguments.of("both", BackupOptions(), listOf(plugins, seen, FONT_SIZE)),
        )
    }
}
