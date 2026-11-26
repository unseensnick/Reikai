package reikai.data.local

import com.hippo.unifile.UniFile
import reikai.domain.library.StoredType
import tachiyomi.domain.storage.service.StorageManager

/** The local folder [type]'s series live in, each one entry of it; null while no storage folder is picked. */
fun StorageManager.localRoot(type: StoredType): UniFile? = when (type) {
    StoredType.NOVELS -> getLocalNovelsDirectory()
    else -> getLocalSourceDirectory()
}
