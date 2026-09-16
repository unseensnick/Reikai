package mihon.app.di.injekt

import android.app.Application
import android.content.Context
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.eh.EHentaiUpdateHelper
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.InjektionException
import uy.kohesive.injekt.api.fullType
import java.lang.reflect.Type

/**
 * The registrar is the whole Injekt surface: a closed allow-list, so a type it omits throws at
 * runtime where it used to resolve, and nothing outside the app's own code can be compile-checked
 * (installed extensions resolve against `source-api`).
 *
 * The list below is every type with a reader in the tree. Resolution goes through `fullType`, the
 * same reified path production uses, because the map is keyed on `Type` and matched exactly: a key
 * that did not equal what `fullType` produces would fail only on a device.
 */
class MetroInjektRegistrarTest {

    private val graph = mockk<AppGraph>(relaxed = true)

    private val scope = InjektScope(
        MetroInjektRegistrar(
            application = mockk<Application>(relaxed = true),
            graphProvider = mockk<GraphProvider<AppGraph>>().also { every { it.graph } returns graph },
        ),
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("typesWithALiveReader")
    fun `a type with a live reader resolves`(type: Type) {
        scope.getInstance<Any>(type) shouldNotBe null
    }

    @Test
    fun `a type outside the allow-list is refused rather than returning null`() {
        shouldThrow<InjektionException> { scope.getInstance<Any>(fullType<XmlSerialName>().type) }
    }

    @Test
    fun `the registrar refuses to be written to`() {
        shouldThrow<UnsupportedOperationException> { scope.addSingleton(fullType<Json>(), Json) }
    }

    companion object {

        @JvmStatic
        fun typesWithALiveReader(): List<Type> = listOf(
            // Upstream's own, most with no in-tree reader: installed extensions resolve these.
            fullType<Application>().type,
            fullType<Context>().type,
            fullType<Json>().type,
            fullType<ProtoBuf>().type,
            fullType<XML>().type,
            fullType<NetworkHelper>().type,
            fullType<JavaScriptEngine>().type,

            // Reikai's, read by the built-in MangaDex and E-Hentai sources and by source-api.
            fullType<TrackerManager>().type,
            fullType<TrackPreferences>().type,
            fullType<DelegateSourcePreferences>().type,
            fullType<ExhPreferences>().type,
            fullType<EHentaiUpdateHelper>().type,

            // source-api's MetadataSource contract, which installed extensions implement.
            fullType<MetadataSource.GetMangaId>().type,
            fullType<MetadataSource.GetFlatMetadataById>().type,
            fullType<MetadataSource.InsertFlatMetadata>().type,
        )
    }
}
