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

## Are extensions and sources supported?

Reikai doesn't maintain or fix third-party extensions; problems with a specific
extension are out of scope and belong upstream. A few sources are built into Reikai,
and bugs in those are fair to report. When you do, keep the issue title generalized
(for example "Error opening a built-in gallery source") and name the source by its
shorthand in the body. Shorthand list: [built-in sources](built-in-sources.md#reporting-a-bug-in-one).

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

## On a merged series, why can't I change the cover while a source chip is selected?

Tapping the cover shows the cover of whichever source you have selected, so it matches the
page you are looking at. Edit cover and Delete custom cover are only offered on the group
itself, which is the All chip.

The reason is that your library shows the group's cover, not each source's. If you could set
a custom cover while viewing one source, it would land on that source's copy and your library
would carry on showing the old one, which looks like the change failed. Restricting the edit
to the group means the cover you are looking at is always the cover a change would replace.

To change it, switch to All and tap the cover there.

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
