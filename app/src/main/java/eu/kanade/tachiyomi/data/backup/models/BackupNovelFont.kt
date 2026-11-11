package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * A reader font the user added, carried whole at root field 721 so the font setting a restore brings back
 * finds its file. Only TTF and OTF files the user imported or downloaded, each under a size cap.
 */
@Serializable
class BackupNovelFont(
    @ProtoNumber(1) var fileName: String,
    @ProtoNumber(2) var bytes: ByteArray,
)
