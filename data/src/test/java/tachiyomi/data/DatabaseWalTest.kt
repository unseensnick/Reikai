package tachiyomi.data

import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.SqliteJournalMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Upstream's port of Room's connection setup inverted the low-RAM check, turning WAL off on every device
 * with normal memory. Room keeps WAL except on low-RAM devices, and so does Reikai.
 */
class DatabaseWalTest {

    @Test
    fun `a device with normal memory keeps WAL`() {
        DatabaseBindings.sqlDriverConfiguration(isLowRamDevice = false).journalMode shouldBe SqliteJournalMode.WAL
    }

    @Test
    fun `a device that cannot report its memory keeps WAL`() {
        DatabaseBindings.sqlDriverConfiguration(isLowRamDevice = null).journalMode shouldBe SqliteJournalMode.WAL
    }

    @Test
    fun `a low-RAM device drops to TRUNCATE`() {
        DatabaseBindings.sqlDriverConfiguration(isLowRamDevice = true).journalMode shouldBe SqliteJournalMode.Truncate
    }

    @Test
    fun `the reader pool follows the journal mode`() {
        val model = DatabaseBindings.sqlDriverConfiguration(isLowRamDevice = true).concurrencyModel
        (model as MultipleReadersSingleWriter).isWal shouldBe false
    }
}
