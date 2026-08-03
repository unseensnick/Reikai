package reikai.presentation.migrate.flow

/**
 * Marker for the flow's intermediate Voyager screens (entry pick, source config, favorites,
 * search, list). A finished migration unwinds with `popUntil { it !is MigrationFlowScreen }`,
 * landing back on whatever launched the flow: a single pop would land on the screen below,
 * which is a stale step of a flow that already completed.
 */
interface MigrationFlowScreen
