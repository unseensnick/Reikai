package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Open folder and Clear downloads in the details overflow act on the member this rule names, for manga
 * and novels alike, so the menu is offered exactly when there is a folder behind it.
 */
class DownloadFolderOwnerTest {

    data class Member(val id: Long, val isAnchor: Boolean = false, val hasFiles: Boolean = false)

    @ParameterizedTest(name = "{0}")
    @MethodSource("owners")
    fun `the folder opened is the viewed member's, or under All the anchor's before a sibling's`(
        @Suppress("UNUSED_PARAMETER") case: String,
        viewed: Member?,
        group: List<Member>,
        owner: Member?,
    ) {
        downloadFolderOwner(viewed, group, isAnchor = { it.isAnchor }, hasDownloads = { it.hasFiles }) shouldBe owner
    }

    companion object {
        private val ANCHOR = Member(1, isAnchor = true)
        private val ANCHOR_WITH_FILES = ANCHOR.copy(hasFiles = true)
        private val SIBLING = Member(2)
        private val SIBLING_WITH_FILES = SIBLING.copy(hasFiles = true)

        @JvmStatic
        fun owners() = listOf(
            Arguments.of(
                "All with downloads only on a sibling",
                null,
                listOf(ANCHOR, SIBLING_WITH_FILES),
                SIBLING_WITH_FILES,
            ),
            Arguments.of(
                "All with files on the anchor too",
                null,
                listOf(SIBLING_WITH_FILES, ANCHOR_WITH_FILES),
                ANCHOR_WITH_FILES,
            ),
            Arguments.of("a chip holding nothing", SIBLING, listOf(ANCHOR_WITH_FILES, SIBLING), null),
            Arguments.of(
                "a chip holding files",
                SIBLING_WITH_FILES,
                listOf(ANCHOR_WITH_FILES, SIBLING_WITH_FILES),
                SIBLING_WITH_FILES,
            ),
            Arguments.of("nothing on disk", null, listOf(ANCHOR), null),
        )
    }
}
