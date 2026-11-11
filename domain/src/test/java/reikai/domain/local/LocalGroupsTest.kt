package reikai.domain.local

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

/** Which series lists which local volume, from the rows a combine, a split and an organize write. */
class LocalGroupsTest {

    // B combined into A, then B's Vol 2 split out into S, which has no folder of its own.
    private val groups = LocalGroups(
        listOf(
            VolumeOwner("B", VolumeOwner.WHOLE, "A"),
            VolumeOwner("B", "Vol 2", "S"),
            VolumeOwner("S", VolumeOwner.WHOLE, "S"),
        ),
    )

    @Test
    fun `only a file loose in the series' own folder becomes a volume of its own`() {
        listOf(
            localVolumeKey("A", "A", VolumeOwner.LOOSE, "Ch 1", eachLoose = true),
            localVolumeKey("A", "B", VolumeOwner.LOOSE, "Ch 1", eachLoose = true),
            localVolumeKey("A", "A", VolumeOwner.LOOSE, "Ch 1", eachLoose = false),
        ) shouldBe listOf("Ch 1", "B/", "")
    }

    @Test
    fun `a volume's own row wins over its folder's whole row`() {
        listOf(groups.ownerOf("B", "Vol 1"), groups.ownerOf("B", "Vol 2"), groups.ownerOf("C", "Vol 1")) shouldBe
            listOf("A", "S", "C")
    }

    @Test
    fun `a series lists its own folder first, then the folders it holds volumes of`() {
        listOf(groups.foldersOf("A"), groups.foldersOf("S"), groups.foldersOf("B")) shouldBe
            listOf(listOf("A", "B"), listOf("B"), listOf("B"))
    }

    @Test
    fun `a folder held whole by another series is no series of its own`() {
        listOf(groups.isLentWhole("B"), groups.isLentWhole("S"), groups.isLentWhole("A")) shouldBe
            listOf(true, false, false)
    }

    @Test
    fun `a member folder the local folder no longer holds is missing, the series' own never is`() {
        listOf(groups.missingMembers("A", listOf("C")), groups.missingMembers("A", listOf("B"))) shouldBe
            listOf(setOf("B"), emptySet())
    }

    @Test
    fun `naming one folder of a group names every folder the group lists across`() {
        groups.closure(listOf("S")) shouldBe setOf("S", "B", "A")
    }

    @Test
    fun `a volume's key in a series is its own name at home and folder and name elsewhere, read back alike`() {
        listOf(localGroupKey("A", "A", "Vol 1"), localGroupKey("A", "B", "Vol 1"), localGroupKey("A", "B", "")) shouldBe
            listOf("Vol 1", "B/Vol 1", "B/")
    }

    @Test
    fun `holding one series of a group holds every folder it lists, so a listing inside needs no other`() = runTest {
        val locks = LocalSeriesLocks(
            object : LocalVolumeOwnerRepository {
                override suspend fun all(type: ContentType) = groups.rows
            },
        ).apply { open() }

        locks.withSeries(ContentType.MANGA, "A") { locks.withSeries(ContentType.MANGA, "B") { "listed" } } shouldBe
            "listed"
    }

    @Test
    fun `a key reads back to the folder and volume it names`() {
        listOf(localGroupUnit("A", "Vol 1"), localGroupUnit("A", "B/Vol 1"), localGroupUnit("A", "B/")) shouldBe
            listOf("A" to "Vol 1", "B" to "Vol 1", "B" to "")
    }
}
