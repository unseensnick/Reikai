package eu.kanade.tachiyomi.data.backup

import android.content.SharedPreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.configurableSources
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.FLARESOLVERR_URL_KEY
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.backup.AppPreferenceCarry
import reikai.domain.novel.DEAD_READER_AUTO_SCROLL_KEY
import reikai.domain.novel.DEAD_READER_PADDING_KEY
import reikai.domain.novel.DEAD_READER_TAP_TO_SCROLL_KEY
import reikai.domain.novel.DEAD_READER_TTS_ENABLED_KEY
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.content.NovelCodeSnippet
import reikai.novel.content.NovelSnippets
import reikai.presentation.recents.EmittingPreferenceStore
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
        signatures = emptyList(),
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
            getNovelCategories = mockk(),
            appPreferenceCarry = mockk(),
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

    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)

    private val restorer = PreferenceRestorer(
        context = mockk(),
        getCategories = mockk(),
        preferenceStore = store,
        categoryIdPreferences = mockk(relaxed = true),
        getNovelCategories = mockk(),
        appPreferenceCarry = AppPreferenceCarry(
            novelPreferences,
            SourcePreferences(store),
            NetworkPreferences(store, isDebugBuild = false),
        ),
    )

    /** An extension's settings file, which the restore opens through the source contract. */
    private suspend fun restoreIntoAnExtension(vararg prefs: BackupPreference) {
        mockkStatic("eu.kanade.tachiyomi.source.ConfigurableSourceKt")
        try {
            every { sourcePreferences(any<String>()) } returns mockk<SharedPreferences>(relaxed = true) {
                every { all } returns emptyMap<String, Any>()
            }
            restorer.restoreSource(listOf(BackupSourcePreferences("source_1234", prefs.toList())))
        } finally {
            unmockkStatic("eu.kanade.tachiyomi.source.ConfigurableSourceKt")
        }
    }

    /** Source settings can be restored with App settings off, so an entry must not reach an app setting. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("appKeys")
    fun `an extension's restored settings leave the app's own settings alone`(
        key: String,
        value: PreferenceValue,
        written: (PreferenceStore) -> Boolean,
    ) = runTest {
        restoreIntoAnExtension(BackupPreference(key, value))

        written(store) shouldBe false
    }

    @Test
    fun `an extension's restored settings leave the reader bar alone`() = runTest {
        val bar = setOf(ReaderBottomButton.ViewChapters.value)
        novelPreferences.readerBottomButtons().set(bar)

        restoreIntoAnExtension(BackupPreference(DEAD_READER_TTS_ENABLED_KEY, BooleanPreferenceValue(true)))

        novelPreferences.readerBottomButtons().get() shouldBe bar
    }

    companion object {
        @JvmStatic
        fun appKeys() = listOf(
            Arguments.of(
                FLARESOLVERR_URL_KEY,
                StringPreferenceValue("https://elsewhere.example"),
                { s: PreferenceStore -> NetworkPreferences(s, isDebugBuild = false).flareSolverrUrl.isSet() },
            ),
            Arguments.of(
                NovelPreferences.JS_SNIPPETS_KEY,
                StringPreferenceValue(
                    NovelSnippets.encode(
                        listOf(NovelCodeSnippet(title = "x", code = "alert(1)", enabled = true, id = "a")),
                    ),
                ),
                { s: PreferenceStore -> NovelPreferences(s).readerJsSnippets().isSet() },
            ),
            Arguments.of(
                DEAD_READER_PADDING_KEY,
                IntPreferenceValue(32),
                { s: PreferenceStore -> NovelPreferences(s).readerMarginLeft().isSet() },
            ),
            Arguments.of(
                ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY,
                BooleanPreferenceValue(false),
                { s: PreferenceStore -> SourcePreferences(s).enabledContentWarnings.isSet() },
            ),
            Arguments.of(
                DEAD_READER_TAP_TO_SCROLL_KEY,
                BooleanPreferenceValue(true),
                { s: PreferenceStore -> NovelPreferences(s).readerTapLayout().isSet() },
            ),
            Arguments.of(
                DEAD_READER_AUTO_SCROLL_KEY,
                BooleanPreferenceValue(true),
                { s: PreferenceStore -> NovelPreferences(s).readerAutoScrollOnOpen().isSet() },
            ),
        )
    }
}
