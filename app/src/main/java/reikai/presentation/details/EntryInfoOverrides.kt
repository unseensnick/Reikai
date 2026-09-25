package reikai.presentation.details

/** The fields an edit-info save stores over the source row, each null where the field tracks the source. */
data class EntryInfoOverrides(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long? = null,
    val thumbnailUrl: String? = null,
)

/**
 * One rule for manga and novels: a field is stored only where it differs from [source]; a blank field,
 * a blank tag or the [unknownStatus] stores nothing, so that field tracks the source again.
 */
fun EntryEditInfoUi.overridesOver(source: EntryEditInfoUi, unknownStatus: Long) = EntryInfoOverrides(
    title = title.trim().takeIf { it.isNotEmpty() && it != source.title },
    author = author.trim().takeIf { it.isNotEmpty() && it != source.author },
    artist = artist.trim().takeIf { it.isNotEmpty() && it != source.artist },
    description = description.takeIf { it.isNotBlank() && it != source.description },
    genre = genre.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() && it != source.genre },
    status = status.takeIf { it != source.status && it != unknownStatus },
    thumbnailUrl = thumbnailUrl.trim().takeIf { it.isNotEmpty() && it != source.thumbnailUrl },
)
