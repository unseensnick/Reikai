package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.merge.GroupViewerFlags.Member

/** Which member's choice a merged series keeps for each reader-flag field, when its members disagree. */
class GroupViewerFlagsChosenTest {

    private val mode = 0x07L
    private val rotation = 0x38L

    @Test
    fun `a field takes the choice of the most recently read member`() {
        val members = listOf(Member(flags = 0x01L, lastReadAt = 10L), Member(flags = 0x02L, lastReadAt = 20L))

        GroupViewerFlags.chosen(members, listOf(mode)) shouldBe mapOf(mode to 0x02L)
    }

    @Test
    fun `a member that follows the setting does not outrank an older choice`() {
        val members = listOf(Member(flags = 0x01L, lastReadAt = 10L), Member(flags = 0L, lastReadAt = 20L))

        GroupViewerFlags.chosen(members, listOf(mode)) shouldBe mapOf(mode to 0x01L)
    }

    @Test
    fun `a tie goes to the settings owner`() {
        val members = listOf(Member(flags = 0x01L, lastReadAt = 0L), Member(flags = 0x02L, lastReadAt = 0L))

        GroupViewerFlags.chosen(members, listOf(mode)) shouldBe mapOf(mode to 0x01L)
    }

    @Test
    fun `each field is chosen on its own`() {
        val members = listOf(Member(flags = 0x08L, lastReadAt = 10L), Member(flags = 0x02L, lastReadAt = 20L))

        GroupViewerFlags.chosen(members, listOf(mode, rotation)) shouldBe mapOf(mode to 0x02L, rotation to 0x08L)
    }

    @Test
    fun `a field no member chose is left out`() {
        val members = listOf(Member(flags = 0x01L, lastReadAt = 10L))

        GroupViewerFlags.chosen(members, listOf(rotation)) shouldBe emptyMap()
    }
}
