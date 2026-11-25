package reikai.presentation.widget

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.library.ContentType
import reikai.domain.novel.model.NovelCover
import reikai.domain.novel.model.NovelUpdateWithRelations
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import kotlin.time.Instant

/** The combined widget's refresh driver watches the library only while a combined widget is placed. */
internal class UnifiedWidgetRefreshesTest {

    private val placed = MutableStateFlow(false)
    private var watching = 0
    private val updates = flow {
        emit(UnifiedWidgetUpdates(manga = emptyList(), novel = emptyList(), shown = ContentType.ALL))
        awaitCancellation()
    }
        .onStart { watching++ }
        .onCompletion { watching-- }

    private fun TestScope.collectRefreshes() {
        backgroundScope.launch { unifiedWidgetRefreshes(placed, updates, flowOf(false)).collect() }
        runCurrent()
    }

    @Test
    fun `nothing is watched while no widget is placed`() = runTest {
        collectRefreshes()

        watching shouldBe 0
    }

    @Test
    fun `placing a widget starts the watch`() = runTest {
        collectRefreshes()

        placed.value = true
        runCurrent()

        watching shouldBe 1
    }

    /** A Glance session closes soon after it draws, and only the driver redraws a widget after that. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("faceChanges")
    fun `a change to what the widget draws redraws it`(
        case: String,
        change: (UnifiedWidgetUpdates) -> UnifiedWidgetUpdates,
        lock: Boolean,
    ) = runTest {
        val face = MutableStateFlow(UnifiedWidgetUpdates(listOf(manga()), listOf(novel()), ContentType.ALL))
        val locked = MutableStateFlow(false)
        val drawn = mutableListOf<UnifiedWidgetDrawn>()
        backgroundScope.launch { unifiedWidgetRefreshes(flowOf(true), face, locked).toList(drawn) }
        runCurrent()

        face.value = change(face.value)
        locked.value = lock
        runCurrent()

        drawn shouldHaveSize 2
    }

    @Test
    fun `a change the widget does not draw leaves it alone`() = runTest {
        val face = MutableStateFlow(UnifiedWidgetUpdates(listOf(manga()), emptyList(), ContentType.ALL))
        val drawn = mutableListOf<UnifiedWidgetDrawn>()
        backgroundScope.launch { unifiedWidgetRefreshes(flowOf(true), face, flowOf(false)).toList(drawn) }
        runCurrent()

        face.value = face.value.copy(manga = listOf(manga().copy(lastPageRead = 4)))
        runCurrent()

        drawn shouldHaveSize 1
    }

    @Test
    fun `removing the last widget stops the watch`() = runTest {
        placed.value = true
        collectRefreshes()

        placed.value = false
        runCurrent()

        watching shouldBe 0
    }

    companion object {
        private fun manga(url: String? = null) = UpdatesWithRelations(
            mangaId = 1L,
            mangaTitle = "",
            chapterId = 10L,
            chapterName = "",
            scanlator = null,
            chapterUrl = "",
            read = false,
            bookmark = false,
            lastPageRead = 0,
            pageCount = 0,
            sourceId = 1L,
            dateFetch = Instant.fromEpochMilliseconds(0L),
            coverData = MangaCover(1L, 1L, isMangaFavorite = true, url = url, lastModified = null),
        )

        private fun novel() = NovelUpdateWithRelations(
            novelId = 1L,
            novelTitle = "",
            chapterId = 10L,
            chapterName = "",
            chapterUrl = "",
            read = false,
            bookmark = false,
            lastTextProgress = 0L,
            source = "src",
            dateFetch = Instant.fromEpochMilliseconds(0L),
            coverData = NovelCover(url = null, sourceId = "src", isNovelFavorite = true, lastModified = null, 1L),
            novelUrl = "",
        )

        @JvmStatic
        fun faceChanges() = listOf(
            Arguments.of(
                "an Edit info cover on a manga",
                { face: UnifiedWidgetUpdates -> face.copy(manga = listOf(manga(url = "mine"))) },
                false,
            ),
            Arguments.of("a novel read through", { it: UnifiedWidgetUpdates -> it.copy(novel = emptyList()) }, false),
            Arguments.of("Show narrowed", { it: UnifiedWidgetUpdates -> it.copy(shown = ContentType.MANGA) }, false),
            Arguments.of("app lock on", { face: UnifiedWidgetUpdates -> face }, true),
        )
    }
}
