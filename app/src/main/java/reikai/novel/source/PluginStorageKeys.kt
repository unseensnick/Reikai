package reikai.novel.source

private const val LN_STORAGE_PREFIX = "ln_storage::"
private const val IREADER_STORAGE_PREFIX = "ireader_storage::"

/** The app-store prefix an LN plugin's own settings sit under. */
fun lnStorageScope(pluginId: String) = "$LN_STORAGE_PREFIX$pluginId::"

/** The app-store prefix an IReader extension's own settings sit under. */
fun ireaderStorageScope(packageName: String) = "$IREADER_STORAGE_PREFIX$packageName::"

/**
 * The plugin or extension scope an app-store key belongs to, such as `ln_storage::<id>::`, or null for
 * a key the app owns. A backup carries these with Source settings, one entry per scope, because they
 * are a source's settings even though they are not kept in a per-source file.
 */
fun pluginStorageScope(key: String): String? {
    val prefix = listOf(LN_STORAGE_PREFIX, IREADER_STORAGE_PREFIX).firstOrNull(key::startsWith) ?: return null
    val end = key.indexOf("::", prefix.length).takeIf { it > prefix.length } ?: return null
    return key.substring(0, end + 2)
}
