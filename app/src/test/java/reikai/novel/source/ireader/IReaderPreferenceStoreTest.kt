package reikai.novel.source.ireader

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class IReaderPreferenceStoreTest {

    @Serializable
    private data class Filter(val sort: String)

    private val store = InMemoryPreferenceStore(
        sequenceOf(InMemoryPreference("ireader_storage::ireader.a::lang", "en", "")),
    )

    @Test
    fun `an extension reads what is stored under its own package`() {
        IReaderPreferenceStore(store, "ireader.a").getString("lang", "").get() shouldBe "en"
    }

    @Test
    fun `another extension does not see it`() {
        IReaderPreferenceStore(store, "ireader.b").getString("lang", "none").get() shouldBe "none"
    }

    @Test
    fun `the extension sees the key it asked for`() {
        IReaderPreferenceStore(store, "ireader.a").getString("lang", "").key() shouldBe "lang"
    }

    private val encoder = slot<(Filter) -> String>()

    /** Answers only under the extension's own key, so a setting read under another is no answer at all. */
    private val raw = mockk<PreferenceStore> {
        every {
            getObjectFromString(
                "ireader_storage::ireader.a::filter",
                any(),
                capture(encoder),
                any<
                    (
                        String,
                    ) -> Filter,
                    >(),
            )
        } answers {
            InMemoryPreference(firstArg(), arg<(String) -> Filter>(3)("""{"sort":"new"}"""), secondArg())
        }
    }

    private fun filter() =
        IReaderPreferenceStore(
            raw,
            "ireader.a",
        ).getJsonObject("filter", Filter("old"), Filter.serializer(), EmptySerializersModule())

    @Test
    fun `a JSON setting decodes through the extension's serializer, under its own package`() {
        filter().get() shouldBe Filter("new")
    }

    @Test
    fun `a JSON setting encodes through the extension's serializer`() {
        filter()

        encoder.captured(Filter("top")) shouldBe """{"sort":"top"}"""
    }
}
