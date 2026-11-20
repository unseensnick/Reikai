package reikai.presentation.details

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LooseFiles
import reikai.domain.library.ContentType

/**
 * The details list as the page shows it, built with the rest of the neutral state off the main thread, since
 * each is a pass over every chapter row and the rows change on every download tick. [items] is what the list
 * shows (only the copies while that filter is on, under their volume headers), null showing every row.
 * [structural] is the volumes as the files hold them, which the dialogs that change files act on; [volumes] is
 * them as the series shows them, under its Loose files setting.
 */
@Immutable
data class DetailsVolumeViews(
    val onlyCopies: Boolean = false,
    val items: List<EntryChapterListItem>? = null,
    val structural: VolumeView? = null,
    val volumes: VolumeView? = null,
)

/** What the volume views read beside a details state, with the Only copies switch the page saves and hands over. */
class DetailsVolumeInputs(private val picking: VolumePicking, private val type: ContentType) {

    data class Values(
        val onlyCopies: Boolean,
        val picks: Map<Long, String>,
        val saved: Map<String, List<LocalVolume>>,
        val loose: Map<String, LooseFiles>,
    )

    private val onlyCopies = MutableStateFlow(false)

    fun setOnlyCopies(on: Boolean) {
        onlyCopies.value = on
    }

    val values: Flow<Values> =
        combine(onlyCopies, picking.picks(type), picking.saved(type), picking.looseFiles(), ::Values)
}

/** [this] with its [DetailsVolumeViews] built from [inputs]; the rows' own state is left as it is. */
fun EntryDetailsScreenState.withVolumeViews(inputs: DetailsVolumeInputs.Values): EntryDetailsScreenState {
    if (this !is EntryDetailsScreenState.Loaded) return this
    val copiesOn = inputs.onlyCopies && !chapters.duplicates?.copies.isNullOrEmpty()
    val rows = if (copiesOn) withOnlyCopies().chapters.items else chapters.items
    val picked = inputs.picks[entryId.rawId]
    val structural = rows.inVolumes(picked, inputs.saved, chapters.volumes)
    val volumes = rows.inVolumes(picked, inputs.saved, chapters.volumes, inputs.loose)
    return copy(
        volumeViews = DetailsVolumeViews(
            onlyCopies = copiesOn,
            items = volumes?.items ?: rows.takeIf { copiesOn },
            structural = structural,
            volumes = volumes,
        ),
    )
}
