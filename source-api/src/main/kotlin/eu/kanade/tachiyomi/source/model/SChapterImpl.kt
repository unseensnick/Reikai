@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    override var date_upload: Long = 0

    // RK: the same value as upstream's JsonObject.EMPTY, spelled out since the TachiyomiX 1.6 sync
    override var memo: JsonObject = JsonObject(emptyMap())
}
