package mihon.core.migration.migrations

import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

class FlareSolverrCredentialsMigrationTest {

    private val store = EmittingPreferenceStore()
    private val networkPreferences = NetworkPreferences(store, isDebugBuild = false)
    private val migration = FlareSolverrCredentialsMigration(networkPreferences)

    private suspend fun migrate() = migration.invoke(MigrationContext(dryrun = false, previousVersion = 195))

    @Test
    fun `credentials in the address move into their own preferences`() = runTest {
        networkPreferences.flareSolverrUrl.set("https://user:secret@solverr.example.com")

        migrate()

        networkPreferences.flareSolverrUsername.get() shouldBe "user"
        networkPreferences.flareSolverrPassword.get() shouldBe "secret"
    }

    @Test
    fun `the address is left without the credentials it carried`() = runTest {
        networkPreferences.flareSolverrUrl.set("https://user:secret@solverr.example.com")

        migrate()

        networkPreferences.flareSolverrUrl.get() shouldBe "https://solverr.example.com"
    }

    @Test
    fun `an address with no credentials is left alone`() = runTest {
        networkPreferences.flareSolverrUrl.set("http://192.168.1.10:8191")

        migrate()

        networkPreferences.flareSolverrUrl.get() shouldBe "http://192.168.1.10:8191"
        networkPreferences.flareSolverrUsername.get() shouldBe ""
    }

    @Test
    fun `an unconfigured server is a no-op`() = runTest {
        migrate() shouldBe true

        networkPreferences.flareSolverrUrl.isSet() shouldBe false
    }

    @Test
    fun `credentials already entered by hand are not overwritten`() = runTest {
        networkPreferences.flareSolverrUrl.set("https://stale:stale@solverr.example.com")
        networkPreferences.flareSolverrUsername.set("current")
        networkPreferences.flareSolverrPassword.set("current-pass")

        migrate()

        networkPreferences.flareSolverrUsername.get() shouldBe "current"
        networkPreferences.flareSolverrPassword.get() shouldBe "current-pass"
        // The address is still cleaned, so the stale pair stops travelling in backups.
        networkPreferences.flareSolverrUrl.get() shouldBe "https://solverr.example.com"
    }
}
