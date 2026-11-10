package reikai.source.local

import com.hippo.unifile.UniFile

/** A picture a local volume folder names as its cover, which its Extras chapter leaves out of its pages. */
fun isVolumeCover(file: UniFile): Boolean = file.name.orEmpty().substringBeforeLast('.').lowercase() in COVER_NAMES

/**
 * The pages of a volume folder's Extras chapter out of its [pictures]: every one but those named as a cover,
 * and none when its only picture is unnamed, since that one is the cover. The listing and the reader both
 * read it, so an Extras chapter is listed only when it opens with pages.
 */
fun volumeExtras(pictures: List<UniFile>): List<UniFile> {
    val extras = pictures.filterNot(::isVolumeCover)
    return if (pictures.size == 1) emptyList() else extras
}

private val COVER_NAMES = setOf("cover", "folder")
