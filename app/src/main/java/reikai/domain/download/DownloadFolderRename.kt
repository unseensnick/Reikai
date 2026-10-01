package reikai.domain.download

import com.hippo.unifile.UniFile
import logcat.logcat

/**
 * Whether a title change may move an entry's download [folder] to [newName], for both types. Folders are named by
 * title within a source, so a folder another entry there is named onto ([otherEntryFolders]) holds its downloads
 * too, and a name another folder holds belongs to that title, which storage would dodge with a "name (1)" no entry
 * is named onto. A change of letter case alone passes, since a case-blind disk finds the folder itself at its new name.
 */
fun movesDownloadFolder(folder: UniFile, newName: String, otherEntryFolders: Collection<String>): Boolean {
    val name = folder.name ?: return false
    val shared = otherEntryFolders.any { it.equals(name, ignoreCase = true) }
    val taken = !name.equals(newName, ignoreCase = true) && folder.parentFile?.findFile(newName) != null
    if (shared) logcat(TAG) { "Download folder $name stays: another entry on its source is named onto it" }
    if (taken) logcat(TAG) { "Download folder $name stays: $newName is already taken" }
    return !shared && !taken
}

private const val TAG = "DownloadFolderRename"
