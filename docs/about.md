---
title: Reikai
titleTemplate: Frequently Asked Questions
description: What Reikai is, where to get it, and how it differs from Mihon.
---

# Reikai

The questions that come up most about the app itself.
For how the reading experience works, the rest of this section and the [guides](/docs/guides/getting-started) cover it.

::: tip Not answered here?
Ask in [Q&A](https://github.com/unseensnick/Reikai/discussions/categories/q-a).
:::

## What is Reikai, and why was it rebuilt on Mihon?

Reikai is a personal fork for reading manga and light novels. It used to be built on
Yōkai (which descends from TachiyomiJ2K), a lineage on an older foundation that I was
hand-rebuilding on modern tools, solo. Mihon already ships that modern stack with an
active community keeping it current (upstream fixes, security updates, extension
compatibility), and Reikai's own features sit cleanly on top. So rebasing keeps the
base current automatically and frees my time for the Reikai-specific features. Nothing
goes away: your library and settings carry over through a backup. Since 0.3.2 the app
installs as its own app beside older builds, so back up in the old one and restore in the new
one.

## Does the UI follow Mihon or Yōkai?

Mostly Mihon, so nothing looks out of place, with the Yōkai touches I liked carried
over: the single-list library view with the floating category hopper, dynamic grouping
(by source, language, tag, and so on), and the cover-color accent on the details
screen. [Library layout](library-layout.md) covers how to turn them on. Suggestions are welcome,
but additions will still follow Mihon's design.

## How do I get updates? Is it on an app store?

No app store. Reikai has a built-in updater, in <nav to="about"> under **Check for updates**. It checks
GitHub Releases, downloads the newest version, and installs it from the notification
(or wherever your downloads land).

## Where should I download Reikai from? Are "patched" builds safe?

Only from the [official Releases page](https://github.com/unseensnick/Reikai/releases)
(or the in-app updater, which pulls from the same place). Nowhere else. People
sometimes post links to a "patched" or "fixed" APK in issue comments or elsewhere;
those are not from me and have turned out to be malware. If a build did not come from
this repository's Releases, do not install it.

## Will updating keep my library and data? Should I back up?

Updates from 0.3.2 onward install in place and keep your library and settings. Coming from a
build older than 0.3.2, Reikai installs beside it, so restore a backup. Back up first anyway
(<nav to="data-and-storage"> then **Create backup**); good habit before any update.

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
under **Light novel repos** in **Extension stores**, in <nav to="browse">. Supporting the compiled-APK side is a much bigger piece
of work: on the backlog, but no timeline.

## Where do I report a bug, request a feature, or ask a question?

- **Bug:** the [bug report form](https://github.com/unseensnick/Reikai/issues/new?template=2_report_issue.yml)
  under Issues. Include your Reikai version, from <nav to="about">, plus your Android version and
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
