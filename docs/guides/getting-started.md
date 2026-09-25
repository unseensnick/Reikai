---
title: Getting started
titleTemplate: Guides
description: Essential information to help you get set up with Reikai.
---

# Getting started

Essential information to help you get set up with Reikai.

## Installation guide

### Downloading Reikai

1. Visit our [download](/download/) page to get the latest version of **Reikai**.
2. After the download is complete, open the `.apk` file.
3. Proceed with the installation process.

### Adding sources

Once **Reikai** is installed on your device, you can bring your own content to read from various sources:

:::: tabs
== Local source
Read content stored locally on your device.

See the [Local source guide](/docs/guides/local-source/) for instructions.
== External repositories
External repositories add additional sources to **Reikai**:
* Add one by going to <nav to="browse"> and tapping **Repos**, then **Add repo**.
* Paste the address as the repo gives it to you. An extension store's usually ends in `repo.json` or `index.min.json`, and for an older array-format store it has to end in `index.min.json`, because the app derives that store's real address from it.

Novel repos are added the same way. **Reikai** reads
[LNReader](https://github.com/LNReader/lnreader)-style plugins, whose repo address points at a
`plugins.min.json` registry, and the novel extensions Tsundoku and IReader publish, whose stores are
added like a manga extension store. **Add repo** works out which kind an address is, and turns down one
it cannot read. Each repo's card shows how many extensions or plugins it lists, or that it
could not be reached.

::: danger Caution
Reikai will not provide resources for any unofficial repositories. Beware that any third-party repositories or extensions will have full access to the app and may contain malware.
:::

Once you've added a repository, go to <nav to="extensions"> and refresh the list.

You can now tap the download button next to an extension app or a light novel plugin to install it (see [extension apps and plugins](/docs/faq/browse/extensions#extension-apps-and-plugins)).

> You may need to [enable third-party installations](/docs/faq/browse/extensions#enabling-third-party-installations).

== Manual extensions
Extensions can be manually installed through `.apk` files.

::: danger Caution
Reikai will not provide resources for any unofficial extensions. Beware that any third-party repositories or extensions will have full access to the app and may contain malware.
:::
::::

### Adding series to your library

After installing the desired extension, you'll find it in the **Sources** tab.

Here's how you can add series to your library:

1. Select the source you'd like to browse.
1. You can use the **Popular**/**Latest** listings to browse, or you can search for the series name.
1. Once you've found the series that you want to add, tap on it for more details.
1. Press the "**Add to library**" button, and the series will be added to your Library, ready to be read!

## Additional setup

### Series search options

If you want to search for series across all your sources, you can use the Global Search feature.

Follow these steps:

1. Go to the "**Browse**" section.
1. Open the "**Sources**" tab. It comes first unless you turned on the **Feed** tab and put it in front.
1. Tap **Global search** in the toolbar, the globe icon, to find series across all your sources. The plain search icon beside it only filters the source list.

### Trouble finding a specific series?

If you encounter difficulties while searching for a specific series, consider the following points:

* Double-check your spelling and try again, as some sources might use **Japanese romanized** titles instead of **English** ones.
  > Example: **Boku no Hero Academia** instead of **My Hero Academia**.

* Some sources may use different spellings or wordings for titles.
  > Example: **Bungo Stray Dogs** instead of **Bungou Stray Dogs**

  > Example: **3-gatsu no Lion** instead of **Sangatsu no Lion**.
