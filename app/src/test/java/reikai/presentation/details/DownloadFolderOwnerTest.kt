package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Open folder and Clear downloads in the details overflow act on the member this rule names, for manga
 * and novels alike, so the menu is offered exactly when there is a folder behind it.
 */
class DownloadFolderOwnerTest {

    private data class Member(val id: Long, val isAnchor: Boolean = false, val hasFiles: Boolean = false)

    private val anchor = Member(1, isAnchor = true)
    private val sibling = Member(2)

    private fun owner(viewed: Member?, group: List<Member>) =
        downloadFolderOwner(viewed, group, isAnchor = { it.isAnchor }, hasDownloads = { it.hasFiles })

    @Test
    fun `a unified view whose only downloads are on a sibling opens the sibling's folder`() {
        val withFiles = sibling.copy(hasFiles = true)
        owner(viewed = null, group = listOf(anchor, withFiles)) shouldBe withFiles
    }

    @Test
    fun `a unified view prefers the anchor's folder when it holds files`() {
        val anchorWithFiles = anchor.copy(hasFiles = true)
        owner(viewed = null, group = listOf(sibling.copy(hasFiles = true), anchorWithFiles)) shouldBe anchorWithFiles
    }

    @Test
    fun `a chip whose own source holds nothing offers nothing, even when a sibling does`() {
        owner(viewed = sibling, group = listOf(anchor.copy(hasFiles = true), sibling)) shouldBe null
    }

    @Test
    fun `a chip whose own source holds files opens its own folder`() {
        val withFiles = sibling.copy(hasFiles = true)
        owner(viewed = withFiles, group = listOf(anchor.copy(hasFiles = true), withFiles)) shouldBe withFiles
    }

    @Test
    fun `an entry with nothing on disk offers nothing`() {
        owner(viewed = null, group = listOf(anchor)) shouldBe null
    }
}
