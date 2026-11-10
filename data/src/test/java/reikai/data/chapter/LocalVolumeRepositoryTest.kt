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
}
