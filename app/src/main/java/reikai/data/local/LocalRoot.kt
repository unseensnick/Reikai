package reikai.data.local

import com.hippo.unifile.UniFile
import reikai.domain.library.ContentType
import tachiyomi.domain.storage.service.StorageManager

/** The local folder [type]'s series live in, each one entry of it; null while no storage folder is picked. */
fun StorageManager.localRoot(type: ContentType): UniFile? = when (type) {
    ContentType.NOVELS -> getLocalNovelsDirectory()
    else -> getLocalSourceDirectory()
}
