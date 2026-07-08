# Reikai FAQ

New here? These are the questions that come up most. For anything not covered, ask in
[Q&A](https://github.com/unseensnick/Reikai/discussions/categories/q-a).

## What is Reikai, and why was it rebuilt on Mihon?

Reikai is a personal fork for reading manga and light novels. It used to be built on
Yōkai (which descends from TachiyomiJ2K), a lineage on an older foundation that I was
hand-rebuilding on modern tools, solo. Mihon already ships that modern stack with an
active community keeping it current (upstream fixes, security updates, extension
compatibility), and Reikai's own features sit cleanly on top. So rebasing keeps the
base current automatically and frees my time for the Reikai-specific features. Nothing
goes away: the app keeps its identity and upgrades in place, so you keep your library
and settings.

## Does the UI follow Mihon or Yōkai?

Mostly Mihon, so nothing looks out of place, with the Yōkai touches I liked carried
over: the single-list library view with the floating category hopper, dynamic grouping
(by source, language, tag, and so on), and the cover-color accent on the details
screen. To get the single-list look: Library, tap the filter/funnel icon, open the
Display tab, and turn on "Show all categories in one list." Suggestions are welcome,
but additions will still follow Mihon's design.

## How do I get updates? Is it on an app store?

No app store. Reikai has a built-in updater: More, About, Check for updates. It checks
GitHub Releases, downloads the newest version, and installs it from the notification
(or wherever your downloads land).

## Where should I download Reikai from? Are "patched" builds safe?

Only from the [official Releases page](https://github.com/unseensnick/Reikai/releases)
(or the in-app updater, which pulls from the same place). Nowhere else. People
sometimes post links to a "patched" or "fixed" APK in issue comments or elsewhere;
those are not from me and have turned out to be malware. If a build did not come from
this repository's Releases, do not install it.

## Will updating keep my library and data? Should I back up?

Yes, updates install in place and keep your library and settings. Back up first anyway
(Settings, Data and storage, Create backup); good habit before any update.

## A download keeps failing. Is that a bug in Reikai?

Usually not. Reikai's download code is Mihon's, so whether a chapter can actually be
fetched is almost always on the source or extension side, not the app. Common cases:

- **Blank in the reader too / a Cloudflare error:** the source's own server is
  unreachable. Nothing to do but wait for it to come back, or route it through a
  [Cloudflare bypass proxy](flaresolverr.md) if that is the block.
- **Won't download until you open a chapter first:** some sources only build their page
  list once you have opened the reader (a session or decrypt step). The downloader asks
  for the list cold and gets nothing until you have viewed a chapter once.
- **Renders in the reader but saves zero pages:** the page shows, but the extension's
  parser returns no pages, so there is nothing to save.

Quick test: try the same source and chapter in Mihon. If it fails there too, it is not
specific to Reikai (more likely the source blocking you, or something on your network:
ISP, DNS, a VPN or firewall). If it works in Mihon but not Reikai, that one is on me,
so open an issue with the exact source, chapter, and steps.

## Are extensions and sources supported?

Reikai doesn't maintain or fix third-party extensions; problems with a specific
extension are out of scope and belong upstream. A few sources are built into Reikai,
and bugs in those are fair to report. When you do, keep the issue title generalized
(for example "Error opening a built-in gallery source") and name the source by its
shorthand in the body. Shorthand list: [docs/adult-sources.md](adult-sources.md#shorthands).

## Can I use novel sources from tsundoku (NovelSourcery) or IReader?

Not those repos directly as-is. Reikai's novel sources run as
[LNReader](https://github.com/LNReader/lnreader)-style JavaScript plugins, while
tsundoku's NovelSourcery and IReader ship compiled-APK extensions of their own type
(tsundoku adds a novel-extension flag on top of Mihon that stock Mihon, which has no
novels at all, does not recognize).

The upside: the LNReader plugin format is shared across Reikai, tsundoku, and IReader,
so any novel source that exists as an LNReader plugin you can add today, add its repo
in the novel source settings. Supporting the compiled-APK side is a much bigger piece
of work: on the backlog, but no timeline.

## I edited an entry's title (or author). Why doesn't search or sorting find the new name?

That is intentional. When you use Edit info to change a title, author, cover, or other
details, the change is display-only: it changes how the entry looks on its details page and
in the library, updates, and history lists. Search, sorting, category grouping, and the
automatic same-title source grouping all keep using the entry's original source info.

So a renamed entry stays where its original title sorts, still groups with its other sources,
and is found in search by its original name, not the one you typed. This is deliberate: it
keeps a rename from silently reshuffling your library or splitting a merged series. The edit
is stored separately and never overwrites the source, so Reset restores the original cleanly.

## Where do I report a bug, request a feature, or ask a question?

- **Bug:** the [bug report form](https://github.com/unseensnick/Reikai/issues/new?template=2_report_issue.yml)
  under Issues. Include your Reikai version (More, About, Version), Android version, and
  device.
- **Feature idea:** the [Ideas discussion](https://github.com/unseensnick/Reikai/discussions/categories/ideas),
  so it can be talked through first.
- **Question:** the [Q&A discussion](https://github.com/unseensnick/Reikai/discussions/categories/q-a).

## Will my feature request be built?

Maybe as asked, maybe as a variation that fits the app better, maybe not at all. Reikai
is shaped around one person's use, so talking an idea through in Ideas first is the most
useful path.

## Will my pull request be merged?

It's a personal-time project, so a PR may sit for a while, may not be merged, or may
inspire a different take on the same idea. For anything beyond a small fix, raise it
first.
