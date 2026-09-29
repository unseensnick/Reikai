package reikai.data.dedupe

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadStore
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.core.migration.Migration
import mihon.core.migration.Migrator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadStore

/**
 * Both saved download queues are re-pointed by a migration after the upgrade's dedupe, and both engines restore
 * theirs as they are built, which can be before the migrations finish. So each restore waits for them.
 */
class SavedQueueRestoreOrderTest {

    private val gate = CompletableDeferred<Unit>()

    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
    }

    @AfterEach
    fun releaseMigrator() {
        gate.complete(Unit)
        Migrator.release()
    }

    @ParameterizedTest
    @EnumSource(Store::class)
    fun `a saved queue is not read while a migration is still running`(store: Store) = runTest {
        Migrator.initialize(
            old = 1,
            new = 2,
            migrations = listOf(Migration.of(2f) { gate.await().let { true } }),
            onMigrationComplete = {},
        )

        val restored = async { store.restore(context) }
        runCurrent()
        val readEarly = restored.isCompleted
        gate.complete(Unit)
        restored.await()

        readEarly shouldBe false
    }

    enum class Store {
        MANGA {
            override suspend fun restore(context: Context) {
                DownloadStore(context, mockk(), Json, mockk(), mockk()).restore()
            }
        },
        NOVEL {
            override suspend fun restore(context: Context) {
                NovelDownloadStore(context, mockk()).restore()
            }
        },
        ;

        abstract suspend fun restore(context: Context)
    }
}
