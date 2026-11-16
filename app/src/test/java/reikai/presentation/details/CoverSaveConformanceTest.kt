package reikai.presentation.details

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
import reikai.presentation.components.savePicture
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR

/** The cover viewer's Save over both types' models, when the cover loads as no picture and nothing is written. */
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
        MangaCover.vibrantCoverColorMap.clear()
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

    private fun TestScope.loaded(model: EntryCoverViewModel<*>) {
        backgroundScope.launch { model.entry.collect {} }
        runCurrent()
    }

    private companion object {
        const val PICTURE_FILES = "reikai.presentation.components.PictureFilesKt"
        const val LOCALIZE = "tachiyomi.core.common.i18n.LocalizeKt"
    }
}
