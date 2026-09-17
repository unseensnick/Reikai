---
title: Troubleshooting
titleTemplate: Guides
description: Facing source or app issues? Here's how to troubleshoot.
---

# Troubleshooting

Facing source or app issues? Here's how to troubleshoot.

Be sure to check the [Frequently Asked Questions](/docs/faq/general) for how to address common issues too.

## WebView

### Accessing websites via WebView
Once you've entered a source/series, go to <nav to="webview">, or simply press the <nav to="webview-single"> icon directly inside a series.

::: tabs
== From Browse
1. Open **Browse** from the bottom navbar.
2. Tap the desired source.
3. Open <nav to="overflow"> in the top toolbar and tap **Open in WebView**.
4. Complete a **CAPTCHA** if one is shown.
5. Close by tapping `X` at the top-left.
== From a Series
1. Open a series.
2. Tap the **WebView** icon button.
3. Complete a **CAPTCHA** if one is shown.
4. Close by tapping `X` at the top-left.
:::

### Clearing cookies and WebView data
This resets your **WebView** to a clean state, including any login states.

1. Navigate to <nav to="advanced">.
1. Tap **Clear cookies**.
1. Tap **Clear WebView data**.

### WebView update
To update **WebView**, you need to find what **WebView** implementation is used on your device.

Typical default implementation depends on the Android version as follows:

::: tabs
== Android 10 and above
[Android System WebView](https://play.google.com/store/apps/details?id=com.google.android.webview)
== Android 8 - 9
[Google Chrome](https://play.google.com/store/apps/details?id=com.android.chrome)
:::

::: tip
You can check or change which implementation is in use from [Developer Options](https://developer.android.com/studio/debug/dev-options).
:::

::: warning Caution with Non-Standard WebView
Using non-standard **WebView** (like **Firefox**) might cause **Reikai** to malfunction or crash.

It's best to use the standard [Android System WebView](https://play.google.com/store/apps/details?id=com.google.android.webview) or [Google Chrome](https://play.google.com/store/apps/details?id=com.android.chrome).
:::

## Cloudflare

**Cloudflare**, an anti-bot mechanism, is used by some sources.
Some sources intentionally have higher **Cloudflare** protection to deter apps like **Reikai**.

### Routing the source through a bypass proxy

Some sources sit on protection the in-app WebView cannot clear at all, and no amount of retrying or user-agent swapping helps.

For those, **Reikai** can hand the request to a bypass proxy you run yourself, which solves the challenge in a real browser. See [Cloudflare bypass](/docs/flaresolverr).

### Dealing with Cloudflare looping
Certain sources may employ more advanced **Cloudflare** protection, leading to **WebView** continuously reloading when you [access the website via WebView](#accessing-websites-via-webview).
If the page stops on a **Verify you are human** box, turn on **Solve interactive Cloudflare challenges** in <nav to="advanced">. Otherwise, try changing your user agent below, and if that does not help either, route the source through a bypass proxy as described above.

### Changing your user agent
A user agent string shares requester information with websites, potentially affecting **Cloudflare**'s bot detection.
While some sources have specific user agent strings, most rely on the app's default.

::: info Changing your user agent
1. Navigate to <nav to="advanced">.
1. Replace the **Default user agent string** with a different user agent string.
   >    [Here's a reference site](https://www.whatismybrowser.com/guides/the-latest-user-agent/).
   * You can use any user agent strings available in the reference site or by searching online.
   * You may need to try different user agents from different devices, browsers, and/or operating systems.
1. After changing the user agent string, remember to restart the app & check WebView to see if it passes verification.
:::

::: tip Did none of this work?
Wait for the source to lower its protection, or switch to a different source.
:::

## General

### A download keeps failing

Usually not. Reikai's download code is Mihon's, so whether a chapter can actually be
fetched is almost always on the source or extension side, not the app. Common cases:

- **Blank in the reader too / a Cloudflare error:** the source's own server is
  unreachable. Nothing to do but wait for it to come back, or route it through a
  [Cloudflare bypass proxy](/docs/flaresolverr) if that is the block.
- **Won't download until you open a chapter first:** some sources only build their page
  list once you have opened the reader (a session or decrypt step). The downloader asks
  for the list cold and gets nothing until you have viewed a chapter once.
- **Renders in the reader but saves zero pages:** the page shows, but the extension's
  parser returns no pages, so there is nothing to save.

Quick test: try the same source and chapter in Mihon. If it fails there too, it is not
specific to Reikai (more likely the source blocking you, or something on your network:
ISP, DNS, a VPN or firewall). If it works in Mihon but not Reikai, that one is on me,
so open an issue with the exact source, chapter, and steps.

### Obtaining crash/error logs
For crash investigations, navigate to <nav to="advanced"> and tap **Share crash logs**.

<img
  class="only-light"
  src="/docs/guides/troubleshooting/share-crash-logs.light.webp"
  alt="Share crash logs"
  width="672"
  height="360"
  loading="lazy"
  decoding="async"
/>
<img
  class="only-dark"
  src="/docs/guides/troubleshooting/share-crash-logs.dark.webp"
  alt="Share crash logs"
  width="672"
  height="360"
  loading="lazy"
  decoding="async"
/>

### Obtaining more logs
To diagnose abnormal app behavior, record device logs using a [Logcat Reader](https://github.com/darshanparajuli/LogcatReader/releases).

### App or extension installation issues
Encountering problems while trying to install app or extension `.apk` files?
Follow these steps:

1. Install the latest version of [Split APK Installer](https://github.com/Aefyr/SAI/releases) from their **GitHub Releases** page.
1. Open Split APK Installer, then select and install the `.apk` file.

::: info Common errors:
**Split APK Installer** helps show better error messages or may even successfully install your `.apk` without issue.
Common errors include:
<div class ="custom-details">

::: details `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package apk.file_name signatures do not match the previously installed version; ignoring!`
Seeing this error while installing means the `.apk` already exists on the device, indicating a mismatch in signatures.
* Backup any data, uninstall the existing app or extension from your device, then install the `.apk` file again.
:::

::: details `DISPLAY_NAME column is null`
Seeing this error points to a corrupted `.apk` file.
* Try re-downloading the `.apk`.
:::

::: details `INSTALL_FAILED_NO_MATCHING_ABIS`
Seeing this error suggests the `.apk` is incompatible with your device's CPU architecture.
* Download the universal `.apk` version (i.e. the largest `.apk` file size option on **GitHub**), otherwise download the compatible version for your device.
:::
</div>
