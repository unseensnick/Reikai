package reikai.presentation.settings.download

import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaPreferences
import reikai.domain.novel.NovelPreferences
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.source.service.SourceManager

/** The per-source delays list: which sources it shows under the chip, the search and the Show setting. */
class SourceDelaysViewModelTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val store = EmittingPreferenceStore()
    private val library = ReikaiLibraryPreferences(store)
    private val mangaPreferences = MangaPreferences(store)
    private val novelPreferences = NovelPreferences(store)

    private val weebCentral = mockk<HttpSource> {
        every { id } returns 7L
        every { name } returns "Weeb Central"
        every { lang } returns "en"
    }
    private val fenrir = mockk<NovelSource> {
        every { id } returns "fenrir"
        every { name } returns "Fenrir Realm"
        every { lang } returns "en"
        every { minimumRequestDelayMs } returns 0L
    }

    private val sourceManager = mockk<SourceManager> { every { sources } returns MutableStateFlow(listOf(weebCentral)) }
    private val novelSources = MutableStateFlow(listOf(fenrir))
    private val novelSourceManager = mockk<NovelSourceManager> { every { loadedSources() } returns novelSources }

    private fun TestScope.model(): SourceDelaysViewModel {
        val model = main.track(
            SourceDelaysViewModel(
                sourceManager = sourceManager,
                novelSourceManager = novelSourceManager,
                reikaiLibraryPreferences = library,
                mangaPreferences = mangaPreferences,
                novelPreferences = novelPreferences,
            ),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        return model
    }

    private suspend fun SourceDelaysViewModel.names(): List<String> {
        val sections = state.first { it.sections != null }.sections!!
        return (sections.own + sections.manga + sections.novels).map { it.name }
    }

    @Test
    fun `both types are listed while both show`() = runTest {
        model().names() shouldBe listOf("Weeb Central", "Fenrir Realm")
    }

    @Test
    fun `the chip narrows the list to one type`() = runTest {
        val model = model()

        model.setContentType(ContentType.NOVELS)

        model.names() shouldBe listOf("Fenrir Realm")
    }

    /** The chip row hides itself then, so a chip left on Novels must not empty the list. */
    @Test
    fun `hiding a type while the screen is open follows the Show setting, not the hidden chip`() = runTest {
        val model = model()
        model.setContentType(ContentType.NOVELS)

        library.shownContent.set(ContentType.MANGA)

        model.names() shouldBe listOf("Weeb Central")
    }

    @Test
    fun `showing both again brings back the chip last picked`() = runTest {
        val model = model()
        model.setContentType(ContentType.NOVELS)
        library.shownContent.set(ContentType.MANGA)

        library.shownContent.set(ContentType.ALL)

        model.names() shouldBe listOf("Fenrir Realm")
    }

    @Test
    fun `the search keeps the sources whose name matches`() = runTest {
        val model = model()

        model.setQuery("weeb")

        model.names() shouldBe listOf("Weeb Central")
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a source with its own delay is listed first`(kind: Kind) = runTest {
        kind.delays().set(setOf("${kind.id}=3000"))
        val model = model()

        model.sections().own.map { it.name } shouldBe listOf(kind.sourceName)
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a source's delay is saved under its type, keyed by its id`(kind: Kind) = runTest {
        val model = model()

        model.setDelay(kind.rowIn(model.sections()), 5_000L)

        kind.delays().get() shouldBe setOf("${kind.id}=5000")
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `choosing Default hands the source back to the global delay`(kind: Kind) = runTest {
        kind.delays().set(setOf("${kind.id}=3000"))
        val model = model()

        model.setDelay(model.sections().own.single(), null)

        kind.delays().get() shouldBe emptySet()
    }

    /** A slider writes on every step, so a row that moved section would jump away mid-drag. */
    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a source given its own delay stays in its section while the slider is held`(kind: Kind) = runTest {
        val model = model()

        model.setDelay(kind.rowIn(model.sections()), 500L)

        model.sectionOf(500L) shouldBe kind.name
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a source handed back to Default stays among the own delays while the slider is held`(kind: Kind) =
        runTest {
            kind.delays().set(setOf("${kind.id}=3000"))
            val model = model()

            model.setDelay(model.sections().own.single(), null)

            model.sectionOf(null) shouldBe "OWN"
        }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a source given its own delay moves to the own delays once the slider is let go`(kind: Kind) = runTest {
        val model = model()
        val row = kind.rowIn(model.sections())

        model.setDelay(row, 500L)
        model.settle(row)

        model.sectionOf(kind, 500L) shouldBe "OWN"
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a source handed back to Default returns to its type once the slider is let go`(kind: Kind) = runTest {
        kind.delays().set(setOf("${kind.id}=3000"))
        val model = model()
        val row = model.sections().own.single()

        model.setDelay(row, null)
        model.settle(row)

        model.sectionOf(kind, null) shouldBe kind.name
    }

    /** It reads files from the device, so it has nothing to pace, as manga's local source is not listed. */
    @Test
    fun `the local novel source is not listed`() = runTest {
        novelSources.value = listOf(
            fenrir,
            mockk {
                every { id } returns LocalNovelSource.ID
                every { name } returns "Local source"
                every { lang } returns "other"
                every { minimumRequestDelayMs } returns 0L
            },
        )

        model().names() shouldBe listOf("Weeb Central", "Fenrir Realm")
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a reset source returns to its type at once`(kind: Kind) = runTest {
        kind.delays().set(setOf("${kind.id}=3000"))
        val model = model()

        model.resetDelays(model.sections().own)

        model.sectionOf(kind, null) shouldBe kind.name
    }

    @Test
    fun `resetting all of the own delays hands both types back to their global delays`() = runTest {
        mangaPreferences.downloadSourceDelays().set(setOf("7=3000"))
        novelPreferences.downloadSourceDelays().set(setOf("fenrir=2000"))
        val model = model()

        model.resetDelays(model.sections().own)

        mangaPreferences.downloadSourceDelays().get() + novelPreferences.downloadSourceDelays().get() shouldBe
            emptySet()
    }

    /** The Download speed summary's count, which must match the list it opens. */
    @Test
    fun `a delay kept for a source no longer installed is not counted`() = runTest {
        mangaPreferences.downloadSourceDelays().set(setOf("7=3000", "99=3000"))
        novelPreferences.downloadSourceDelays().set(setOf("gone=3000"))

        rows().ownDelayCount(ContentType.ALL) shouldBe 1
    }

    @Test
    fun `a hidden type's delays are not counted`() = runTest {
        mangaPreferences.downloadSourceDelays().set(setOf("7=3000"))
        novelPreferences.downloadSourceDelays().set(setOf("fenrir=3000"))

        rows().ownDelayCount(ContentType.NOVELS) shouldBe 1
    }

    private suspend fun rows() =
        sourceDelayRows(sourceManager, novelSourceManager, mangaPreferences, novelPreferences).first()

    enum class Kind(val id: String, val sourceName: String) {
        MANGA("7", "Weeb Central"),
        NOVELS("fenrir", "Fenrir Realm"),
    }

    private fun Kind.delays() = when (this) {
        Kind.MANGA -> mangaPreferences.downloadSourceDelays()
        Kind.NOVELS -> novelPreferences.downloadSourceDelays()
    }

    private fun Kind.rowIn(sections: SourceDelaySections) = when (this) {
        Kind.MANGA -> sections.manga.single()
        Kind.NOVELS -> sections.novels.single()
    }

    private suspend fun SourceDelaysViewModel.sections() = state.first { it.sections != null }.sections!!

    /** The section [kind]'s source sits in, once its delay reads [delayMs]. */
    private suspend fun SourceDelaysViewModel.sectionOf(kind: Kind, delayMs: Long?): String {
        fun SourceDelaySections.named() = listOf("OWN" to own, "MANGA" to manga, "NOVELS" to novels)
            .firstOrNull { (_, rows) -> rows.any { it.name == kind.sourceName && it.delayMs == delayMs } }
        return state.first { it.sections?.named() != null }.sections!!.named()!!.first
    }

    /** The section of the one source in [Kind], once its delay reads [delayMs]. */
    private suspend fun SourceDelaysViewModel.sectionOf(delayMs: Long?): String {
        val sections = state.first { state ->
            state.sections?.let { it.own + it.manga + it.novels }.orEmpty().any { it.delayMs == delayMs }
        }.sections!!
        return listOf("OWN" to sections.own, "MANGA" to sections.manga, "NOVELS" to sections.novels)
            .first { (_, rows) -> rows.any { it.delayMs == delayMs } }.first
    }
}
