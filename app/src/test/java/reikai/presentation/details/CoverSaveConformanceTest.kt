package reikai.presentation.details

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.ClearCustomCover
import reikai.domain.entry.CoverColors
import reikai.domain.entry.EntryId
import reikai.presentation.components.savePicture
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** The cover viewer's Save, Share and custom-cover writes over both types' models. */
class CoverSaveConformanceTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(PICTURE_FILES, LOCALIZE)
        coEvery { savePicture(any(), any(), any(), any(), any(), any()) } returns null
        every { any<Context>().stringResource(MR.strings.error_saving_cover) } returns "error"
        every { any<Context>().stringResource(MR.strings.cover_saved) } returns "saved"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
        CoverColors.clear()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a cover that saved nothing says the save failed`(type: Type) = runTest {
        val viewer = type.probe(CoroutineScope(UnconfinedTestDispatcher(testScheduler))).model(inLibrary = true)
        loaded(viewer)

        viewer.saveCover(mockk(relaxed = true), cover = "model")
        runCurrent()

        viewer.snackbarHostState.currentSnackbarData?.visuals?.message shouldBe "error"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a cover that saved says so`(type: Type) = runTest {
        coEvery { savePicture(any(), any(), any(), any(), any(), any()) } returns mockk<Uri>()
        val viewer = type.probe(CoroutineScope(UnconfinedTestDispatcher(testScheduler))).model(inLibrary = true)
        loaded(viewer)

        viewer.saveCover(mockk(relaxed = true), cover = "model")
        runCurrent()

        viewer.snackbarHostState.currentSnackbarData?.visuals?.message shouldBe "saved"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a cover that shared nothing says the share failed`(type: Type) = runTest {
        every { any<Context>().stringResource(MR.strings.error_sharing_cover) } returns "share error"
        val viewer = type.probe(CoroutineScope(UnconfinedTestDispatcher(testScheduler))).model(inLibrary = true)
        loaded(viewer)

        viewer.shareCover(mockk(relaxed = true), cover = "model")
        runCurrent()

        viewer.snackbarHostState.currentSnackbarData?.visuals?.message shouldBe "share error"
    }

    /** A custom cover's removal is a write the closed viewer must not cut off, as Save is. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a custom cover deleted as the viewer closes is still deleted`(type: Type) = runTest {
        val written = CompletableDeferred<Unit>()
        val cleared = mutableListOf<EntryId>()
        val clear = mockk<ClearCustomCover> {
            coEvery { await(any()) } coAnswers {
                written.await()
                cleared += firstArg<EntryId>()
            }
        }
        val probe = type.probe(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), clear)
        val viewer = probe.model(inLibrary = true)
        loaded(viewer)

        viewer.deleteCustomCover(mockk(relaxed = true))
        ViewModelStore().apply { put("cover", viewer) }.clear()
        written.complete(Unit)
        runCurrent()

        cleared shouldBe listOf(probe.id)
    }

    enum class Type(private val make: (CoroutineScope, ClearCustomCover) -> CoverEditProbe) {
        MANGA(::MangaCoverEditProbe),
        NOVEL(::NovelCoverEditProbe),
        ;

        fun probe(appScope: CoroutineScope, clear: ClearCustomCover = mockk(relaxed = true)) = make(appScope, clear)
    }

    private fun TestScope.loaded(model: EntryCoverViewModel<*>) {
        backgroundScope.launch { model.entry.collect {} }
        runCurrent()
    }

    private companion object {
        const val PICTURE_FILES = "reikai.presentation.components.PictureFilesKt"
        const val LOCALIZE = "tachiyomi.core.common.i18n.LocalizeKt"
    }
}
