package reikai.data.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.chapter.LocalVolume
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

class LocalVolumeRepositoryTest {

    @Test
    fun `a restored volume with no name keeps the name set on the device`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val repository = LocalVolumeRepositoryImpl(DatabaseBindings.providesDatabase(driver), backgroundScope)
        repository.setLabel(ContentType.MANGA, "S", "v1", "Prologue")

        repository.restore(ContentType.MANGA, "S", listOf(LocalVolume("v1", position = 2, label = null)))

        repository.loadedVolumes(ContentType.MANGA).getValue("S").single() shouldBe LocalVolume("v1", 2, "Prologue")
        driver.close()
    }

    // A volume added after the backup was taken keeps its place behind the backup's order, never tied in it.
    @Test
    fun `a restored order goes first and the device's other ordered volumes follow it`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val repository = LocalVolumeRepositoryImpl(DatabaseBindings.providesDatabase(driver), backgroundScope)
        repository.setOrder(ContentType.MANGA, "S", listOf("c", "a", "b"))

        repository.restore(
            ContentType.MANGA,
            "S",
            listOf(LocalVolume("a", position = 0, label = null), LocalVolume("b", position = 1, label = null)),
        )

        repository.loadedVolumes(ContentType.MANGA).getValue("S").sortedBy { it.position }.map { it.key } shouldBe
            listOf("a", "b", "c")
        driver.close()
    }

    @Test
    fun `a volume keeps its own notes beside its name`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val repository = LocalVolumeRepositoryImpl(DatabaseBindings.providesDatabase(driver), backgroundScope)
        repository.setLabel(ContentType.NOVELS, "S", "v1.epub", "Prologue")

        repository.setNotes(ContentType.NOVELS, "S", "v1.epub", "Proper translation")

        repository.loadedVolumes(ContentType.NOVELS).getValue("S").single() shouldBe
            LocalVolume("v1.epub", null, "Prologue", "Proper translation")
        driver.close()
    }

    @Test
    fun `blank notes clear a volume's notes`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val repository = LocalVolumeRepositoryImpl(DatabaseBindings.providesDatabase(driver), backgroundScope)
        repository.setNotes(ContentType.MANGA, "S", "v1", "MTL")

        repository.setNotes(ContentType.MANGA, "S", "v1", "  ")

        repository.loadedVolumes(ContentType.MANGA).getValue("S").single().notes shouldBe null
        driver.close()
    }

    @Test
    fun `a restored volume brings back its notes`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val repository = LocalVolumeRepositoryImpl(DatabaseBindings.providesDatabase(driver), backgroundScope)

        repository.restore(ContentType.MANGA, "S", listOf(LocalVolume("v1", null, null, "MTL")))

        repository.loadedVolumes(ContentType.MANGA).getValue("S").single().notes shouldBe "MTL"
        driver.close()
    }
}
