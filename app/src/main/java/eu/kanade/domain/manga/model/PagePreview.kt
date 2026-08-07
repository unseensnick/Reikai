package eu.kanade.domain.manga.model

import eu.kanade.tachiyomi.source.PagePreviewInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PagePreview(
    val index: Int,
    val imageUrl: String,
    val source: Long,
    // The PagePreviewCache entry this preview came out of, so a dead image URL can drop the whole
    // cached page list. Gallery hosts hand out time-limited URLs, and a cached list that outlives
    // them keeps serving 404s with nothing to invalidate it.
    val pageListKey: String,
) {
    @Transient
    private val _progress: MutableStateFlow<Int> = MutableStateFlow(-1)

    @Transient
    val progress = _progress.asStateFlow()

    fun getPagePreviewInfo() = PagePreviewInfo(index, imageUrl, _progress)
}
