# Reikai documentation

Three tiers, by audience:

- **User docs** (`docs/*.md`, plus `docs/guides/` and `docs/faq/`): what a feature does and how to use it. The two folders hold guides adapted from Mihon's site, covering the reading experience Reikai inherits; [guides/PORTING.md](guides/PORTING.md) is their record and is not itself a user doc. All of it publishes to the docs site.
- **Dev docs** (`dev/`): how the project is built, how to sync Mihon, how work is recorded. Start at [dev/README.md](dev/README.md).
- **Feature records** (`dev/plans/*.md`): one per substantial feature, the how and the why, indexed in [dev/plans/README.md](dev/plans/README.md).

Three more files hold the moving parts. At the repo root, the forward backlog is [ROADMAP.md](../ROADMAP.md) and user-facing release notes are [CHANGELOG.md](../CHANGELOG.md), which with the release tags is the record of what shipped; under `dev/`, what was considered and set aside is [dev/parked.md](dev/parked.md).

## Where a feature lives

To change or understand a feature, this is every doc that covers it: the user doc explains it, the dev records hold how and why it was built. If you touch the behavior, check the records too.

| Feature area | User doc | Dev records |
|---|---|---|
| Categories | [guides/categories.md](guides/categories.md) | [library.md](dev/subsystems/library.md) |
| Upgrading and moving from other apps | [before-you-upgrade.md](before-you-upgrade.md) | [data-and-backup.md](dev/subsystems/data-and-backup.md) |
| Backup & restore | [guides/backups.md](guides/backups.md) | [data-and-backup.md](dev/subsystems/data-and-backup.md); streaming divergence in [upstream-sync.md](dev/upstream-sync.md) |
| Trackers | [guides/tracking.md](guides/tracking.md) | [tracking.md](dev/subsystems/tracking.md), [tracker-aware-duplicate-detection.md](dev/tracker-aware-duplicate-detection.md) |
| Merged series | [multi-source.md](multi-source.md) | [merged-series.md](dev/subsystems/merged-series.md) |
| Recommendations | [related-mangas.md](related-mangas.md) | [recommendations.md](dev/subsystems/recommendations.md) |
| Adult sources | [adult-sources.md](adult-sources.md) | [adult-sources.md](dev/subsystems/adult-sources.md), [library.md](dev/subsystems/library.md) |
| Library search | [library-search.md](library-search.md) | [library.md](dev/subsystems/library.md) |
| Cloudflare bypass | [flaresolverr.md](flaresolverr.md) | [cloudflare.md](dev/subsystems/cloudflare.md) |
| Built-in sources | [built-in-sources.md](built-in-sources.md) | [adult-sources.md](dev/subsystems/adult-sources.md) |
| MangaDex enhanced source | [built-in-sources.md](built-in-sources.md) | [adult-sources.md](dev/subsystems/adult-sources.md) |
| Light novels | [about.md](about.md) | the `novel-*` records in [plans/](dev/plans/README.md#light-novels), plus [ln-plugin-host.md](dev/subsystems/ln-plugin-host.md) |
| Novel reader | [novel-reader.md](novel-reader.md), [guides/novel-reader-settings.md](guides/novel-reader-settings.md) | [reader.md](dev/subsystems/reader.md), [novel-reader-rendering.md](dev/subsystems/novel-reader-rendering.md) |
| Updates, History and Recents | [recents.md](recents.md) | [recents.md](dev/subsystems/recents.md) |
| Feed and saved searches | [feed.md](feed.md) | [browse-and-sources.md](dev/subsystems/browse-and-sources.md) |
| Opening shared links | [shared-links.md](shared-links.md) | none |
| Library shell | [library-layout.md](library-layout.md) | [library.md](dev/subsystems/library.md) |
| Unified manga + novel UI | (none yet) | the Unified-surfaces records in [plans/](dev/plans/README.md) |

Areas with no user doc are internal or cross-cutting; their records carry the full picture. When you add a user-facing feature, add its row here.
