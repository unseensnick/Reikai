package reikai.presentation.details

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.merge.DetailsRemoval
import reikai.domain.merge.TestMergeManagers

/**
 * What the details heart removes, through the group host both details models read and each type's own
 * merge manager. The heart used to take only the entry the page was opened on: under a source chip it
 * removed the wrong source, and under All it left the rest of the group favorited but collapsed out of
 * the library, so the series appeared to half-vanish.
 */
class DetailsRemovalConformanceTest {

    private suspend fun host(type: ContentType, selected: Long? = null): EntryMergeGroupHost {
        val group = mapOf(OPENED to GROUP, SIBLING to GROUP, OTHER to GROUP)
        val managers = TestMergeManagers(mapOf(type to group), mergingOn = true)
        val host = EntryMergeGroupHost(managers.of(type), longArrayOf(OPENED), emptyFlow(), { _, _ -> }) { emptyList() }
        host.refresh(OPENED)
        host.selectSource(selected)
        return host
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the All view of a merged entry asks before removing`(type: ContentType) = runTest {
        host(type).removal(OPENED).asksForGroup shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `every grouped source leaves when the grouped choice is kept`(type: ContentType) = runTest {
        host(type).removal(OPENED).targets(removeGrouped = true) shouldContainExactly listOf(OPENED, SIBLING, OTHER)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a source chip removes the source it shows, not the opened entry`(type: ContentType) = runTest {
        host(type, selected = SIBLING).removal(OPENED).targets(removeGrouped = false) shouldContainExactly
            listOf(SIBLING)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a source chip removes without asking`(type: ContentType) = runTest {
        host(type, selected = SIBLING).removal(OPENED).asksForGroup shouldBe false
    }

    @Test
    fun `declining the grouped choice removes only the opened entry`() {
        val removal = DetailsRemoval(OPENED, listOf(OPENED, SIBLING), selectedId = null)

        removal.targets(removeGrouped = false) shouldContainExactly listOf(OPENED)
    }

    @Test
    fun `an entry standing alone removes itself without asking`() {
        DetailsRemoval(OPENED, listOf(OPENED), selectedId = null).asksForGroup shouldBe false
    }

    @Test
    fun `an entry standing alone removes only itself`() {
        DetailsRemoval(OPENED, listOf(OPENED), selectedId = null).targets(removeGrouped = true) shouldContainExactly
            listOf(OPENED)
    }

    private companion object {
        const val OPENED = 1L
        const val SIBLING = 2L
        const val OTHER = 3L
        const val GROUP = 10L
    }
}
