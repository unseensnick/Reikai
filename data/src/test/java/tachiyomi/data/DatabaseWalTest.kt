package tachiyomi.data

import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.SqliteJournalMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * WAL on every device, so a write never blocks reads; a low-RAM device keeps it with one reader instead of four,
 * since each connection holds its own page cache.
 */
class DatabaseWalTest {

    @Test
    fun `a device with normal memory keeps WAL`() {
        DatabaseBindings.sqlDriverConfiguration(isLowRam = false).journalMode shouldBe SqliteJournalMode.WAL
    }

    @Test
    fun `a low-RAM device keeps WAL`() {
        DatabaseBindings.sqlDriverConfiguration(isLowRam = true).journalMode shouldBe SqliteJournalMode.WAL
    }

    @Test
    fun `the reader pool runs in WAL on a low-RAM device`() {
        val model = DatabaseBindings.sqlDriverConfiguration(isLowRam = true).concurrencyModel
        (model as MultipleReadersSingleWriter).isWal shouldBe true
    }

    @Test
    fun `a device with normal memory opens four readers`() {
        val model = DatabaseBindings.sqlDriverConfiguration(isLowRam = false).concurrencyModel
        (model as MultipleReadersSingleWriter).walCount shouldBe 4
    }

    @Test
    fun `a low-RAM device opens one reader`() {
        val model = DatabaseBindings.sqlDriverConfiguration(isLowRam = true).concurrencyModel
        (model as MultipleReadersSingleWriter).walCount shouldBe 1
    }
}
