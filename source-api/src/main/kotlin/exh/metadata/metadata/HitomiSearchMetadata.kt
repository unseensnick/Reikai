package exh.metadata.metadata

import android.content.Context
import kotlinx.serialization.Serializable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
class HitomiSearchMetadata : GallerySiteSearchMetadata() {
    var hlId: Int? = null

    // Not `type`: the stored row already uses that key for the metadata class.
    var galleryType: String? = null

    var language: String? = null

    var uploadDate: Long? = null

    /** The gallery's files in reading order; a preview is built from each one's hash. */
    var images: List<Image> = emptyList()

    @Serializable
    class Image(val hash: String, val isGif: Boolean)

    override fun getExtraInfoPairs(context: Context): List<Pair<String, String>> {
        return with(context) {
            listOfNotNull(
                getItem(hlId) { stringResource(MR.strings.id) },
                getItem(galleryType) { stringResource(MR.strings.genre) },
                getItem(language) { stringResource(MR.strings.language) },
                getItem(
                    uploadDate,
                    {
                        DateTimeFormatter.ISO_LOCAL_DATE.withZone(
                            ZoneId.systemDefault(),
                        ).format(Instant.ofEpochMilli(it))
                    },
                ) {
                    stringResource(MR.strings.date_posted)
                },
                getItem(images.size.takeIf { it > 0 }) { stringResource(MR.strings.page_count) },
            ) + super.getExtraInfoPairs(context)
        }
    }
}
