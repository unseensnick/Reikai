package reikai.data.track

import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.CookieLoginTracker

/**
 * A tracker signed in with a pasted token or a captured session cookie, checked against the service's
 * own profile route before either is stored. A subclass answers where a credential goes and whose
 * profile it opens; a logout that holds more state overrides [logout] and calls through.
 */
abstract class TokenTracker(id: Long, name: String) : BaseTracker(id, name), CookieLoginTracker {

    protected abstract fun useCredential(credential: String?)

    protected abstract suspend fun currentUsername(): String

    override suspend fun login(username: String, password: String) = login(password)

    suspend fun login(token: String) = storeCredential(token.trim())

    override suspend fun loginWithCookie(credential: String) = storeCredential(credential)

    private suspend fun storeCredential(credential: String) =
        storeCheckedCredential(credential, ::useCredential) { currentUsername() }

    override suspend fun updateUserConfig() {
        saveDisplayUsername(currentUsername())
    }

    override fun logout() {
        super.logout()
        useCredential(null)
    }

    fun restoreToken(): String? = trackPreferences.trackPassword(this).get().ifBlank { null }
}
