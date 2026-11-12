package reikai.source.local

/** Folders macOS, Synology and Windows leave beside files, which hold no chapter and are no series. */
val LOCAL_SYSTEM_FOLDERS = setOf("__MACOSX", "@eaDir", "#recycle", "\$RECYCLE.BIN", "System Volume Information")

/** Whether an entry of the local folder named [name] can be a series: neither hidden nor one a system keeps. */
fun isLocalSeriesName(name: String): Boolean = !name.startsWith('.') && name !in LOCAL_SYSTEM_FOLDERS
