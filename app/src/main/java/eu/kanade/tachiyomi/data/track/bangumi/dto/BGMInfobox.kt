package eu.kanade.tachiyomi.data.track.bangumi.dto

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// RK: Bangumi subject "infobox" is a list whose `value` is either a string or a nested list, so it
// needs a polymorphic deserializer. Used by "Fill from tracker" to read author/illustrator keys.
// Infobox deserializer courtesy of komf (Snd-R/komf, BangumiSubject.kt).
object InfoBoxSerializer : JsonContentPolymorphicSerializer<Infobox>(Infobox::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Infobox> {
        if (element !is JsonObject) throw SerializationException("Expected JsonObject got ${element::class}")
        return when (element["value"]) {
            is JsonArray -> Infobox.MultipleValues.serializer()
            is JsonPrimitive -> Infobox.SingleValue.serializer()
            else -> throw SerializationException("Unexpected infobox value type")
        }
    }
}

@Serializable(with = InfoBoxSerializer::class)
sealed interface Infobox {
    val key: String

    @Serializable
    class SingleValue(
        override val key: String,
        val value: String,
    ) : Infobox

    @Serializable
    class MultipleValues(
        override val key: String,
        val value: List<InfoboxNestedValue>,
    ) : Infobox
}

@Serializable
data class InfoboxNestedValue(
    @SerialName("k")
    val key: String? = null,
    @SerialName("v")
    val value: String,
)
