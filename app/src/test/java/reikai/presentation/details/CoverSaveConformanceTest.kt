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
import org.junit.jupiter.params.provider.ValueSource
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
    @ValueSource(strings = ["manga", "novel"])
    fun `a cover that saved nothing says the save failed`(type: String) = runTest {
        val appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val model = if (type == "manga") MangaCoverEditProbe(appScope) else NovelCoverEditProbe(appScope)
        val viewer = model.model(inLibrary = true)
        loaded(viewer)

        viewer.saveCover(mockk(relaxed = true), cover = "model")
        runCurrent()

        viewer.snackbarHostState.currentSnackbarData?.visuals?.message shouldBe "error"
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "novel"])
    fun `a cover that saved says so`(type: String) = runTest {
        coEvery { savePicture(any(), any(), any(), any(), any(), any()) } returns mockk<Uri>()
        val viewer = probe(type, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).model(inLibrary = true)
        loaded(viewer)

        viewer.saveCover(mockk(relaxed = true), cover = "model")
        runCurrent()

        viewer.snackbarHostState.currentSnackbarData?.visuals?.message shouldBe "saved"
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "novel"])
    fun `a cover that shared nothing says the share failed`(type: String) = runTest {
        every { any<Context>().stringResource(MR.strings.error_sharing_cover) } returns "share error"
        val viewer = probe(type, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).model(inLibrary = true)
        loaded(viewer)

        viewer.shareCover(mockk(relaxed = true), cover = "model")
        runCurrent()

        viewer.snackbarHostState.currentSnackbarData?.visuals?.message shouldBe "share error"
    }

    /** A custom cover's removal is a write the closed viewer must not cut off, as Save is. */
    @ParameterizedTest
    @ValueSource(strings = ["manga", "novel"])
    fun `a custom cover deleted as the viewer closes is still deleted`(type: String) = runTest {
        val written = CompletableDeferred<Unit>()
        val cleared = mutableListOf<EntryId>()
        val clear = mockk<ClearCustomCover> {
            coEvery { await(any()) } coAnswers {
                written.await()
                cleared += firstArg<EntryId>()
            }
        }
        val probe = probe(type, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), clear)
        val viewer = probe.model(inLibrary = true)
        loaded(viewer)

        viewer.deleteCustomCover(mockk(relaxed = true))
        ViewModelStore().apply { put("cover", viewer) }.clear()
        written.complete(Unit)
        runCurrent()

        cleared shouldBe listOf(probe.id)
    }

    private fun probe(
        type: String,
        appScope: CoroutineScope,
        clear: ClearCustomCover = mockk(relaxed = true),
    ): CoverEditProbe = if (type ==
        "manga"
    ) {
        MangaCoverEditProbe(appScope, clear)
    } else {
        NovelCoverEditProbe(appScope, clear)
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
