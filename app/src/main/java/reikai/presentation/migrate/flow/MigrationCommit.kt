package reikai.presentation.migrate.flow

/**
 * Commit one entry onto one target: resolve, then migrate, then hand back the resolved target so the
 * caller can record what it actually migrated onto.
 *
 * The batch list, the per-row action and the single-entry dialog all run this, so the resolve step,
 * the fetch-once handoff and the failure contract cannot drift between them. Throws on failure, and
 * lets cancellation through: every caller has to decide for itself whether a half-applied row shows
 * as failed, since the engines are not transactional.
 */
internal suspend fun MigrationFlowAdapter.commitMigration(
    entry: MigrationEntry,
    target: MigrationCandidate,
    replace: Boolean,
    flags: Set<MigrationDataFlag>,
): MigrationCandidate {
    val outcome = resolve(target) ?: error("target failed to resolve")
    migrate(entry, outcome.candidate, replace, flags, targetJustSynced = outcome.syncedNow)
    return outcome.candidate
}
