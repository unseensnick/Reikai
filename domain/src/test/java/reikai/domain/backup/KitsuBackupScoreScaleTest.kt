package reikai.domain.backup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KitsuBackupScoreScaleTest {

    @Test
    fun `a backup marked as native scale keeps its Kitsu scores`() {
        KitsuBackupScoreScale.of(nativeScaleMarked = true, listOf(KITSU to 7.5)).restore(KITSU, 7.5) shouldBe 7.5
    }

    @Test
    fun `an unmarked backup's half-step Kitsu score is doubled`() {
        KitsuBackupScoreScale.of(nativeScaleMarked = false, listOf(KITSU to 7.5, KITSU to 4.0))
            .restore(KITSU, 7.5) shouldBe 15.0
    }

    @Test
    fun `an unmarked backup's whole Kitsu score is doubled`() {
        KitsuBackupScoreScale.of(nativeScaleMarked = false, listOf(KITSU to 7.5, KITSU to 4.0))
            .restore(KITSU, 4.0) shouldBe 8.0
    }

    @Test
    fun `an unmarked backup with a Kitsu score above 10 is already on the native scale`() {
        KitsuBackupScoreScale.of(nativeScaleMarked = false, listOf(KITSU to 4.0, KITSU to 16.0))
            .restore(KITSU, 4.0) shouldBe 4.0
    }

    @Test
    fun `another tracker's score above 10 says nothing about Kitsu's scale`() {
        KitsuBackupScoreScale.of(nativeScaleMarked = false, listOf(KITSU to 4.0, ANILIST to 85.0))
            .restore(KITSU, 4.0) shouldBe 8.0
    }

    @Test
    fun `an unset Kitsu score stays unset`() {
        KitsuBackupScoreScale.of(nativeScaleMarked = false, listOf(KITSU to 0.0)).restore(KITSU, 0.0) shouldBe 0.0
    }

    @Test
    fun `an unmarked backup leaves other trackers' scores alone`() {
        KitsuBackupScoreScale.of(nativeScaleMarked = false, listOf(KITSU to 4.0, ANILIST to 7.5))
            .restore(ANILIST, 7.5) shouldBe 7.5
    }

    private companion object {
        const val KITSU = 3L
        const val ANILIST = 2L
    }
}
