package reikai.presentation.browse.source

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import mihon.core.navigation.SourcePreferencesRoute
import reikai.domain.source.SourceKey

/** A novel source's settings, whose id is text; a manga source's are upstream's [SourcePreferencesRoute]. */
@Serializable
data class NovelSourcePreferencesRoute(val sourceId: String) : NavKey

/** The settings page of the source at [this] key, the one place either type's is built. */
fun SourceKey.preferencesRoute(): NavKey = when (this) {
    is SourceKey.Manga -> SourcePreferencesRoute(id)
    is SourceKey.Novel -> NovelSourcePreferencesRoute(id)
}
