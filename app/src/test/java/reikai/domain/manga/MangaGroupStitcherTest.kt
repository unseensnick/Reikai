package reikai.domain.manga

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.NamespaceSource
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.RankedMember
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager

/** A gallery member's chapters never pair, so the reconcile must see that answer change as it sees a reorder. */
class MangaGroupStitcherTest {

    @Test
    fun `a member's source loading as a gallery source restamps its group`() = runTest {
        val loaded = mutableMapOf<Long, Source>()
        val sourceManager = mockk<SourceManager> { coEvery { get(any<Long>()) } answers { loaded[firstArg()] } }
        val repository = mockk<MergeGroupRepository> {
            coEvery { getRankedMangaMembers() } returns
                listOf(RankedMember(GROUP, 1L, 100L, false), RankedMember(GROUP, 2L, 200L, false))
        }
        val stitcher = MangaGroupStitcher(
            repository,
            mockk(),
            mockk(),
            sourceManager,
            ReikaiLibraryPreferences(InMemoryPreferenceStore()),
        )
        val absent = stitcher.rankings()[GROUP]

        loaded[200L] = mockk<NamespaceSource>()

        stitcher.rankings()[GROUP] shouldNotBe absent
    }

    private companion object {
        const val GROUP = 7L
    }
}
