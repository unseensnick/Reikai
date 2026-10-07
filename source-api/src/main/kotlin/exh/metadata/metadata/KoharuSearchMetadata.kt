package exh.metadata.metadata

import kotlinx.serialization.Serializable

@Serializable
class KoharuSearchMetadata : GallerySiteSearchMetadata() {
    companion object {
        const val MAGAZINE_NAMESPACE = "magazine"
        const val COSPLAYER_NAMESPACE = "cosplayer"
        const val UPLOADER_NAMESPACE = "uploader"
        const val MALE_NAMESPACE = "male"
        const val FEMALE_NAMESPACE = "female"
        const val MIXED_NAMESPACE = "mixed"
        const val OTHER_NAMESPACE = "other"
    }
}
