# Anime as a third content type (minimum viable product)

## Goal

Watch anime in Reikai beside manga and novels: browse Aniyomi anime extensions, add a series to the library, see its episodes, and play an episode in an outside video player, with what was watched remembered and backed up.

## Why

The owner wants all three content types in one app. Aniyomi and Anikku show the extension ecosystem exists and is maintained. Reikai is better placed than Aniyomi was: Aniyomi keeps a separate anime copy of every screen (about 59,000 lines on its anime paths, measured by path), while Reikai's library, browse, details, history, updates, downloads and migrate already run on shared engines over per-type adapters, so anime would add an adapter to each rather than a copy.

## Approach

**A lighter type (owner ruling).** Anime is not a full peer under the write-once rule. It shares the structure of the shared surfaces (library, browse, details, history, migrate, backup), and a change to one of those lands for anime when anime can use it. Reading behaviour (the reader, tap zones, spreads, page layouts, typography, read aloud) has no meaning for video and stays manga and novels only. Where a shared capability does not apply, anime answers it as unsupported and the control is hidden, as content-layer.md requires.

**Stage 1, the MVP:**

1. **Loading extensions.** `Extension.Kind` gains `ANIME` for the `tachiyomi.animeextension` feature, through Mihon's one extension pipeline as the novel kinds already do. Reikai ships Aniyomi's extension API (`eu.kanade.tachiyomi.animesource`: `AnimeSource`, `AnimeCatalogueSource`, `SAnime`, `SEpisode`, `Video`, `Hoster`) as the contract those extensions compile against, the way it ships IReader's for its novel APKs. Aniyomi accepts extension library versions 14, 16 and 17.
2. **Data.** `ContentType` gains `ANIME`, and anime keeps its own tables (series, episodes, watch history), as novels keep theirs; `EntryId` gains an anime variant. The All chip shows all three; a chip for anime alone joins Manga and Novels.
3. **Surfaces.** Browse and the per-source catalogue, the details screen with its episode list, the library, and History, each through a third adapter. Updates follows once an anime library update runs.
4. **Playback.** An episode opens in an outside player (mpv-android, VLC and others) with an `ACTION_VIEW` intent carrying the stream address and its request headers, as Aniyomi's `ExternalIntents` does. The position the player hands back marks the episode watched or saves where it stopped.
5. **Backup.** Anime library entries, episodes watched and categories go into the backup from the first build that can add an anime series, so a restore never loses them.

**Later stages, not in the MVP:** the built-in player (mpv, about 12,000 lines in Aniyomi), episode downloads (ffmpeg), anime list tracking (AniList and MyAnimeList anime lists, Kitsu, Simkl, Shikimori, Bangumi), intro skipping, seasons, casting, torrents.

**Public naming.** The README, the site and release notes stay generic about anime sources, as they are about manga and novel sources.

## Key files

- `domain/src/main/java/eu/kanade/tachiyomi/extension/model/Extension.kt` (`Extension.Kind`, `fromFeatures`).
- `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt` (where each kind is loaded).
- `domain/src/main/java/reikai/domain/library/ContentType.kt` (`ContentType`, `includes`).
- `app/src/main/java/reikai/domain/entry/EntryId.kt` (`EntryId`).
- `.claude/rules/content-layer.md` (the lighter-type rule) and [content-layer.md](../subsystems/content-layer.md).

## Open questions

1. **Which players first?** mpv-android and VLC (common, free), or also MX Player and Just Player. Recommend mpv-android and VLC, then any app that accepts a video link.
2. **Seasons.** Aniyomi's extensions can split a series into seasons (`SAnimeSeasonUpdate`). Flatten them into one episode list, or skip seasons in the MVP. Recommend flattening, since a season list needs its own screen.
3. **Off by default?** A setting that shows anime, off until turned on, or anime always present. Recommend off by default, so manga and novel readers see no change.
4. **Anikku's extension additions.** Anikku adds torrent helpers to its extension API; whether its repos need them is unverified. Recommend Aniyomi's API only for the MVP.

## Decisions & tradeoffs

- **Not in 0.6.0.** Scheduled under Later on the roadmap with no date.
- **A lighter type, not a full peer** (owner ruling), because most reading features have no video meaning, and a full peer would put "how does this apply to anime" on every change.
- **An outside player before a built-in one.** It proves the extension and data side with no native video code and no growth in app size; the built-in player is the largest single piece and comes last.
- **Aniyomi's extension API, not a new one,** so the existing anime extension repos work unchanged.
