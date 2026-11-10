---
title: Settings
titleTemplate: Frequently Asked Questions
description: Frequently Asked Questions about various app settings.
---

# Settings
Frequently Asked Questions about various app settings.

## Why is taking screenshots blocked?
Set **Secure screen** to **Never** in <nav to="security-and-privacy">. It is a three-way choice rather than a switch, and it starts on **Incognito mode**, so out of the box screenshots are blocked only while incognito is on.

## What is DNS over HTTPS?
**DNS over HTTPS (DoH)** (in <nav to="advanced">) offers secure DNS resolution through HTTPS, preventing attacks.
Learn more [here on DNS over HTTPS](https://www.cloudflare.com/learning/dns/dns-over-tls/). This may help bypass some basic website blocking.

## What are the different installers?
Up to four installer options are available (in <nav to="advanced">):

* **PackageInstaller**: The primary installer option with additional features dependent on the Android version.
  > For instance, it can bypass user prompts when updating extensions on **Android 12**.
* **Legacy**: A fallback installer if the standard **PackageInstaller** doesn't work.
  > This is the default for **MIUI** (i.e. Xiaomi devices).
* **Shizuku**: Refer to the [Shizuku guide](/docs/guides/shizuku) for details on **Shizuku**'s functionality.
* **Private**: Installs extensions inside **Reikai** rather than as separate apps, so they need no install prompt and do not show up in your app list. They also go away with the app, and are not shared with any other Tachiyomi-lineage app on the device. The standard stable APK does not offer it; the FOSS and nightly builds do.

## Can I hide manga or light novels?
Yes. **Content shown** at the top of <nav to="appearance"> takes **All**, **Manga** or **Novels**, and is also offered when you first set up the app. Set to one type, the app shows only that type everywhere: the library, browsing and search, the widget, extensions and the settings for it. The chips that switch between manga and novels, and the labels saying which a series is, go away too. Library updates skip the hidden type. Nothing is deleted, so switching back to **All** brings everything back as it was.

## Where are the buttons that jump through a series' chapter list?
On a series' page, the top bar has three buttons: **Scroll to top**, **Scroll to next unread** and **Scroll to bottom**. **Scroll to next unread** goes to the chapter the **Start** / **Resume** button would open. To keep the top bar tidier, turn on **Chapter list scroll actions in menu** in <nav to="appearance">, which moves the three into the <nav to="overflow"> menu instead.

## Can I move the Start/Resume button to the left?
Yes. Drag the button sideways on a series' page and let go: it stays on the half of the screen you dropped it on, for every series. You can also pick **Left** or **Right** under **Resume button position** in <nav to="appearance">.
