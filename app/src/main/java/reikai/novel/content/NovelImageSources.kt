package reikai.novel.content

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Which image a chapter's picture and srcset markup stand for, shared by the text reader and the offline copy. */
object NovelImageSources {

    private val whitespace = Regex("\\s+")
    private val widthDescriptor = Regex("^(\\d+)w$")

    // The formats the text reader can decode, so a picture never collapses onto one it cannot show.
    private val decodableImageTypes = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp",
        "image/gif",
        "image/bmp",
    )

    private fun Element.isDecodableSource(): Boolean {
        val type = attr("type").trim().lowercase()
        return type.isEmpty() || type in decodableImageTypes
    }

    /** Collapses each picture to its img, which takes the first decodable source's srcset if it has none. */
    fun unwrapPictures(doc: Document) {
        doc.select("picture").forEach { picture ->
            val sources = picture.select("source")
            val fallbackSrcset = sources.firstOrNull { it.isDecodableSource() && it.hasAttr("srcset") }
                ?.attr("srcset")
            val img = picture.selectFirst("img")
            if (img == null) {
                if (fallbackSrcset != null) picture.appendElement("img").attr("srcset", fallbackSrcset)
            } else if (!img.hasAttr("srcset") && fallbackSrcset != null) {
                img.attr("srcset", fallbackSrcset)
            }
            sources.remove()
            picture.unwrap()
        }
    }

    /** [img]'s srcset candidate for [targetWidth]: the narrowest at least that wide, else the widest. */
    fun srcsetCandidate(img: Element, targetWidth: Int): String? {
        val srcset = img.attr("srcset").takeIf { it.isNotBlank() } ?: return null
        val candidates = srcset.split(',').mapNotNull { entry ->
            val parts = entry.trim().split(whitespace, limit = 2)
            val url = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val width = parts.getOrNull(1)?.trim()
                ?.let { widthDescriptor.find(it) }
                ?.groupValues?.get(1)?.toIntOrNull()
            url to width
        }
        if (candidates.isEmpty()) return null
        val withWidth = candidates.filter { it.second != null }
        val best = withWidth.filter { it.second!! >= targetWidth }.minByOrNull { it.second!! }
            ?: withWidth.maxByOrNull { it.second!! }
            ?: candidates.first()
        return best.first
    }
}
