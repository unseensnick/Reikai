package reikai.data.cache

import android.content.Context
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LegacyNovelCustomCoverTest {

    @TempDir
    lateinit var dir: File

    /** The name builds before 186 wrote, so a file still on disk under it is found and moved. */
    @Test
    fun `a novel's legacy custom cover is named by its negated id`() {
        val cache = CoverCache(
            mockk<Context> {
                every { getExternalFilesDir(any()) } answers { File(dir, firstArg<String>()).apply { mkdirs() } }
            },
        )

        cache.legacyNovelCustomCoverFile(4L).name shouldBe DiskUtil.hashKeyForDisk("-4")
    }
}
