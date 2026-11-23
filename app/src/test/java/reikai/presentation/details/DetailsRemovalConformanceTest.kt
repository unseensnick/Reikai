package reikai.presentation.details

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.merge.DetailsRemoval
import reikai.domain.merge.RemovalPrompt
import reikai.domain.merge.TestMergeManagers

/**
 * What the details heart removes and how it asks, through the group host both details models read. Every
 * remove asks exactly once: the shared confirm, or for a lone E-Hentai gallery its account confirm in place
 * of it. Under a source chip the heart removes that source, and under All it offers the whole group, so the
 * series does not half-vanish. Both types' merge managers are the shared EntryMergeManager with no
 * override, so the cases run once, on manga.
 */
class DetailsRemovalConformanceTest {

    private suspend fun host(selected: Long? = null): EntryMergeGroupHost {
        val group = mapOf(OPENED to GROUP, SIBLING to GROUP, OTHER to GROUP)
        val managers = TestMergeManagers(mapOf(ContentType.MANGA to group), mergingOn = true)
        val host = EntryMergeGroupHost(managers.manga, longArrayOf(OPENED), emptyFlow(), { _, _ -> }) { emptyList() }
        host.refresh(OPENED)
        host.selectSource(selected)
        return host
    }

    @Test
    fun `the All view of a merged entry offers every grouped source`() = runTest {
        host().removal(OPENED).offersGroup shouldBe true
    }

    @Test
    fun `every grouped source leaves when the grouped choice is kept`() = runTest {
        host().removal(OPENED).targets(removeGrouped = true) shouldContainExactly listOf(OPENED, SIBLING, OTHER)
    }

    @Test
    fun `a source chip removes the source it shows, not the opened entry`() = runTest {
        host(selected = SIBLING).removal(OPENED).targets(removeGrouped = false) shouldContainExactly
            listOf(SIBLING)
    }

    @Test
    fun `a source chip asks with the shared confirm`() = runTest {
        val removal = host(selected = SIBLING).removal(OPENED)

        removal.prompt { false } shouldBe RemovalPrompt.Confirm(removal)
    }

    @Test
    fun `a source chip offers no grouped sources`() = runTest {
        host(selected = SIBLING).removal(OPENED).offersGroup shouldBe false
    }

    @Test
    fun `the All view asks the shared confirm even over a source's own`() = runTest {
        val removal = host().removal(OPENED)

        removal.prompt { true } shouldBe RemovalPrompt.Confirm(removal)
    }

    @Test
    fun `declining the grouped choice removes only the opened entry`() {
        val removal = DetailsRemoval(OPENED, listOf(OPENED, SIBLING), selectedId = null)

        removal.targets(removeGrouped = false) shouldContainExactly listOf(OPENED)
    }

    @Test
    fun `an entry standing alone asks with the shared confirm`() = runTest {
        val removal = DetailsRemoval(OPENED, listOf(OPENED), selectedId = null)

        removal.prompt { false } shouldBe RemovalPrompt.Confirm(removal)
    }

    @Test
    fun `an entry standing alone offers no grouped sources`() {
        DetailsRemoval(OPENED, listOf(OPENED), selectedId = null).offersGroup shouldBe false
    }

    @Test
    fun `an entry whose source keeps its own confirm asks only that one`() = runTest {
        DetailsRemoval(OPENED, listOf(OPENED), selectedId = null).prompt { it == OPENED } shouldBe
            RemovalPrompt.SourceConfirm(OPENED)
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
