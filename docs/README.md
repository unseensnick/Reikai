# Reikai documentation

Three tiers, by audience:

- **User docs** (`docs/*.md`, this folder): what a feature does and how to use it.
- **Dev docs** (`dev/`): how the project is built, how to sync Mihon, how work is recorded. Start at [dev/README.md](dev/README.md).
- **Feature records** (`dev/plans/*.md`): one per substantial feature, the how and the why, indexed in [dev/plans/README.md](dev/plans/README.md).

Three more files at the repo root hold the moving parts: the forward backlog is [ROADMAP.md](../ROADMAP.md), user-facing release notes are [CHANGELOG.md](../CHANGELOG.md), and the terse done-log is [dev/shipped.md](dev/shipped.md).

## Where a feature lives

To change or understand a feature, this is every doc that covers it: the user doc explains it, the dev records hold how and why it was built. If you touch the behavior, check the records too.

| Feature area | User doc | Dev records |
|---|---|---|
| Categories | [categories.md](categories.md) | [novel-categories.md](dev/plans/novel-categories.md), [category-schema-unification.md](dev/plans/category-schema-unification.md), [library-sort-overrides.md](dev/plans/library-sort-overrides.md) |
| Backup & restore | [backup-restore.md](backup-restore.md) | [novel-backup.md](dev/plans/novel-backup.md), [legacy-yokai-import.md](dev/plans/legacy-yokai-import.md); streaming divergence in [upstream-sync.md](dev/upstream-sync.md) |
| Trackers | [tracker-sync.md](tracker-sync.md) | [novel-tracking.md](dev/plans/novel-tracking.md), [tracker-aware-duplicate-detection.md](dev/tracker-aware-duplicate-detection.md) |
| Multi-source & merge | [multi-source.md](multi-source.md) | [merge-system-rebuild.md](dev/plans/merge-system-rebuild.md), [merge-aware-manga-reader.md](dev/plans/merge-aware-manga-reader.md), [merge-component-consolidation.md](dev/plans/merge-component-consolidation.md), [merged-read-state.md](dev/plans/merged-read-state.md) |
| Recommendations | [related-mangas.md](related-mangas.md) | [recommendations.md](dev/plans/recommendations.md) |
| Adult sources | [adult-sources.md](adult-sources.md) | [exh-subsystem.md](dev/plans/exh-subsystem.md), [adult-browse-parity.md](dev/plans/adult-browse-parity.md), [library-tag-search.md](dev/plans/library-tag-search.md) |
| Cloudflare bypass | [flaresolverr.md](flaresolverr.md) | [flaresolverr-integration.md](dev/plans/flaresolverr-integration.md) |
| MangaDex enhanced source | (in [adult-sources.md](adult-sources.md) settings) | [md-enhanced-source.md](dev/plans/md-enhanced-source.md) |
| Light novels | [FAQ.md](FAQ.md) | the `novel-*` records in [plans/](dev/plans/README.md#light-novels), plus [ln-plugin-host.md](dev/ln-plugin-host.md) |
| Library shell | (none yet) | [library-screen-carry.md](dev/plans/library-screen-carry.md), [library-tabbed-shell.md](dev/plans/library-tabbed-shell.md) |
| Unified manga + novel UI | (none yet) | the Unified-surfaces records in [plans/](dev/plans/README.md) |

Areas with no user doc are internal or cross-cutting; their records carry the full picture. When you add a user-facing feature, add its row here.
