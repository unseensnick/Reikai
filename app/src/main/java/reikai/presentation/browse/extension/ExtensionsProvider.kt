package reikai.presentation.browse.extension

import eu.kanade.domain.extension.model.Extensions
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import reikai.domain.library.ContentType
import reikai.novel.install.LnPluginLoadFailure
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.source.NovelSource
import reikai.novel.source.langCode
import reikai.novel.source.toLangCode
import reikai.novel.update.LnPluginUpdate

/**
 * One install pipeline's part of the Extensions list. A provider answers about its own extensions and
 * acts on them; it never sections, applies the chip or the search box, or decides what the list
 * looks like, because those describe the whole list and the engine owns them. A pipeline can serve
 * more than one content type, which each row's key names.
 */
interface ExtensionsProvider {

    val contentTypes: Set<ContentType>

    /** Rows this provider contributes, unsectioned. Null until its first list has been produced. */
    val rows: Flow<List<BrowseExtensionRow>?>

    /** The content types it has a repo for, so an empty list can say which kind of empty it is. */
    val reposFor: Flow<Set<ContentType>>

    /** Whether a refresh is in flight, so pull-to-refresh keeps its spinner up. */
    val isRefreshing: Flow<Boolean>

    /** Whether installing from here needs the system's install-unknown-apps permission granted. */
    val needsInstallPermission: Flow<Boolean>

    fun refresh()

    /** Install the pending updates among [rows], which are this provider's own and on screen now. */
    fun updateAll(rows: List<BrowseExtensionRow>)
}

/**
 * The apk extensions, manga and novel, over Mihon's live [ExtensionsViewModel]. One provider, because
 * one store index lists both kinds, so a refresh per kind would download every store twice.
 */
class ApkExtensionsProvider(private val model: ExtensionsViewModel) : ExtensionsProvider {

    override val contentTypes = setOf(ContentType.MANGA, ContentType.NOVELS)

    override val rows: Flow<List<BrowseExtensionRow>?> =
        combine(model.extensions, model.novelExtensions, model.currentDownloads) { manga, novel, downloads ->
            val mangaRows = apkExtensionRows(manga, downloads) { ExtensionKey.Manga(it) }
            val novelRows = apkExtensionRows(novel, downloads) { ExtensionKey.NovelApk(it) }
            if (mangaRows == null || novelRows == null) null else mangaRows + novelRows
        }

    // A store counts toward novels only once it lists one, so a manga-only store leaves the Novels
    // chip telling someone with no plugin repo to add one.
    override val reposFor: Flow<Set<ContentType>> =
        combine(model.hasRepos, model.novelExtensions) { hasStores, novel ->
            buildSet {
                if (hasStores) add(ContentType.MANGA)
                if (novel != null && novel.isNotEmpty()) add(ContentType.NOVELS)
            }
        }

    override val isRefreshing: Flow<Boolean> = model.isRefreshing

    override val needsInstallPermission: Flow<Boolean> = model.needsInstallPermission

    override fun refresh() = model.findAvailableExtensions()

    override fun updateAll(rows: List<BrowseExtensionRow>) {
        rows.mapNotNull { (it.payload as? ExtensionUiModel.Item)?.extension as? Extension.Loaded }
            .forEach(model::updateExtension)
    }
}

/**
 * One apk kind's extensions as rows, keeping the interactor's own partition: an extension with an
 * update is under Updates and nowhere else, one that did not load sits under Not loaded as upstream
 * shows it, and what is available splits by language. Null while the first list is still being produced.
 */
fun apkExtensionRows(
    extensions: Extensions?,
    downloads: Map<String, InstallStep>,
    key: (pkgName: String) -> ExtensionKey,
): List<BrowseExtensionRow>? = extensions?.run {
    updates.map { it.toRow(ExtensionSection.Updates, downloads, key) } +
        notLoaded.map { it.toRow(ExtensionSection.NotLoaded, downloads, key) } +
        loaded.map { it.toRow(ExtensionSection.Installed, downloads, key) } +
        available.map { it.toRow(ExtensionSection.Available(it.lang), downloads, key) }
}

private fun Extensions.isNotEmpty() =
    updates.isNotEmpty() || loaded.isNotEmpty() || available.isNotEmpty() || notLoaded.isNotEmpty()

private fun Extension.toRow(
    section: ExtensionSection,
    downloads: Map<String, InstallStep>,
    key: (pkgName: String) -> ExtensionKey,
) =
    BrowseExtensionRow(
        key = key(pkgName),
        name = name,
        lang = lang.orEmpty(),
        section = section,
        // An obsolete extension is the one the user has to act on, so it leads its section, which is
        // the order GetExtensionsByType hands the installed list over in.
        needsAttention = this is Extension.Loaded && isObsolete,
        searchTerms = searchTerms(),
        searchIds = searchIds(),
        payload = ExtensionUiModel.Item(this, downloads[pkgName] ?: InstallStep.Idle),
    )

private fun Extension.searchTerms(): List<String> = buildList {
    add(name)
    when (this@searchTerms) {
        is Extension.Loaded -> sources.forEach { source ->
            add(source.name)
            (source as? HttpSource)?.getHomeUrl()?.let(::add)
        }
        is Extension.Available -> sources.forEach {
            add(it.name)
            add(it.baseUrl)
        }
        is Extension.NotLoaded -> Unit
    }
}

private fun Extension.searchIds(): List<String> = when (this) {
    is Extension.Loaded -> sources.map { it.id.toString() }
    is Extension.Available -> sources.map { it.id.toString() }
    is Extension.NotLoaded -> emptyList()
}

/** The light-novel half, over [LnPluginManagerViewModel]. */
class NovelExtensionsProvider(private val model: LnPluginManagerViewModel) : ExtensionsProvider {

    override val contentTypes = setOf(ContentType.NOVELS)

    override val rows: Flow<List<BrowseExtensionRow>?> = model.state.map { state ->
        if (!state.hasLoaded) return@map null
        novelExtensionRows(state.updates, state.notLoaded, state.installed, state.available)
    }

    override val reposFor: Flow<Set<ContentType>> =
        model.state.map { if (it.hasRepos) setOf(ContentType.NOVELS) else emptySet() }

    override val isRefreshing: Flow<Boolean> = model.state.map { it.isRefreshing }

    // Plugins are JavaScript the app fetches itself, so nothing is installed through the system.
    override val needsInstallPermission: Flow<Boolean> = flowOf(false)

    override fun refresh() = model.refresh()

    override fun updateAll(rows: List<BrowseExtensionRow>) {
        rows.mapNotNull { it.payload as? LnPluginUpdate }.forEach(model::update)
    }
}

/**
 * The light-novel plugins as rows, at most one per plugin.
 *
 * The three lists arrive independently rather than partitioned the way manga's are, and they overlap
 * two ways: an installed plugin with an update pending is in both, and one whose registry URL no
 * longer matches what was recorded at install stays in Available as well. Each plugin takes the
 * first section that claims it, so the list never shows the same one twice.
 */
fun novelExtensionRows(
    updates: List<LnPluginUpdate>,
    notLoaded: List<LnPluginLoadFailure>,
    installed: List<NovelSource>,
    available: List<LnRegistryEntry>,
): List<BrowseExtensionRow> {
    val claimed = mutableSetOf<String>()
    // Languages normalised to a code, so a plugin declaring its language in that language lands
    // with the ones declaring a code, with the manga extensions of that language, and renders the
    // same name its own section header does.
    return updates.mapNotNull {
        val lang = it.entry.lang.toLangCode()
        novelRow(claimed, it.entry.site, it.entry.id, it.entry.name, lang, ExtensionSection.Updates, it)
    } + notLoaded.mapNotNull {
        // Keyed by URL when the install never recorded a plugin id, which is still one row per plugin.
        val lang = it.lang.orEmpty().toLangCode()
        novelRow(claimed, it.url, it.pluginId ?: it.url, it.name, lang, ExtensionSection.NotLoaded, it)
    } + installed.mapNotNull {
        novelRow(claimed, it.site, it.id, it.name, it.langCode(), ExtensionSection.Installed, it)
    } + available.mapNotNull {
        val lang = it.lang.toLangCode()
        novelRow(claimed, it.site, it.id, it.name, lang, ExtensionSection.Available(lang), it)
    }
}

private fun novelRow(
    claimed: MutableSet<String>,
    site: String,
    id: String,
    name: String,
    lang: String,
    section: ExtensionSection,
    payload: Any,
) = if (!claimed.add(id)) {
    null
} else {
    BrowseExtensionRow(
        key = ExtensionKey.Novel(id),
        name = name,
        lang = lang,
        section = section,
        needsAttention = false,
        searchTerms = listOf(name, site),
        searchIds = listOf(id),
        payload = payload,
    )
}
