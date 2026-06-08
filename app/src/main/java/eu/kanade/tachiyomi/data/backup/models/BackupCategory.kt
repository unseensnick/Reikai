package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.category.isHidden
import tachiyomi.domain.category.model.Category

@Serializable
class BackupCategory(
    @ProtoNumber(1) var name: String,
    @ProtoNumber(2) var order: Long = 0,
    @ProtoNumber(3) var id: Long = 0,
    // @ProtoNumber(3) val updateInterval: Int = 0, 1.x value not used in 0.x
    @ProtoNumber(100) var flags: Long = 0,
    // RK: Komikku's hidden field (it stores hidden in a column; Reikai stores it as a flags bit).
    // Carrying it lets the hidden state round-trip between the two apps. ProtoNumber matches Komikku.
    @ProtoNumber(900) var hidden: Boolean = false,
) {
    // RK: fold a Komikku backup's separate `hidden` into our flags bit; idempotent for Reikai backups
    // (whose bit is already set in flags).
    private val flagsWithHidden: Long
        get() = if (hidden) flags or CATEGORY_HIDDEN_MASK else flags

    fun toCategory(id: Long) = Category(
        id = id,
        name = this@BackupCategory.name,
        // RK: was `flags`
        flags = this@BackupCategory.flagsWithHidden,
        order = this@BackupCategory.order,
    )

    // RK: the flags to persist on restore, with the hidden bit merged in. See [flagsWithHidden].
    fun flagsForRestore(): Long = flagsWithHidden
}

val backupCategoryMapper = { category: Category ->
    BackupCategory(
        id = category.id,
        name = category.name,
        order = category.order,
        flags = category.flags,
        // RK: emit the hidden state so Komikku (and a future Reikai restore) can read it
        hidden = category.isHidden,
    )
}
