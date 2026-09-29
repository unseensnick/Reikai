package reikai.data.track

/**
 * Whether a tracker's metadata fetch ("Fill from tracker") needs a login. SignedIn trackers call through
 * their authenticated client, so a signed-out fill is refused before it fetches; Public ones fill from
 * a public catalogue whether or not the user is signed in.
 */
enum class MetadataAccess {
    Public,
    SignedIn,
}
