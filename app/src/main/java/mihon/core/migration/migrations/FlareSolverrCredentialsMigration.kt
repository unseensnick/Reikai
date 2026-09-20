package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.carryFlareSolverrUserInfo
import eu.kanade.tachiyomi.network.interceptor.splitFlareSolverrUserInfo
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext

/**
 * Moves `user:password@` out of a stored bypass-server address into its own username and password
 * preferences, which are private and so stay out of a backup.
 *
 * Credentials typed into the address never worked: OkHttp parses userinfo into the URL and then
 * derives no header from it. They did reach every preference backup, because the address key has no
 * private prefix, so anyone who tried this has the password sitting in the backups they already
 * made. Moving it cannot fix those; the docs say to rotate it.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class FlareSolverrCredentialsMigration(
    private val networkPreferences: NetworkPreferences,
) : Migration {
    override val version: Float = 196f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val stored = networkPreferences.flareSolverrUrl
        if (!stored.isSet()) return true
        val raw = stored.get()
        if (splitFlareSolverrUserInfo(raw) == null) return true

        networkPreferences.carryFlareSolverrUserInfo(raw)

        return true
    }
}
