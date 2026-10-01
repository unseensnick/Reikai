package reikai.domain.download

import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class DownloadFolderRenameTest {

    /** A folder named [folderName] beside [siblings]; a [caseBlind] disk finds a name in any letter case. */
    private fun folder(folderName: String, siblings: Set<String> = emptySet(), caseBlind: Boolean = false): UniFile {
        val held = siblings + folderName
        val sourceFolder = mockk<UniFile> {
            every { findFile(any()) } answers {
                val asked = firstArg<String>()
                if (held.any { it.equals(asked, ignoreCase = caseBlind) }) mockk() else null
            }
        }
        return mockk {
            every { name } returns folderName
            every { parentFile } returns sourceFolder
        }
    }

    @Test
    fun `a folder only its own entry is named onto moves to a free name`() {
        movesDownloadFolder(folder("Old"), "New", otherEntryFolders = listOf("Other")) shouldBe true
    }

    @Test
    fun `a folder another entry on the source is named onto stays`() {
        movesDownloadFolder(folder("Old"), "New", otherEntryFolders = listOf("Old")) shouldBe false
    }

    @Test
    fun `another entry's folder name matches whatever its letter case`() {
        movesDownloadFolder(folder("Old"), "New", otherEntryFolders = listOf("OLD")) shouldBe false
    }

    @Test
    fun `a folder stays when another folder already holds its new name`() {
        movesDownloadFolder(folder("Old", siblings = setOf("New")), "New", otherEntryFolders = emptyList()) shouldBe
            false
    }

    @Test
    fun `a change of letter case moves though a case-blind disk finds the new name`() {
        movesDownloadFolder(folder("Old", caseBlind = true), "OLD", otherEntryFolders = emptyList()) shouldBe true
    }
}
