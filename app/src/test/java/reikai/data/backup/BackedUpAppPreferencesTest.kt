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
 * so a backup with App settings off still lets a restore offer them. App settings also carry the
 * marker saying the migration options know the custom info option.
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

        // Named one by one rather than through pluginInstallKeys, so a key that falls out of it is caught.
        private val plugins = listOf(
            prefs.installedPluginUrls().key(),
            prefs.installedPluginMetadata().key(),
            prefs.addedRepoUrls().key(),
        )
        private val seen = prefs.seenNovelSources().key()
        private const val FONT_SIZE = "font_size"
        private val all = (plugins + seen + FONT_SIZE).map { BackupPreference(it, IntPreferenceValue(1)) }

        @JvmStatic
        fun cases() = listOf(
            Arguments.of(
                "extension stores only",
                BackupOptions(appSettings = false, extensionStores = true),
                plugins + seen,
            ),
            Arguments.of(
                "app settings only",
                BackupOptions(appSettings = true, extensionStores = false),
                listOf(seen, FONT_SIZE, AppPreferenceCarry.CUSTOM_INFO_OPTION_MARKER_KEY),
            ),
            Arguments.of(
                "both",
                BackupOptions(),
                plugins + seen + FONT_SIZE + AppPreferenceCarry.CUSTOM_INFO_OPTION_MARKER_KEY,
            ),
        )
    }
}
