package tachiyomi.domain.source.model

import reikai.domain.source.sourceVisualName

data class Source(
    val id: Long,
    val lang: String,
    val name: String,
    val supportsLatest: Boolean,
    val isStub: Boolean,
    val pin: Pins = Pins.unpinned,
    val isUsedLast: Boolean = false,
) {

    val visualName: String
        get() = sourceVisualName(name, lang) // RK: one rule with the novel rows of Clear database

    val key: () -> String = {
        when {
            isUsedLast -> "$id-lastused"
            else -> "$id"
        }
    }
}
