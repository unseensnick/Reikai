package eu.kanade.domain.extension.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Manga and novel apks are sorted into the Extensions sections by one rule. */
class GetExtensionsByTypeTest {

    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `an installed extension is not offered again`(kind: Extension.Kind) = runTest {
        val extensions = subscribe(
            kind,
            loaded = listOf(loaded("pkg.a", kind)),
            available = listOf(available("pkg.a", "en", kind), available("pkg.b", "en", kind)),
        )

        extensions.available.map { it.name } shouldBe listOf("pkg.b")
    }

    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `an extension carrying a hidden content warning is not offered`(kind: Extension.Kind) = runTest {
        val extensions = subscribe(
            kind,
            available = listOf(available("pkg.a", "en", kind, ContentWarning.NSFW), available("pkg.b", "en", kind)),
        )

        extensions.available.map { it.name } shouldBe listOf("pkg.b")
    }

    /** The language filter is the manga list's own; the Novels chip hides it, so novels keep every language. */
    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `only manga is narrowed to the enabled languages`(kind: Extension.Kind) = runTest {
        val extensions = subscribe(
            kind,
            available = listOf(available("pkg.en", "en", kind), available("pkg.ja", "ja", kind)),
        )

        extensions.available.size shouldBe if (kind == Extension.Kind.MANGA) 1 else 2
    }

    private suspend fun subscribe(
        kind: Extension.Kind,
        loaded: List<Extension.Loaded> = emptyList(),
        available: List<Extension.Available> = emptyList(),
    ) = GetExtensionsByType(
        preferences = mockk<SourcePreferences> {
            every { enabledContentWarnings.get() } returns setOf(ContentWarning.SAFE)
            every { enabledLanguages.changes() } returns flowOf(setOf("en"))
        },
        extensionManager = mockk<ExtensionManager> {
            val isManga = kind == Extension.Kind.MANGA
            every { loadedExtensionsFlow } returns flowOf(if (isManga) loaded else emptyList())
            every { loadedNovelExtensionsFlow } returns flowOf(if (isManga) emptyList() else loaded)
            every { notLoadedExtensionsFlow } returns flowOf(emptyList())
            every { notLoadedNovelExtensionsFlow } returns flowOf(emptyList())
            every { availableExtensionsFlow } returns MutableStateFlow(if (isManga) available else emptyList())
            every { availableNovelExtensionsFlow } returns MutableStateFlow(if (isManga) emptyList() else available)
        },
    ).subscribe(kind).first()

    private fun loaded(pkgName: String, kind: Extension.Kind) = Extension.Loaded(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        kind = kind,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
    )

    private fun available(
        pkgName: String,
        lang: String,
        kind: Extension.Kind,
        contentWarning: ContentWarning = ContentWarning.SAFE,
    ) = Extension.Available(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1",
        versionCode = 1,
        libVersion = 1.4,
        lang = lang,
        contentWarning = contentWarning,
        kind = kind,
        // One source per extension, named after it, so each offered row reads back as its package.
        sources = listOf(
            Extension.Available.Source(id = pkgName.hashCode().toLong(), lang = lang, name = pkgName, baseUrl = ""),
        ),
        apkUrl = "",
        iconUrl = "",
        store = ExtensionStore(
            "",
            "",
            "",
            "",
            ExtensionStore.Contact("", null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )
}
