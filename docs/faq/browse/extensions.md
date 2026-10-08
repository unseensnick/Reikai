---
title: Extensions
titleTemplate: Browse - Frequently Asked Questions
description: Frequently Asked Questions about Extensions.
---

# Extensions
Frequently Asked Questions about Extensions.

## Where can I find repositories/extensions for Reikai?
**Reikai** does not run a repository, and it recommends none. Where you get your extensions from is your business and your risk.

A handful of sources are built into the app rather than installed, and those are Reikai's own. See [built-in sources](/docs/built-in-sources) for the list and which bugs belong here.

::: danger Caution
Beware that any third-party repositories or extensions will have full access to the app and may contain malware.
:::

## What are some recommended extensions and sources?
None are recommended, and none are hosted.

::: info Disclaimer
**Reikai** isn't responsible for slow, down, missing chapters, or subpar image quality of sources as it doesn't host the content.
:::

## Extension apps and plugins
**Reikai** installs sources in two forms, and the rest of these docs use the two names below.

* **Extension apps** are Android apps (`.apk` files). Every manga extension is one, and so are Tsundoku's novel extensions (tachiyomi-format APKs) and IReader's. They go through Android's installer, so the install permission, the **Installer** setting and [Shizuku](/docs/guides/shizuku) apply to them, and a backup records which ones you had.
* **Plugins** are light novel sources written in JavaScript, in the [LNReader](https://github.com/LNReader/lnreader) format. They download and run inside **Reikai**, never touch Android's installer, and do not appear in your device's app list.

Both install, update and uninstall from <nav to="extensions">.

## Enabling third-party installations
Extension apps are Android apps, so the system asks your permission before installing one. Plugins install inside **Reikai**, and nothing below applies to them (see [extension apps and plugins](#extension-apps-and-plugins)).

When prompted while installing your first extension, allow unknown apps installation from that source. You can also enable it ahead of time, per app, under **Install unknown apps** in your device settings.

::: details Video guide - recorded on Android 10
<video controls muted preload="metadata">
  <source src="/docs/faq/browse/extensions/unknown-sources-A10.light.webm" type="video/webm">
</video>
:::

::: tip Still got questions?
If you need more help regarding this, read [this post](https://nerdschalk.com/how-to-allow-apps-installation-from-unknown-sources-on-android-9-pie/ "nerdschalk.com | How to allow apps installation from unknown sources on Android 9 Pie").
:::

## What does the Not loaded section mean?
<nav to="extensions"> lists installed extensions and plugins that failed to load under **Not loaded**, so none of their sources show in Browse. Tap one to see why. The dialog gives the reason, any error message, and, for one that threw an error, **Copy stack trace** for a bug report, with **Uninstall** beside **OK**. The usual reasons:

* Its content warning is not one you allow (the row says **Filtered**). The [Browse FAQ](/docs/faq/browse/) covers changing that.
* It is not trusted yet (the row says **Untrusted**). Tapping it asks whether to trust or uninstall it.
* It is not signed, was built for an extension library this version of the app cannot load, or is missing information the app needs. Updating the app or the extension may help. One that failed to load but has an update waiting is listed under **Updates pending** instead, where its button installs the update.
* It threw an error while loading.

For a plugin whose script is missing, the dialog offers **Reinstall** instead.

## How do I uninstall an extension?
Uninstall extensions like regular apps: through device settings or in **Reikai**.

::: tip Uninstalling an extension
In **Reikai**, uninstall an extension via <nav to="extensions">, then tap **Uninstall** on the chosen extension.
:::

Two things behave differently. [Plugins](#extension-apps-and-plugins) only exist inside the app, so <nav to="extensions"> is the only place to remove one. And on a build that offers the **Private** installer, if you set it (see the [settings FAQ](/docs/faq/settings#what-are-the-different-installers)), extension apps live inside the app too, so they will not appear in your device's app list either.
