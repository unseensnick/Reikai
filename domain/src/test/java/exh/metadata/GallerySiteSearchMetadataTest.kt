package exh.metadata

import exh.metadata.metadata.AsmHentaiSearchMetadata
import exh.metadata.metadata.GallerySiteSearchMetadata
import exh.metadata.metadata.HentaiFoxSearchMetadata
import exh.metadata.metadata.KoharuSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import exh.metadata.sql.models.SearchMetadata
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.reflect.KClass

/** A gallery site's stored metadata row keeps decoding into its class, since rows outlive the build. */
class GallerySiteSearchMetadataTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("classes")
    fun `a stored row raises with its thumbnail`(name: String, clazz: KClass<out GallerySiteSearchMetadata>) {
        val extra = """{"type":"exh.metadata.metadata.$name","thumbnailUrl":"https://t.example/1.jpg"}"""
        val flat = FlatMetadata(SearchMetadata(7L, null, extra, null, 0), emptyList(), emptyList())

        flat.raise(clazz).thumbnailUrl shouldBe "https://t.example/1.jpg"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("classes")
    fun `a fresh row stores the same blob`(name: String, clazz: KClass<out GallerySiteSearchMetadata>) {
        val meta = clazz.java.getDeclaredConstructor().newInstance().apply {
            mangaId = 7L
            thumbnailUrl = "https://t.example/1.jpg"
        }

        meta.flatten().metadata.extra shouldBe
            """{"type":"exh.metadata.metadata.$name","thumbnailUrl":"https://t.example/1.jpg"}"""
    }

    companion object {
        @JvmStatic
        fun classes() = listOf(
            Arguments.of("AsmHentaiSearchMetadata", AsmHentaiSearchMetadata::class),
            Arguments.of("HentaiFoxSearchMetadata", HentaiFoxSearchMetadata::class),
            Arguments.of("KoharuSearchMetadata", KoharuSearchMetadata::class),
        )
    }
}
