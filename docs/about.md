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

Reikai is a personal fork for reading manga and light novels in one app.

It started on Yōkai, which descends from TachiyomiJ2K. That foundation was showing its age, and
keeping it modern meant rebuilding large parts of it by hand, on my own. Mihon had already done that
work: it runs on a current stack, and an active community keeps it up to date with fixes, security
updates and extension compatibility.

So from 0.1.0, Reikai is built on Mihon, with its own features on top. Mihon's improvements reach
Reikai as they land, and my time goes into what Reikai adds rather than into maintaining the base.

::: warning Two updates needed extra steps
- **0.3.2** changed the ID Android uses to recognise the app, so it installs next to an older Reikai
  rather than over it. Back up in the old app with **Include sensitive settings** ticked so your
  tracker logins come along, install 0.3.2 and pick the same storage folder, restore the backup, then
  uninstall the old app. Covers you set by hand do not carry over, so set those again.
- **0.1.0 and 0.1.1**, the first releases on Mihon, crashed on launch when installed over Yōkai-Y2K.
  0.1.2 fixed that: installing over Yōkai-Y2K now recovers your library on first launch, though merged
  series come back unmerged.
:::

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

Only from the [download page](/download/), which offers both Stable and Nightly, or the GitHub
releases it links to (the in-app updater pulls from the same place). Nowhere else. People
sometimes post links to a "patched" or "fixed" APK in issue comments or elsewhere;
those are not from me and have turned out to be malware. If a build did not come from
there, do not install it.

## Will updating keep my library and data? Should I back up?

Yes. An update installs over the version you have, and your library and settings stay as they are.
The exceptions are listed under [the rebuild question](#what-is-reikai-and-why-was-it-rebuilt-on-mihon).

Making a backup before any update is a good habit either way (<nav to="data-and-storage"> then
**Create backup**).

## Are extensions and sources supported?

Reikai doesn't maintain or fix third-party extensions; problems with a specific
extension are out of scope and belong upstream. A few sources are built into Reikai,
and bugs in those are fair to report. When you do, keep the issue title generalized
(for example "Error opening a built-in gallery source") and name the source by its
shorthand in the body. Shorthand list: [built-in sources](built-in-sources.md#reporting-a-bug-in-one).

## Can I use novel sources from tsundoku (NovelSourcery) or IReader?

Yes. Add the repo address each project publishes with **Add repo** under **Repos**, in
<nav to="browse">, the same way as any other. Their extensions then install and update from
**Extensions** like manga extensions, and their novels browse, read, download, update, migrate and
track like any other novel.

Both kinds sit beside [LNReader](https://github.com/LNReader/lnreader)-style plugins. When a list mixes
kinds of novel source, each one is labelled JS, APK or IReader so you can tell them apart.
Extensions from a repo that publishes its signing key load without a trust prompt, and so do those from
IReader's own repo; any other asks before an extension first loads, as it does for manga.

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
