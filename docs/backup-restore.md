# Backup & restore

How to back up your library, tracking, and settings — and how Reikai's backup format relates to upstream Yōkai.

## Creating a backup

*Settings → Data and storage → Create backup.*

Pick what to include (library, categories, chapters, tracking, history, settings), tap **Create**, and save the resulting `.tachibk` file somewhere safe — cloud sync, SD card, external storage.

Backups don't include downloaded chapter files. To preserve downloads, copy the app's downloads folder separately.

## Restoring a backup

*Settings → Data and storage → Restore backup → pick the .tachibk file.*

The restore replaces the current library state with the backup. The app warns before applying so an accidental tap is recoverable until you confirm.

After restore, open Settings → Browse → Extensions and re-install any extensions that were active in the backup. Backups reference sources by id, not by extension binary — the manga reappears immediately, but you need the matching extension installed to actually fetch chapters.

## Reikai ↔ upstream Yōkai compatibility

A backup made in **Reikai** restores cleanly into **upstream Yōkai**, and vice versa. The backup format is shared.

Reikai-specific state behaves as follows on a cross-fork round-trip:

- **Multi-source grouping** (manual merges / unmerges) — included in the backup file. Survives a Reikai → Reikai restore; upstream Yōkai ignores it on restore (the manga reappear ungrouped), and round-tripping back through upstream loses the grouping.
- **Taste-profile cache, category sort order, related-mangas settings** — included in the backup. Survives a Reikai → Reikai restore; upstream silently drops unknown preference keys on restore.
- **Tracker links, library entries, categories, chapter read state, history, downloads metadata** — fully portable in both directions.

If you're temporarily switching to upstream Yōkai and plan to come back, make a Reikai backup *before* switching so Reikai-specific state isn't lost.

## Migrating across the .yokai → .y2k package suffix (1.9.7.5.x)

Reikai 1.9.7.5.8 changed the release package suffix from `.yokai` to `.y2k` so Reikai can install alongside upstream Yōkai instead of overwriting it. Android treats apps with different package ids as separate installs, so the new build doesn't auto-update the old one.

Existing Reikai users upgrading from a `.yokai` build to a `.y2k` build:

1. Open your existing `.yokai` install → Settings → Data and storage → **Create backup**. Save the `.tachibk` file off-device.
2. Install the new `.y2k` build. Both apps will now appear in your launcher; they're separate installs and don't share data.
3. Open the new `.y2k` install → Settings → Data and storage → **Restore backup** → pick the backup from step 1.
4. Verify the restore looks correct (library entries, categories, tracking, history). Then uninstall the old `.yokai` build to reclaim space and avoid confusion.

If you forget to back up before uninstalling the old build, the old library is gone — Android wipes per-app data when an app is uninstalled. There's no "transfer in place" path because the two installs are independent from Android's perspective.

## Backup contents — what's portable, what isn't

| Included | Not included |
|---|---|
| Library entries (favorited manga) | Downloaded chapter files |
| Categories and their manga assignments | Installed extensions (re-install after restore) |
| Tracker links and per-manga track entries | Cover image cache (re-downloaded on demand) |
| Read / unread chapter state and history | Tracker authentication tokens (re-sign-in after restore) |
| App settings (themes, reader prefs, library prefs) | App-level Android permissions |
| Multi-source merge/unmerge state *(Y2K)* | |
| Taste-profile cache *(Y2K)* | |

Tracker sign-in state is not included for security reasons — restored backups expose only the link between a manga and its tracker entry, not the credentials. Sign in again under Settings → Tracking after a restore.
