package reikai.source.local

import com.hippo.unifile.UniFile

/** A picture a local volume folder names as its cover, which its Extras chapter leaves out of its pages. */
fun isVolumeCover(file: UniFile): Boolean = file.name.orEmpty().substringBeforeLast('.').lowercase() in COVER_NAMES

private val COVER_NAMES = setOf("cover", "folder")
