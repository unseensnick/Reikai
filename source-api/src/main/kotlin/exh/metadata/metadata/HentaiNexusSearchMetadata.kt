package exh.metadata.metadata

import android.content.Context
import kotlinx.serialization.Serializable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
class HentaiNexusSearchMetadata : GallerySiteSearchMetadata() {
    var hnId: Int? = null

    var publishedDate: Long? = null

    var pageCount: Int? = null

    var favorites: Int? = null

    var pagePreviewUrls: List<String> = emptyList()

    override fun getExtraInfoPairs(context: Context): List<Pair<String, String>> {
        return with(context) {
            listOfNotNull(
                getItem(hnId) { stringResource(MR.strings.id) },
                getItem(publishedDate, { DATE_FORMAT.format(Instant.ofEpochMilli(it)) }) {
                    stringResource(MR.strings.date_posted)
                },
                getItem(pageCount) { stringResource(MR.strings.page_count) },
                getItem(favorites) { stringResource(MR.strings.total_favorites) },
            ) + super.getExtraInfoPairs(context)
        }
    }
}

// The site publishes a calendar day, so it is stored and shown as UTC midnight.
private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
