package reikai.novel.content

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Which image a chapter's picture and srcset markup stand for, shared by the text reader and the offline copy. */
object NovelImageSources {

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
        val candidates = parseSrcset(srcset).map { (url, descriptor) ->
            url to widthDescriptor.find(descriptor)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (candidates.isEmpty()) return null
        val withWidth = candidates.filter { it.second != null }
        val best = withWidth.filter { it.second!! >= targetWidth }.minByOrNull { it.second!! }
            ?: withWidth.maxByOrNull { it.second!! }
            ?: candidates.first()
        return best.first
    }

    /**
     * A srcset's candidates as (address, descriptor), split as the HTML spec splits them: an address
     * runs to whitespace, so a comma inside one (common in image CDN addresses) stays in it.
     */
    fun parseSrcset(srcset: String): List<Pair<String, String>> {
        val candidates = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < srcset.length) {
            while (i < srcset.length && (srcset[i].isWhitespace() || srcset[i] == ',')) i++
            val start = i
            while (i < srcset.length && !srcset[i].isWhitespace()) i++
            val raw = srcset.substring(start, i)
            // An address that ends in a comma ends its candidate there, with no descriptor.
            if (raw.endsWith(',')) {
                raw.trimEnd(',').takeIf { it.isNotEmpty() }?.let { candidates += it to "" }
                continue
            }
            val descriptorStart = i
            var depth = 0
            while (i < srcset.length) {
                val c = srcset[i]
                if (c == ',' && depth == 0) break
                if (c == '(') {
                    depth++
                } else if (c == ')' && depth > 0) {
                    depth--
                }
                i++
            }
            if (raw.isNotEmpty()) candidates += raw to srcset.substring(descriptorStart, i).trim()
        }
        return candidates
    }
}
