# Adult sources

_Dev records: [exh-subsystem.md](dev/plans/exh-subsystem.md), [adult-browse-parity.md](dev/plans/adult-browse-parity.md), [library-tag-search.md](dev/plans/library-tag-search.md), [md-enhanced-source.md](dev/plans/md-enhanced-source.md). Doc map: [README.md](README.md)._

Reikai has built-in support for E-Hentai and ExHentai, with richer handling than a normal extension
gives you: galleries carry their real tags into your library, uploader and page count show on the
details screen, and your favorites can sync with the account.

**It is off by default and nothing appears until you turn it on.** If you never open the switch, the
app behaves as though none of this exists.

## Turning it on

Go to <nav to="advanced"> and turn on **Enable adult sources (E-Hentai)**.

E-Hentai then appears in <nav to="main_browse">, and you can search and read from it straight away
without an account.

## Adding ExHentai

ExHentai needs a real account with ExHentai access; a fresh account will not have it.

Go to <nav to="e-hentai"> and turn on **Enable ExHentai**. That opens a login page. Sign in there
and the ExHentai source joins E-Hentai in <nav to="main_browse">.

If the login page loads but ExHentai still shows nothing afterwards, the account does not have
access yet. That is set on the site, not in the app.

## Settings worth knowing

These all live under <nav to="e-hentai">, which only appears once adult sources are on.

**Image quality** picks what the site serves you. Auto follows your account default. Higher settings
cost more of your download allowance.

**Language Filtering** hides galleries in languages you do not read, so browsing stops being mostly
noise. Set it once and it applies everywhere you browse the source.

**Front Page Categories** decides which categories the front page shows you at all.

**Favorites backup** copies your account's favorites into a chosen slot, so a favorites list you
built over years is not one bad click away from gone. There is a "back up now" action, and an option
to keep doing it automatically.

**Gallery update checker** re-checks saved galleries for new pages, because galleries get revised in
place rather than gaining chapters the way a series does. It can be limited to Wi-Fi and to while
charging, and it keeps statistics so you can see whether it is actually finding anything.

## Tags in your library

Galleries saved to your library keep their source tags, and [library search](library-search.md) can
search them. So `artist:` or a content tag finds things in your own library the same way it would on
the site, without opening a browser.

This is the main reason to use the built-in source rather than a generic extension: an extension
gives you the images, this gives you the metadata too.

## What this is not

**It is not a general adult-content unlock.** Other adult sources are ordinary extensions: install
them from a repository in <nav to="extensions"> and they work like any other source, with or without
this switch. This page is only about the built-in E-Hentai and ExHentai support.

**It does not change what other sources show you.** Nothing here filters, hides or reveals content
anywhere else in the app.

## If something is missing

Reikai ships a deliberately smaller slice of this than the fork it came from. Some of the more
obscure E-Hentai features are not here, and a few will never be. If something you relied on is
absent, say so in an issue rather than assuming it is a bug: most of the gaps are deliberate and easy
to revisit, but only if someone asks.
