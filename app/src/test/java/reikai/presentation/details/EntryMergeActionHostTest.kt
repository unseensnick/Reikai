package reikai.presentation.details

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.library.EntryLibraryRemoval
import reikai.domain.merge.EntryMergeManager
import reikai.domain.merge.GroupSnapshot
import reikai.domain.merge.MergeManager
import tachiyomi.core.common.i18n.stringResource
import java.util.Collections

/**
 * Manage sources' remove, over a removal in memory: nothing is deleted while the Undo can still put the
 * sources back, and leaving the screen closes that window as surely as the snackbar does.
 */
class EntryMergeActionHostTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val removal = MemoryRemoval()
    private val snackbarShown = CompletableDeferred<Unit>()
    private val snackbarResult = CompletableDeferred<SnackbarResult>()
    private val offered = CompletableDeferred<List<Long>>()

    private val host = EntryMergeActionHost(
        scope = scope,
        snackbarHostState = mockk<SnackbarHostState> {
            coEvery { showSnackbar(any(), any(), any(), any()) } coAnswers {
                snackbarShown.complete(Unit)
                snackbarResult.await()
            }
        },
        context = mockk(relaxed = true),
        group = EntryMergeGroupHost(
            mergeManager = mockk<EntryMergeManager> { coEvery { computeRelatedIds(any()) } returns longArrayOf(3L) },
            initialIds = longArrayOf(1L, 2L, 3L),
            anchorChanges = emptyFlow(),
            onSourceChange = { _, _ -> },
        ) { emptyList() },
        anchorId = { 3L },
        mergeManager = mockk<MergeManager>(relaxed = true) {
            coEvery { captureGroup(any()) } returns GroupSnapshot.EMPTY
            coEvery { removeFromGroup(any(), any()) } returns longArrayOf(3L)
        },
        dismissDialog = {},
        removal = removal,
        offerToDeleteDownloads = { offered.complete(it) },
    )

    @BeforeEach
    fun setUp() {
        mockkStatic(LOCALIZE)
        every { any<Context>().stringResource(any()) } returns "localized"
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(LOCALIZE)
    }

    @Test
    fun `the covers wait for the undo window`() = runTest {
        host.removeSourcesFromLibrary(SOURCES)
        snackbarShown.await()

        removal.coversDropped shouldBe emptyList()
    }

    @Test
    fun `a closed undo window drops the covers`() = runTest {
        host.removeSourcesFromLibrary(SOURCES)
        snackbarResult.complete(SnackbarResult.Dismissed)
        offered.await()

        removal.coversDropped shouldBe SOURCES
    }

    @Test
    fun `a closed undo window offers the sources' downloads`() = runTest {
        host.removeSourcesFromLibrary(SOURCES)
        snackbarResult.complete(SnackbarResult.Dismissed)

        offered.await() shouldBe SOURCES
    }

    @Test
    fun `an undo puts each source back with the date it joined`() = runTest {
        host.removeSourcesFromLibrary(SOURCES)
        snackbarResult.complete(SnackbarResult.ActionPerformed)
        removal.restored.await()

        removal.favoriteAt shouldBe mapOf(1L to 100L, 2L to 200L)
    }

    @Test
    fun `an undo keeps the covers`() = runTest {
        host.removeSourcesFromLibrary(SOURCES)
        snackbarResult.complete(SnackbarResult.ActionPerformed)
        removal.restored.await()

        removal.coversDropped shouldBe emptyList()
    }

    @Test
    fun `leaving the screen during the undo window drops the covers`() = runTest {
        host.removeSourcesFromLibrary(SOURCES)
        snackbarShown.await()
        scope.coroutineContext.job.cancelAndJoin()

        removal.coversDropped shouldBe SOURCES
    }

    private class MemoryRemoval : EntryLibraryRemoval(mockk(relaxed = true), mockk(relaxed = true)) {
        val favoriteAt: MutableMap<Long, Long?> = Collections.synchronizedMap(
            mutableMapOf<Long, Long?>(
                1L to 100L,
                2L to 200L,
            ),
        )
        val coversDropped: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val restored = CompletableDeferred<Unit>()

        override fun entryId(id: Long) = EntryId.Manga(id)

        override suspend fun favoriteAt(id: Long) = favoriteAt[id]

        override suspend fun writeFavoriteAt(favoriteAt: Map<Long, Long?>): Boolean {
            this.favoriteAt.putAll(favoriteAt)
            if (favoriteAt.values.all { it != null }) restored.complete(Unit)
            return true
        }

        override suspend fun deleteCovers(id: Long): Boolean {
            coversDropped += id
            return false
        }

        override suspend fun stampCover(id: Long) = Unit
    }

    private companion object {
        val SOURCES = listOf(1L, 2L)
        const val LOCALIZE = "tachiyomi.core.common.i18n.LocalizeKt"
    }
}
