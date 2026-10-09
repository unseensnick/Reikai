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

Some sources sit behind **Cloudflare**, an anti-bot check, and stronger settings of it can block apps like **Reikai**.
It usually shows up as a `Failed to bypass Cloudflare` error, or as **WebView** reloading the page over and over.

Work through these in order, and stop at the first one that works:

1. [Open the source in WebView](#accessing-websites-via-webview) and complete the check there, then go back and retry.
1. If the page stops on a **Verify you are human** box, turn on **Solve interactive Cloudflare challenges** in <nav to="advanced">, which ticks the box for you. It is off by default. To let library updates get past the check too, also turn on **Solve with the app closed**.
1. [Change your user agent](#changing-your-user-agent), restart the app and try WebView again.
1. Route the source through a [Cloudflare bypass proxy](/docs/flaresolverr) you run yourself. This is for protection the in-app WebView cannot clear at all, and it needs a computer or server that stays on.

If none of these work, wait for the source to lower its protection, or switch to a different source.

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

## General

### A download keeps failing

Whether a chapter can be fetched is usually decided by the source, extension or plugin, not by Reikai.
Common cases:

- **The chapter is blank in the reader too, or shows a Cloudflare error:** the source's site is unreachable or blocking you. Wait for it to come back, or follow the [Cloudflare steps](#cloudflare) if that is the block.
- **It won't download until you open the chapter first:** some sources only list a chapter's pages once you have opened it in the reader. Open the chapter once, then download it.
- **It opens in the reader but saves no pages:** the extension finds no pages to save, so there is nothing to download. Report it to whoever publishes the extension.

To tell whether the problem is Reikai or the source:

- **Manga:** try the same source and chapter in Mihon. If it fails there too, the cause is the source or your network (ISP, DNS, a VPN or a firewall). If it works in Mihon but not in Reikai, open an issue with the exact source, chapter and steps.
- **Light novels:** open the chapter in the reader, then on the site itself with **Open in WebView**. If the site will not show it either, the cause is the site or your network. If the chapter reads fine but will not download, open an issue with the exact source, chapter and steps.

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
This error can mean the `.apk` file is corrupted or did not download completely.
* Try re-downloading the `.apk`.
:::

::: details `INSTALL_FAILED_NO_MATCHING_ABIS`
Seeing this error suggests the `.apk` is incompatible with your device's CPU architecture.
* Download the universal `.apk` version (i.e. the largest `.apk` file size option on **GitHub**), otherwise download the compatible version for your device.
:::
</div>
