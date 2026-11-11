package reikai.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.time.Duration.Companion.hours

/**
 * Random runs of [LocalLibraryWorld]: whatever a user and a faulty storage provider do to a local library, no
 * file is lost that no in-app delete took and no chapter loses its reading state or gains another's. A failure
 * prints its seed, the fewest steps that still break it, and their trace; `REIKAI_FUZZ_SEED` runs one seed,
 * `REIKAI_FUZZ_SEEDS` that many seeds from `REIKAI_FUZZ_FROM`, and `REIKAI_FUZZ_STEPS` sets the run's length
 * and `REIKAI_FUZZ_TRACE` names a file every run's trace is added to (each read as a system property first,
 * then from the environment).
 */
@RunWith(RobolectricTestRunner::class)
class LocalLibraryFuzzTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(ImportDocuments::class.java, ImportDocuments.AUTHORITY)
        mockkObject(DiskUtil)
        every { DiskUtil.getAvailableStorageSpace(any<UniFile>()) } returns Long.MAX_VALUE
    }

    @After
    fun tearDown() = unmockkObject(DiskUtil)

    @Test
    fun `no run of the fixed seeds loses a file or a chapter's reading state`() = runTest(timeout = 12.hours) {
        failures(seeds(), setting("STEPS")?.toInt() ?: DEFAULT_STEPS) shouldBe emptyList()
    }

    private fun seeds(): List<Long> {
        setting("SEED")?.let { return listOf(it.toLong()) }
        val count = setting("SEEDS")?.toInt() ?: return DEFAULT_SEEDS
        val from = setting("FROM")?.toLong() ?: 1000L
        return List(count) { from + it }
    }

    private fun setting(name: String): String? =
        System.getProperty("reikai.fuzz.${name.lowercase()}") ?: System.getenv("REIKAI_FUZZ_$name")

    private suspend fun failures(seeds: List<Long>, steps: Int): List<String> {
        val seen = HashSet<String>()
        return seeds.mapNotNull { seed ->
            val failure = run(seed, steps, null) ?: return@mapNotNull null
            // One shrink per kind of failure: the rest of a long run only says how often it comes up.
            val kind = failure.message.lineSequence().first().replace(Regex("[0-9]+"), "N").take(60)
            if (!seen.add(kind)) return@mapNotNull "seed $seed fails at step ${failure.step}: $kind"
            val kept = shrink(seed, failure.step)
            val shrunk = run(seed, failure.step + 1, kept) ?: failure
            "seed $seed fails at step ${failure.step}; steps kept: ${kept.sorted()}\n${shrunk.message}\n" +
                shrunk.trace.joinToString("\n")
        }.onEach(::println)
    }

    /** The steps up to the failing one that cannot be left out without the run passing, by dropping one at a time. */
    private suspend fun shrink(seed: Long, failedAt: Int): Set<Int> {
        val kept = (0..failedAt).toMutableSet()
        val deadline = System.currentTimeMillis() + SHRINK_MILLIS
        var dropped = true
        while (dropped && System.currentTimeMillis() < deadline) {
            dropped = false
            for (step in kept.sortedDescending()) {
                if (System.currentTimeMillis() > deadline) break
                if (run(seed, failedAt + 1, kept - step) != null) {
                    kept -= step
                    dropped = true
                }
            }
        }
        return kept
    }

    private suspend fun run(seed: Long, steps: Int, keep: Set<Int>?): WorldFailure? {
        val world = LocalLibraryWorld(context, tmp.newFolder(), seed)
        return try {
            world.open()
            world.run(steps, keep)
        } catch (e: Throwable) {
            WorldFailure(-1, "the world would not open: ${e.stackTraceToString().take(1500)}", world.log)
        } finally {
            setting("TRACE")?.takeIf {
                keep == null
            }?.let { File(it).appendText("seed $seed\n" + world.log.joinToString("\n", postfix = "\n")) }
            world.close()
        }
    }

    private companion object {
        val DEFAULT_SEEDS = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        const val DEFAULT_STEPS = 40
        const val SHRINK_MILLIS = 60_000L
    }
}
