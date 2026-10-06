package reikai.novel.source

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import io.mockk.every
import io.mockk.mockk
import mihon.domain.extension.model.ContentWarning

/** An installed tachiyomi-format novel app holding [sources], as the extension scan hands one over. */
internal fun novelApp(vararg sources: Source) = Extension.Loaded(
    name = "App",
    pkgName = "eu.kanade.tachiyomi.novelextension.en.app",
    versionName = "1.6.1",
    versionCode = 1,
    libVersion = 1.6,
    lang = "en",
    contentWarning = ContentWarning.SAFE,
    isShared = true,
    signatures = emptyList(),
    kind = Extension.Kind.TACHIYOMI_NOVEL,
    pkgFactory = null,
    sources = sources.toList(),
    icon = null,
)

/** One catalogue of a [novelApp], registered as `tachiyomi:<sourceId>`. */
internal fun novelCatalogue(sourceId: Long) = mockk<CatalogueSource> {
    every { id } returns sourceId
    every { name } returns "App $sourceId"
    every { lang } returns "en"
    every { supportsLatest } returns false
    every { getFilterList() } returns FilterList()
}
