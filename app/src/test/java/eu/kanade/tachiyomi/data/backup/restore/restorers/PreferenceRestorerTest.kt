package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.FLARESOLVERR_URL_KEY
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.novel.DEAD_READER_PADDING_KEY
import reikai.domain.novel.DEAD_READER_TAP_TO_SCROLL_KEY
import reikai.domain.novel.DEAD_READER_TTS_BUTTON_KEYS
import reikai.domain.novel.DEAD_READER_TTS_ENABLED_KEY
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelTapLayout
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.content.NovelCodeSnippet
import reikai.novel.content.NovelSnippets
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories

/**
 * What a restore does with a preference key the app has retired. The store answers null for a key it
 * does not hold, which satisfies the restorer's type check, so a retired key is written back unless
 * the restorer says otherwise: the six Yōkai-era keys it already skips, and the novel reader's page
 * padding, whose value has somewhere to go.
 */
class PreferenceRestorerTest {

    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)
    private val sourcePreferences = SourcePreferences(store)
    private val networkPreferences = NetworkPreferences(store, isDebugBuild = false)
    private val context = mockk<Context>()

    private val restorer = PreferenceRestorer(
        context = context,
        getCategories = mockk<GetCategories>(),
        preferenceStore = store,
        categoryIdPreferences = mockk<CategoryIdPreferences>(relaxed = true),
        novelPreferences = novelPreferences,
        extensionSourcePreferences = sourcePreferences,
        networkPreferences = networkPreferences,
    )

    /** Both are WorkManager scheduling the restore does on its way out, which needs a real app. */
    @BeforeEach
    fun stubTheJobs() {
        mockkObject(LibraryUpdateJob)
        mockkObject(BackupCreateJob)
        every { LibraryUpdateJob.setupTask(any(), any()) } returns Unit
        every { BackupCreateJob.setupTask(any(), any()) } returns Unit
    }

    @AfterEach
    fun releaseTheJobs() {
        unmockkObject(LibraryUpdateJob)
        unmockkObject(BackupCreateJob)
    }

    private fun margins() = with(novelPreferences) {
        listOf(readerMarginTop(), readerMarginBottom(), readerMarginLeft(), readerMarginRight()).map { it.get() }
    }

    private suspend fun restore(key: String, value: Int) =
        restorer.restoreApp(listOf(BackupPreference(key, IntPreferenceValue(value))), backupCategories = null)

    private suspend fun restoreString(key: String, value: String) =
        restorer.restoreApp(listOf(BackupPreference(key, StringPreferenceValue(value))), backupCategories = null)

    @Test
    @DisplayName("a restored bypass address does not keep the password that was buried in it")
    fun restoredAddressIsCleaned() = runTest {
        restoreString(FLARESOLVERR_URL_KEY, "https://user:secret@solverr.example.com")

        networkPreferences.flareSolverrUrl.get() shouldBe "https://solverr.example.com"
    }

    @Test
    @DisplayName("credentials buried in a restored address reach the fields that use them")
    fun restoredAddressCredentialsAreKept() = runTest {
        restoreString(FLARESOLVERR_URL_KEY, "https://user:secret@solverr.example.com")

        networkPreferences.flareSolverrUsername.get() shouldBe "user"
        networkPreferences.flareSolverrPassword.get() shouldBe "secret"
    }

    @Test
    @DisplayName("an ordinary bypass address is restored unchanged")
    fun restoredPlainAddressIsUntouched() = runTest {
        restoreString(FLARESOLVERR_URL_KEY, "http://192.168.1.10:8191")

        networkPreferences.flareSolverrUrl.get() shouldBe "http://192.168.1.10:8191"
        networkPreferences.flareSolverrUsername.get() shouldBe ""
    }

    @Test
    @DisplayName("a backup taken before the margins existed keeps its page padding")
    fun retiredPaddingReachesTheMargins() = runTest {
        restore(DEAD_READER_PADDING_KEY, 32)

        margins() shouldBe listOf(50, 16, 32, 32)
    }

    /**
     * The upgrade migrations have already run by the time a restore lands, so nothing would read these.
     * This store tracks `isSet` by key alone, so one probe type serves every row.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("retiredKeys")
    @DisplayName("a retired key is not written back into the store")
    fun retiredKeyIsNotResurrected(key: String, value: PreferenceValue) = runTest {
        restorer.restoreApp(listOf(BackupPreference(key, value)), backupCategories = null)

        store.getString(key, "").isSet() shouldBe false
    }

    @Test
    @DisplayName("a backup taken with tap to scroll on keeps tapping the top and bottom of the page")
    fun retiredTapToScrollReachesTheTapLayout() = runTest {
        restorer.restoreApp(
            listOf(BackupPreference(DEAD_READER_TAP_TO_SCROLL_KEY, BooleanPreferenceValue(true))),
            backupCategories = null,
        )

        novelPreferences.readerTapLayout().get() shouldBe NovelTapLayout.THIRDS
    }

    /** Disabled is also the default, so only the stored value shows the off choice was carried. */
    @Test
    @DisplayName("a backup taken with tap to scroll off stores the disabled layout")
    fun retiredTapToScrollOffIsStored() = runTest {
        restorer.restoreApp(
            listOf(BackupPreference(DEAD_READER_TAP_TO_SCROLL_KEY, BooleanPreferenceValue(false))),
            backupCategories = null,
        )

        novelPreferences.readerTapLayout().let { it.get() to it.isSet() } shouldBe (NovelTapLayout.DISABLED to true)
    }

    @Test
    @DisplayName("a backup taken with NSFW sources hidden allows only safe extensions")
    fun retiredNsfwSwitchReachesTheContentWarnings() = runTest {
        restorer.restoreApp(
            listOf(BackupPreference(ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY, BooleanPreferenceValue(false))),
            backupCategories = null,
        )

        sourcePreferences.enabledContentWarnings.get() shouldBe setOf(ContentWarning.SAFE)
    }

    @Test
    @DisplayName("a backup taken with NSFW sources shown allows every extension again")
    fun retiredNsfwSwitchOnRestoresEveryContentWarning() = runTest {
        sourcePreferences.enabledContentWarnings.set(setOf(ContentWarning.SAFE))

        restorer.restoreApp(
            listOf(BackupPreference(ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY, BooleanPreferenceValue(true))),
            backupCategories = null,
        )

        sourcePreferences.enabledContentWarnings.get() shouldBe
            setOf(ContentWarning.SAFE, ContentWarning.MIXED, ContentWarning.NSFW)
    }

    /** A shared backup is someone else's code, so none of it runs until the user switches it on. */
    @Test
    @DisplayName("a restored javascript snippet comes back switched off")
    fun restoredJavaScriptIsSwitchedOff() = runTest {
        val snippet = NovelCodeSnippet(title = "x", code = "alert(1)", enabled = true, id = "a")
        restorer.restoreApp(
            listOf(
                BackupPreference(
                    NovelPreferences.JS_SNIPPETS_KEY,
                    StringPreferenceValue(NovelSnippets.encode(listOf(snippet))),
                ),
            ),
            backupCategories = null,
        )

        NovelSnippets.decode(novelPreferences.readerJsSnippets().get()) shouldBe listOf(snippet.copy(enabled = false))
    }

    /** It lets any computer with debugging rights inspect every WebView the app has, so it is the user's to turn on. */
    @Test
    @DisplayName("a restored webview developer tools switch stays off")
    fun restoredDevToolsStayOff() = runTest {
        restorer.restoreApp(
            listOf(BackupPreference(NovelPreferences.WEBVIEW_DEV_TOOLS_KEY, BooleanPreferenceValue(true))),
            backupCategories = null,
        )

        novelPreferences.readerWebViewDevTools().get() shouldBe false
    }

    /** The switch comes before the bar in the backup, so the bar restored after it must still gain the button. */
    @Test
    @DisplayName("a backup with read-aloud on and a customised bar keeps the read-aloud button")
    fun retiredReadAloudSwitchReachesTheBar() = runTest {
        val customised = setOf(ReaderBottomButton.ViewChapters.value)
        restorer.restoreApp(
            listOf(
                BackupPreference(DEAD_READER_TTS_ENABLED_KEY, BooleanPreferenceValue(true)),
                BackupPreference(novelPreferences.readerBottomButtons().key(), StringSetPreferenceValue(customised)),
            ),
            backupCategories = null,
        )

        novelPreferences.readerBottomButtons().get() shouldBe customised + ReaderBottomButton.ReadAloud.value
    }

    @Test
    @DisplayName("a backup with read-aloud off keeps its customised bar as it was")
    fun readAloudOffLeavesTheBar() = runTest {
        val customised = setOf(ReaderBottomButton.ViewChapters.value)
        restorer.restoreApp(
            listOf(
                BackupPreference(DEAD_READER_TTS_ENABLED_KEY, BooleanPreferenceValue(false)),
                BackupPreference(novelPreferences.readerBottomButtons().key(), StringSetPreferenceValue(customised)),
            ),
            backupCategories = null,
        )

        novelPreferences.readerBottomButtons().get() shouldBe customised
    }

    @Test
    @DisplayName("a live preference is still restored")
    fun aLivePreferenceIsRestored() = runTest {
        restore(novelPreferences.readerFontSize().key(), 22)

        novelPreferences.readerFontSize().get() shouldBe 22
    }

    companion object {
        @JvmStatic
        fun retiredKeys() = listOf(
            Arguments.of(DEAD_READER_PADDING_KEY, IntPreferenceValue(32)),
            Arguments.of(DEAD_READER_TAP_TO_SCROLL_KEY, BooleanPreferenceValue(true)),
            Arguments.of(ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY, BooleanPreferenceValue(false)),
            Arguments.of(DEAD_READER_TTS_ENABLED_KEY, BooleanPreferenceValue(true)),
        ) + DEAD_READER_TTS_BUTTON_KEYS.map { Arguments.of(it, IntPreferenceValue(120)) }
    }
}
