package reikai.data.track

import eu.kanade.tachiyomi.data.track.BaseTracker

/**
 * Stores a pasted or captured credential only once [currentUser] proves it works, and fills the username
 * slot as well, since `isLoggedIn` reads both. [useCredential] points the tracker's interceptor at the
 * credential for that check and back at nothing when it fails, so a rejected one leaves no half sign-in.
 */
suspend fun BaseTracker.storeCheckedCredential(
    credential: String,
    useCredential: (String?) -> Unit,
    currentUser: suspend () -> String,
) {
    useCredential(credential)
    try {
        val username = currentUser()
        saveDisplayUsername(username)
        saveCredentials(username, credential)
    } catch (e: Throwable) {
        useCredential(null)
        throw e
    }
}
