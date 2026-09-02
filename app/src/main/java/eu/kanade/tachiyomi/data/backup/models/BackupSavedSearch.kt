// RK: saved browse searches and the feed rows built on them. Net-new Reikai files. Both key on the
// serialized SourceKey the tables store, which survives a restore untouched: it names a source rather
// than a row, so unlike a library id it means the same thing on the new install.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupSavedSearch(
    @ProtoNumber(1) var sourceKey: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var query: String? = null,
    @ProtoNumber(4) var filtersJson: String? = null,
)

/**
 * One feed row. Its saved search rides inside it rather than as an id, because ids are the one thing a
 * restore cannot carry: the row is matched to whatever the new install already has by what it holds.
 */
@Serializable
class BackupFeedRow(
    @ProtoNumber(1) var sourceKey: String,
    @ProtoNumber(2) var global: Boolean = true,
    @ProtoNumber(3) var savedSearch: BackupSavedSearch? = null,
)
