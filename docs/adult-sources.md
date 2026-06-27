# Adult sources (E-Hentai / ExHentai)

Built-in support for E-Hentai and ExHentai, plus richer handling of other adult sources you install. It is **off by default** and lives behind a single toggle, so nothing here appears unless you turn it on.

This subsystem is ported from [Komikku](https://github.com/komikku-app/komikku) (the Tachiyomi-SY lineage), re-typed onto Reikai's current Mihon base. Reikai ships a deliberately lighter slice of it for now; see [What's not here yet](#whats-not-here-yet).

## Turning it on

*Settings → Advanced → **Enable adult sources**.*

With it on:

- **E-Hentai** is added to Browse as a built-in source (no extension to install), in every language E-Hentai offers.
- A dedicated **E-Hentai** settings category appears (see below).
- Adult sources you install as extensions (nHentai, HentaiFox, AsmHentai, Koharu / SchaleNetwork, Pururin, 8Muses, LANraragi) get the extra tag handling described under [Tags in your library](#tags-in-your-library).

Turning it back off hides all of the above again.

## Browsing E-Hentai / ExHentai

Open E-Hentai from Browse and search it like any source, with the **full gallery filters**. The search box offers **tag autocomplete**: start typing a namespace and tag (for example `artist:` or `female:`) and it suggests matches from the E-Hentai tag catalogue.

ExHentai (the members-only side) needs a logged-in account. Sign in once under the E-Hentai settings screen (below); after that, browsing and reading use your account.

## E-Hentai account settings

*Settings → Advanced → **E-Hentai**.*

- **Log in to ExHentai** through an in-app WebView, which unlocks ExHentai browsing.
- **Image quality**, **original images**, and **Hentai@Home** options, applied to how pages load.
- **Japanese titles** when a gallery has one.
- **Tag filtering / watching thresholds**, mirroring your E-Hentai account's tag preferences.
- **Language filtering** and **front-page categories** for what the source shows.
- An **Incognito** toggle and a **statistics** dialog for the gallery update checker.

Your account-side choices (quality, H@H, tag thresholds) are synced up to your E-Hentai profile automatically, so the app and the site agree.

## Tags in your library

When you save an adult-source gallery, its **namespaced tags** (artist, group, parody, character, female, male, and so on) are recorded into your library. This works for E-Hentai and nHentai, and for the HentaiFox, AsmHentai, and Koharu (SchaleNetwork) extensions once installed.

Those tags feed two things:

- **Library search by tag.** Typing a tag name in library search surfaces every saved gallery carrying it.
- **Gallery info** (below), which lists them.

Tags also ride along in app backups, so restoring a backup brings them straight back without re-opening each gallery.

## Gallery info

On an adult-source gallery's details screen, the overflow (`⋮`) menu has a **Gallery info** action. It opens a read-only panel of every captured field: tags, uploader, rating, size, page count, language, and dates. Long-press any row to copy it.

## Keeping galleries updated

E-Hentai galleries get re-released as newer versions rather than gaining chapters, so they are handled by a dedicated background checker instead of the normal library update sweep (which skips them to avoid hammering the site).

*Settings → Advanced → E-Hentai → **Gallery update checker**.*

The checker re-examines your **favorited** E-Hentai galleries on a schedule and pulls in a newer version when one exists, merging the new version's pages while keeping your read progress and bookmarks. You can set how often it runs and any Wi-Fi / charging limits.

## Favorites backup

*Settings → Advanced → E-Hentai → **Favorites backup**.*

A one-way **backup** of your library's E-Hentai galleries to your account's favorites, so your in-app library can stay disposable while the account keeps a record.

- With it on, favoriting an E-Hentai gallery in the app also adds it to your account's favorites (into a slot you choose).
- **Back up all favorites now** pushes everything already in your library in one go (throttled to stay within E-Hentai's limits).
- Removing a gallery from your library **leaves it on the account** unless you tick **Also remove from E-Hentai favorites** in the removal confirmation.

This is intentionally one-directional: your library is the source of truth, and the account is a mirror it writes to. It never pulls galleries from the account back into your library.

## What's not here yet

Reikai currently ships a lighter slice of Komikku's adult-source subsystem. The notable gaps, any of which may be widened toward Komikku parity in a later release:

- **Two-way favorites sync.** Today's favorites feature is a one-way backup (library → account). Full sync (pull the account's favorites into the library, mirror removals both ways, reconcile conflicts) is not built.
- **Bulk gallery import.** Pasting many gallery URLs at once, or sharing / opening a gallery link straight into the app, is not wired up yet. You add galleries by browsing the source.
- **Library tag-search engine.** Library tag search matches recorded tags, but the richer query language (wildcards, exclusions, exact match, namespace aliases) that the browse-side autocomplete understands is not yet available over your saved library.
