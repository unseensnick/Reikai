package reikai.data.extension

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import okio.BufferedSource
import reikai.domain.extension.NO_SIGNING_KEY
import reikai.domain.extension.repoNameFromAddress

private const val INDEX_FILE = "/index.min.json"

private const val IREADER_REPO = "https://raw.githubusercontent.com/IReaderorg/IReader-extensions/"

// IReader's index names no key, so this was read off its APKs, every one of which it signs with it.
private const val IREADER_SIGNING_KEY = "f4527fa6edd6de2a8ec987f9967bfdb8836dfef88e437a87ac81bba70557c17c"

/**
 * One entry of IReader's store index, a flat array beside its `apk/` and `icon/` folders. A required
 * [id] and a boolean [nsfw] are what set it apart from a tachiyomi index entry, which has neither.
 */
@Serializable
internal data class IReaderIndexEntry(
    val pkg: String,
    val apk: String,
    val name: String,
    val id: Long,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Boolean,
)

/**
 * Whether [this] store lists IReader extensions. Mihon rewrites an old-style index address to its
 * `repo.json` before storing it, and an old backup restores a bare base address, so neither ends so.
 */
val ExtensionStore.isIReaderIndex: Boolean get() = isLegacy && indexUrl.endsWith(INDEX_FILE)

/**
 * The store at [indexUrl] when [source] reads as a non-empty IReader index, else null. It publishes no
 * name and no key, so it is named from its address, and only IReader's own repo carries its known key;
 * any other IReader-format store's extensions are trusted per version.
 */
fun readIReaderStore(indexUrl: String, source: BufferedSource, json: Json): ExtensionStore? {
    if (!indexUrl.endsWith(INDEX_FILE)) return null
    val entries = runCatching { json.decodeFromBufferedSource<List<IReaderIndexEntry>>(source) }.getOrNull()
    if (entries.isNullOrEmpty()) return null
    val (name, website) = repoNameFromAddress(indexUrl)
    return ExtensionStore(
        indexUrl = indexUrl,
        name = name,
        badgeLabel = name,
        signingKey = if (indexUrl.startsWith(IREADER_REPO)) IREADER_SIGNING_KEY else NO_SIGNING_KEY,
        contact = ExtensionStore.Contact(website = website ?: indexUrl, discord = null),
        isLegacy = true,
        extensionListUrl = null,
    )
}

/** The extensions [store] lists, one per package: the index keeps stale copies after newer ones. */
fun readIReaderExtensions(store: ExtensionStore, source: BufferedSource, json: Json): List<Extension.Available> {
    val base = store.indexUrl.removeSuffix(INDEX_FILE)
    return json.decodeFromBufferedSource<List<IReaderIndexEntry>>(source)
        .groupBy { it.pkg }
        .map { (_, copies) -> copies.maxBy { it.code }.toAvailable(store, base) }
}

private fun IReaderIndexEntry.toAvailable(store: ExtensionStore, base: String) = Extension.Available(
    name = name,
    pkgName = pkg,
    versionName = version,
    versionCode = code,
    // The loader's rule for an installed apk, so an update compares like with like.
    libVersion = version.substringBeforeLast('.').toDouble(),
    lang = lang,
    contentWarning = if (nsfw) ContentWarning.NSFW else ContentWarning.SAFE,
    kind = Extension.Kind.IREADER,
    sources = listOf(Extension.Available.Source(id = id, lang = lang, name = name, baseUrl = "")),
    apkUrl = "$base/apk/$apk",
    iconUrl = "$base/icon/${apk.removeSuffix(".apk")}.png",
    store = store,
)
