package reikai.domain.merge

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.recents.EmittingPreferenceStore

@OptIn(ExperimentalCoroutinesApi::class)
class StitchInputChangesTest {

    private val memberships = MutableStateFlow<Map<Long, Long>>(mapOf(1L to 10L))
    private val repository = mockk<MergeGroupRepository> {
        every { getAllMembershipsAsFlow(any()) } returns memberships
    }
    private val preferences = ReikaiLibraryPreferences(EmittingPreferenceStore())

    private fun reorderPreferredSources(type: ContentType) = when (type) {
        ContentType.MANGA -> preferences.preferredMangaSources.set(listOf(2L, 1L))
        else -> preferences.preferredNovelSources.set(listOf("b", "a"))
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `reordering the preferred sources asks for a reconcile`(type: ContentType) = runTest {
        var emissions = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stitchInputChanges(type, repository, preferences).collect { emissions++ }
        }

        reorderPreferredSources(type)

        emissions shouldBe 2
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a membership change still asks for a reconcile`(type: ContentType) = runTest {
        var emissions = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stitchInputChanges(type, repository, preferences).collect { emissions++ }
        }

        memberships.value = mapOf(1L to 10L, 2L to 10L)

        emissions shouldBe 2
    }
}
