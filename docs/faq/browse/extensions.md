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

## Enabling third-party installations
Manga extensions are Android apps, so the system asks your permission before installing one. Light novel plugins are not: they are JavaScript, they install inside **Reikai**, and nothing below applies to them.

When prompted while installing your first extension, allow unknown apps installation from that source. You can also enable it ahead of time, per app, under **Install unknown apps** in your device settings.

::: details Video guide - recorded on Android 10
<video controls muted preload="metadata">
  <source src="/docs/faq/browse/extensions/unknown-sources-A10.light.webm" type="video/webm">
</video>
:::

::: tip Still got questions?
If you need more help regarding this, read [this post](https://nerdschalk.com/how-to-allow-apps-installation-from-unknown-sources-on-android-9-pie/ "nerdschalk.com | How to allow apps installation from unknown sources on Android 9 Pie").
:::

## How do I uninstall an extension?
Uninstall extensions like regular apps: through device settings or in **Reikai**.

::: tip Uninstalling an extension
In **Reikai**, uninstall an extension via <nav to="extensions">, then tap **Uninstall** on the chosen extension.
:::

Two things behave differently. Light novel plugins only exist inside the app, so <nav to="extensions"> is the only place to remove one. And if you set the installer to **Private** (see the [settings FAQ](/docs/faq/settings#what-are-the-different-installers)), manga extensions live inside the app too, so they will not appear in your device's app list either.
