package reikai.domain.source

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.Preference

/**
 * Incognito per source, pinned once for both content types. The Sources sheet stores whatever
 * `incognitoKey` answers and every reader asks `await` or `subscribe`, so a key written one way and
 * read another would leave the switch silently doing nothing for that type.
 */
class SourceIncognitoConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    fun `a source stored under its incognito key reads as incognito`(source: SourceKey) = runTest {
        val key = incognitoState().incognitoKey(source)!!

        incognitoState(stored = setOf(key)).await(source) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    fun `the incognito banner follows the stored key too`(source: SourceKey) = runTest {
        val key = incognitoState().incognitoKey(source)!!

        incognitoState(stored = setOf(key)).subscribe(source).first() shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    fun `another source's key leaves this one out of incognito`(source: SourceKey) = runTest {
        incognitoState(stored = setOf(OTHER_PACKAGE, SourceKey.Novel("other").serialize())).await(source) shouldBe false
    }

    /** A novel apk's sources go with the apk, as a manga extension's do: switching one switches all. */
    @Test
    fun `a novel apk's other source is incognito with it`() = runTest {
        val key = incognitoState().incognitoKey(SourceKey.Novel("tachiyomi:$NOVEL_APK_SOURCE_ID"))!!

        incognitoState(stored = setOf(key)).await(SourceKey.Novel("tachiyomi:$NOVEL_APK_OTHER_SOURCE_ID")) shouldBe true
    }

    @Test
    fun `an IReader app's source is incognito by its app, not as a plugin`() = runTest {
        incognitoState().incognitoKey(SourceKey.Novel("ireader:$NOVEL_APK_SOURCE_ID")) shouldBe IREADER_PACKAGE
    }

    // The preferences are stubbed rather than in-memory: that store's changes() never emits, and building
    // BasePreferences reaches Android's installer checks, which the JVM does not have.
    private fun incognitoState(stored: Set<String> = emptySet()): GetIncognitoState {
        val extensionManager = mockk<ExtensionManager> {
            coEvery { getExtensionPackage(MANGA_SOURCE_ID) } returns MANGA_PACKAGE
            every { getExtensionPackageAsFlow(MANGA_SOURCE_ID) } returns flowOf(MANGA_PACKAGE)
            every { getNovelExtensionPackageAsFlow("tachiyomi:$NOVEL_APK_SOURCE_ID") } returns flowOf(NOVEL_APK_PACKAGE)
            every { getNovelExtensionPackageAsFlow("tachiyomi:$NOVEL_APK_OTHER_SOURCE_ID") } returns
                flowOf(NOVEL_APK_PACKAGE)
            every { getNovelExtensionPackageAsFlow("ireader:$NOVEL_APK_SOURCE_ID") } returns flowOf(IREADER_PACKAGE)
        }
        val basePreferences = mockk<BasePreferences> { every { incognitoMode } returns preference(false) }
        val sourcePreferences = mockk<SourcePreferences> { every { incognitoExtensions } returns preference(stored) }
        return GetIncognitoState(basePreferences, sourcePreferences, extensionManager)
    }

    private fun <T> preference(value: T) = mockk<Preference<T>> {
        every { get() } returns value
        every { changes() } returns flowOf(value)
    }

    companion object {
        private const val MANGA_SOURCE_ID = 42L
        private const val MANGA_PACKAGE = "eu.kanade.tachiyomi.extension.en.example"
        private const val OTHER_PACKAGE = "eu.kanade.tachiyomi.extension.en.other"
        private const val NOVEL_APK_SOURCE_ID = 7L
        private const val IREADER_PACKAGE = "ireader.freewebnovel.en"
        private const val NOVEL_APK_OTHER_SOURCE_ID = 8L
        private const val NOVEL_APK_PACKAGE = "eu.kanade.tachiyomi.novelextension.en.example"

        @JvmStatic
        fun sources() = listOf(
            SourceKey.Manga(MANGA_SOURCE_ID),
            SourceKey.Novel("example"),
            SourceKey.Novel("tachiyomi:$NOVEL_APK_SOURCE_ID"),
        )
    }
}
