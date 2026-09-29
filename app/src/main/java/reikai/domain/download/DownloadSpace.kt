package reikai.domain.download

// Mihon's floor. A device this full starts failing the database as well as the chapter write.
private const val MIN_DOWNLOAD_SPACE = 200L * 1024 * 1024

/**
 * Whether a volume with [availableBytes] free has room to start another chapter download, the check
 * both downloaders make before fetching. -1 means the space could not be read, which Mihon lets through.
 */
fun hasRoomToDownload(availableBytes: Long): Boolean = hasRoomToCopy(availableBytes, bytes = 0L)

/** Whether writing [bytes] more to a volume with [availableBytes] free leaves it above the same floor. */
fun hasRoomToCopy(availableBytes: Long, bytes: Long): Boolean =
    availableBytes == -1L || availableBytes - bytes >= MIN_DOWNLOAD_SPACE
