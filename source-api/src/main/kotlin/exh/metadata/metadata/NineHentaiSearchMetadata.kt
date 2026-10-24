package exh.metadata.metadata

import android.content.Context
import kotlinx.serialization.Serializable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
class NineHentaiSearchMetadata : GallerySiteSearchMetadata() {
    var nhId: Int? = null

    var altTitle by titleDelegate(TITLE_TYPE_ALT)

    /** Approximate: the site only says how long ago the gallery was uploaded. */
    var uploadDate: Long? = null

    var pageCount: Int? = null

    var favorites: Int? = null

    var views: Int? = null

    var downloads: Int? = null

    override fun getExtraInfoPairs(context: Context): List<Pair<String, String>> {
        return with(context) {
            listOfNotNull(
                getItem(nhId) { stringResource(MR.strings.id) },
                getItem(altTitle) { stringResource(MR.strings.alt_title) },
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
                getItem(pageCount) { stringResource(MR.strings.page_count) },
                getItem(favorites) { stringResource(MR.strings.total_favorites) },
                getItem(views) { stringResource(MR.strings.total_views) },
                getItem(downloads) { stringResource(MR.strings.total_downloads) },
            ) + super.getExtraInfoPairs(context)
        }
    }

    companion object {
        // The main title is the base class's type 0.
        private const val TITLE_TYPE_ALT = 1
    }
}
