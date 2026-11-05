package tachiyomi.core.metadata.comicinfo

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

const val COMIC_INFO_FILE = "ComicInfo.xml"

// RK --> a local series' language rides in its memo, namespaced so it cannot meet a key an extension keeps there
const val LOCAL_LANGUAGE_MEMO_KEY = "reikai:language"

/** The language a local series' files name, as written there; see LOCAL_LANGUAGE_MEMO_KEY. */
val SManga.localLanguage: String?
    get() = memo[LOCAL_LANGUAGE_MEMO_KEY]?.jsonPrimitive?.contentOrNull

/** Sets [localLanguage], or drops it when [language] is null. */
fun SManga.setLocalLanguage(language: String?) {
    val others = memo - LOCAL_LANGUAGE_MEMO_KEY
    memo = JsonObject(if (language == null) others else others + (LOCAL_LANGUAGE_MEMO_KEY to JsonPrimitive(language)))
}
// RK <--

fun SManga.getComicInfo() = ComicInfo(
    series = ComicInfo.Series(title),
    summary = description?.let { ComicInfo.Summary(it) },
    writer = author?.let { ComicInfo.Writer(it) },
    penciller = artist?.let { ComicInfo.Penciller(it) },
    genre = genre?.let { ComicInfo.Genre(it) },
    publishingStatus = ComicInfo.PublishingStatusTachiyomi(
        ComicInfoPublishingStatus.toComicInfoValue(status.toLong()),
    ),
    title = null,
    number = null,
    web = null,
    translator = null,
    inker = null,
    colorist = null,
    letterer = null,
    coverArtist = null,
    tags = null,
    year = null,
    month = null,
    day = null,
    categories = null,
    source = null,
    languageISO = localLanguage?.let { ComicInfo.LanguageISO(it) }, // RK
)

fun SManga.copyFromComicInfo(comicInfo: ComicInfo) {
    comicInfo.series?.let { title = it.value }
    comicInfo.writer?.let { author = it.value }
    comicInfo.summary?.let { description = it.value }

    listOfNotNull(
        comicInfo.genre?.value,
        comicInfo.tags?.value,
        comicInfo.categories?.value,
    )
        .distinct()
        .joinToString(", ") { it.trim() }
        .takeIf { it.isNotEmpty() }
        ?.let { genre = it }

    listOfNotNull(
        comicInfo.penciller?.value,
        comicInfo.inker?.value,
        comicInfo.colorist?.value,
        comicInfo.letterer?.value,
        comicInfo.coverArtist?.value,
    )
        .flatMap { it.split(", ") }
        .distinct()
        .joinToString(", ") { it.trim() }
        .takeIf { it.isNotEmpty() }
        ?.let { artist = it }

    status = ComicInfoPublishingStatus.toSMangaValue(comicInfo.publishingStatus?.value)
    setLocalLanguage(comicInfo.languageISO?.value?.takeIf { it.isNotBlank() }) // RK
}

// https://anansi-project.github.io/docs/comicinfo/schemas/v2.0
@Suppress("UNUSED")
@Serializable
@XmlSerialName("ComicInfo", "", "")
data class ComicInfo(
    val title: Title?,
    val series: Series?,
    val number: Number?,
    val summary: Summary?,
    val writer: Writer?,
    val penciller: Penciller?,
    val inker: Inker?,
    val colorist: Colorist?,
    val letterer: Letterer?,
    val coverArtist: CoverArtist?,
    val translator: Translator?,
    val genre: Genre?,
    val tags: Tags?,
    val web: Web?,
    val year: Year?,
    val month: Month?,
    val day: Day?,
    val publishingStatus: PublishingStatusTachiyomi?,
    val categories: CategoriesTachiyomi?,
    val source: SourceMihon?,
    val languageISO: LanguageISO? = null, // RK
) {
    @XmlElement(false)
    @XmlSerialName("xmlns:xsd", "", "")
    val xmlSchema: String = "http://www.w3.org/2001/XMLSchema"

    @XmlElement(false)
    @XmlSerialName("xmlns:xsi", "", "")
    val xmlSchemaInstance: String = "http://www.w3.org/2001/XMLSchema-instance"

    @Serializable
    @XmlSerialName("Title", "", "")
    data class Title(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Series", "", "")
    data class Series(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Number", "", "")
    data class Number(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Summary", "", "")
    data class Summary(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Writer", "", "")
    data class Writer(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Penciller", "", "")
    data class Penciller(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Inker", "", "")
    data class Inker(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Colorist", "", "")
    data class Colorist(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Letterer", "", "")
    data class Letterer(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("CoverArtist", "", "")
    data class CoverArtist(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Translator", "", "")
    data class Translator(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Genre", "", "")
    data class Genre(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Tags", "", "")
    data class Tags(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Web", "", "")
    data class Web(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Year", "", "")
    data class Year(@XmlValue(true) val value: Int = -1)

    @Serializable
    @XmlSerialName("Month", "", "")
    data class Month(@XmlValue(true) val value: Int = -1)

    @Serializable
    @XmlSerialName("Day", "", "")
    data class Day(@XmlValue(true) val value: Int = -1)

    // The spec doesn't have a good field for this
    @Serializable
    @XmlSerialName("PublishingStatusTachiyomi", "http://www.w3.org/2001/XMLSchema", "ty")
    data class PublishingStatusTachiyomi(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Categories", "http://www.w3.org/2001/XMLSchema", "ty")
    data class CategoriesTachiyomi(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("SourceMihon", "http://www.w3.org/2001/XMLSchema", "mh")
    data class SourceMihon(@XmlValue(true) val value: String = "")

    // RK -->
    @Serializable
    @XmlSerialName("LanguageISO", "", "")
    data class LanguageISO(@XmlValue(true) val value: String = "")
    // RK <--
}

enum class ComicInfoPublishingStatus(
    val comicInfoValue: String,
    val sMangaModelValue: Int,
) {
    ONGOING("Ongoing", SManga.ONGOING),
    COMPLETED("Completed", SManga.COMPLETED),
    LICENSED("Licensed", SManga.LICENSED),
    PUBLISHING_FINISHED("Publishing finished", SManga.PUBLISHING_FINISHED),
    CANCELLED("Cancelled", SManga.CANCELLED),
    ON_HIATUS("On hiatus", SManga.ON_HIATUS),
    UNKNOWN("Unknown", SManga.UNKNOWN),
    ;

    companion object {
        fun toComicInfoValue(value: Long): String {
            return entries.firstOrNull { it.sMangaModelValue == value.toInt() }?.comicInfoValue
                ?: UNKNOWN.comicInfoValue
        }

        fun toSMangaValue(value: String?): Int {
            return entries.firstOrNull { it.comicInfoValue == value }?.sMangaModelValue
                ?: UNKNOWN.sMangaModelValue
        }
    }
}
