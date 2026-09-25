package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.configurableSources
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.PreferenceStore

class SourcePreferencesBackupTest {

    private fun configurable(id: Long) = mockk<ConfigurableSource> { every { this@mockk.id } returns id }

    private fun novelApp(vararg sources: Source) = Extension.Loaded(
        name = "app",
        pkgName = "eu.kanade.tachiyomi.novelextension.app",
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        kind = Extension.Kind.TACHIYOMI_NOVEL,
        pkgFactory = null,
        sources = sources.toList(),
        icon = null,
    )

    @Test
    fun `a novel app's settings go into the backup beside the manga sources'`() {
        configurableSources(listOf(configurable(1)), listOf(novelApp(configurable(2)))).map { it.id } shouldBe
            listOf(1L, 2L)
    }

    @Test
    fun `a settings file a manga and a novel source share is written once`() {
        configurableSources(listOf(configurable(7)), listOf(novelApp(configurable(7)))).map { it.id } shouldBe
            listOf(7L)
    }

    @Test
    fun `a source with no settings is left out`() {
        configurableSources(emptyList(), listOf(novelApp(mockk<Source>()))) shouldBe emptyList()
    }

    private val appStore = mapOf(
        "ln_storage::boxnovel::token" to "t",
        "ireader_storage::org.ireader.app::lang" to "en",
        "app_theme" to "dark",
    )

    private fun creator() = PreferenceBackupCreator(
        sourceManager = mockk { coEvery { getAll() } returns emptyList() },
        preferenceStore = mockk { every { getAll() } returns appStore },
        extensionManager = mockk { every { loadedNovelExtensionsFlow } returns MutableStateFlow(emptyList()) },
    )

    @Test
    fun `App settings leave out the settings plugins and IReader extensions keep`() {
        creator().createApp(includePrivatePreferences = false).map { it.key } shouldBe listOf("app_theme")
    }

    @Test
    fun `Source settings carry each plugin's and IReader extension's own settings`() = runTest {
        creator().createSource(includePrivatePreferences = false).map {
            it.sourceKey to it.prefs.map { p -> p.key }
        } shouldBe
            listOf(
                "ln_storage::boxnovel::" to listOf("ln_storage::boxnovel::token"),
                "ireader_storage::org.ireader.app::" to listOf("ireader_storage::org.ireader.app::lang"),
            )
    }

    @Test
    fun `a plugin's restored settings go back to the app store, and only its own keys`() = runTest {
        val written = mutableListOf<Pair<String, String>>()
        val store = mockk<PreferenceStore> {
            every { getAll() } returns emptyMap<String, Any>()
            every { getString(any(), any()) } answers {
                val key = firstArg<String>()
                mockk { every { set(any()) } answers { written += key to firstArg<String>() } }
            }
        }
        val restorer = PreferenceRestorer(
            context = mockk(),
            getCategories = mockk(),
            preferenceStore = store,
            categoryIdPreferences = mockk(),
            novelPreferences = mockk(),
            extensionSourcePreferences = mockk(),
            networkPreferences = mockk(),
            getNovelCategories = mockk(),
        )

        restorer.restoreSource(
            listOf(
                BackupSourcePreferences(
                    "ln_storage::boxnovel::",
                    listOf(
                        BackupPreference("ln_storage::boxnovel::token", StringPreferenceValue("t")),
                        BackupPreference("app_theme", StringPreferenceValue("light")),
                    ),
                ),
            ),
        )

        written shouldBe listOf("ln_storage::boxnovel::token" to "t")
    }
}
