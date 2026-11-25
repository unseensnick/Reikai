package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupLocalVolume
import eu.kanade.tachiyomi.data.backup.models.BackupLocalVolumeFields
import eu.kanade.tachiyomi.data.backup.models.BackupVolumeOwner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.local.VolumeOwner

/** A backup is outside input, so a restored owner row may only name one entry of the local folder. */
class RestoredOwnersTest {

    private fun carrying(owner: BackupVolumeOwner) = object : BackupLocalVolumeFields {
        override var localVolumes: List<BackupLocalVolume> = emptyList()
        override var volumeOwners: List<BackupVolumeOwner> = listOf(owner)
        override var volumePick: String? = null
    }

    @ParameterizedTest(name = "folder \"{0}\", volume \"{1}\"")
    @CsvSource(
        "Other, Vol 2, true",
        "Other, '', true",
        "../../DCIM, Camera, false",
        "Other, ../Camera, false",
        "Other/Nested, Vol 2, false",
        "'', Vol 2, false",
        "Other, .., false",
        "Other, /, true",
    )
    fun `a restored owner row is kept only while it names entries of the local folder`(
        folder: String,
        volume: String,
        kept: Boolean,
    ) {
        carrying(BackupVolumeOwner(folder, volume)).restoredOwners("Saga") shouldBe
            if (kept) listOf(VolumeOwner(folder, volume, "Saga")) else emptyList()
    }
}
