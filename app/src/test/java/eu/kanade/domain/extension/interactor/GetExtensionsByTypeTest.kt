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
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.extension.NO_SIGNING_KEY

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

    /** A keyless store's listing can claim only an apk no added store signs, so it never names this update. */
    @ParameterizedTest
    @EnumSource(Extension.Kind::class)
    fun `a pending update takes its version from the store that signs the apk`(kind: Extension.Kind) = runTest {
        val extensions = subscribe(
            kind,
            loaded = listOf(loaded("pkg.a", kind).copy(hasUpdate = true)),
            available = listOf(
                available("pkg.a", "en", kind, versionName = "3", versionCode = 3, store = keyless),
                available("pkg.a", "en", kind, versionName = "2", versionCode = 2),
            ),
            stores = listOf(keyed, keyless),
        )

        extensions.updateVersions shouldBe mapOf("pkg.a" to "2")
    }

    /** The language filter is the manga list's own; the Novels chip hides it, so novels keep every language. */
    @ParameterizedTest
    @CsvSource("MANGA, 1", "TACHIYOMI_NOVEL, 2", "IREADER, 2")
    fun `only manga is narrowed to the enabled languages`(kind: Extension.Kind, shown: Int) = runTest {
        val extensions = subscribe(
            kind,
            available = listOf(available("pkg.en", "en", kind), available("pkg.ja", "ja", kind)),
        )

        extensions.available.size shouldBe shown
    }

    private suspend fun subscribe(
        kind: Extension.Kind,
        loaded: List<Extension.Loaded> = emptyList(),
        available: List<Extension.Available> = emptyList(),
        stores: List<ExtensionStore> = emptyList(),
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
        extensionStoreRepository = mockk<ExtensionStoreRepository> {
            every { getAllAsFlow() } returns flowOf(stores)
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
        // Signed by the store that lists it, so its listing is this apk rather than another store's
        signatures = listOf(KEY),
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
        versionName: String = "1",
        versionCode: Long = 1,
        store: ExtensionStore = keyed,
    ) = Extension.Available(
        name = pkgName,
        pkgName = pkgName,
        versionName = versionName,
        versionCode = versionCode,
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
        store = store,
    )

    private companion object {
        const val KEY = "key"

        val keyed = ExtensionStore(
            "",
            "",
            "",
            KEY,
            ExtensionStore.Contact("", null),
            isLegacy = false,
            extensionListUrl = null,
        )
        val keyless = keyed.copy(indexUrl = "https://keyless.example/index.min.json", signingKey = NO_SIGNING_KEY)
    }
}
