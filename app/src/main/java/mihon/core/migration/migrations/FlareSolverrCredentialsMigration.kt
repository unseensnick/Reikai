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
 * Credentials in the address never worked (OkHttp derives no header from URL userinfo), but the address
 * key is not private, so they reached every preference backup. Moving them cannot fix those backups;
 * the docs say to rotate the password.
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
