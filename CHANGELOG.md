# Changelog

All notable changes to this project will be documented in this file.

A release is grouped by the area it touches (Library, Reader, Tracking, ...), and within an area by a simplified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/): `Added` for new features, `Changed` for behaviour and visual changes, `Fixed` for bugfixes. `Other` is the last area and holds technical changes with no user-facing effect. Releases up to 0.3.2 group the other way round, by `Additions` / `Changes` / `Fixes` first and area second.

Reikai uses its own [Semantic Versioning](https://semver.org/) from the Mihon-based releases onward. The earlier `1.9.7.5.x` versions tracked the upstream Yōkai release Reikai was based on.

## [Unreleased]

### Highlights

Manga and light novels stop being two apps in one. A new All chip shows your whole library as a
single list, a category can hold both kinds, and one search grammar, sort, filter and grouping
covers everything. Browse, global search, Extensions, Migrate and the download queue each became one
list too, and Updates and History can merge into one Recents tab, off until you turn it on under
Settings -> Appearance.

Merged series got the fix that mattered most. A chapter is now identified by the group's own
stitching instead of by chapter number, which two sources of one series rarely agree on, so reading,
bookmarking, downloading, counting and notifying all land on the right chapter.

Migration was rebuilt. Manga and novels share one flow, matches are offered rather than assumed,
failures are named and can be retried, and you choose what carries across at the moment you confirm.

Novels now open in the same reader manga uses, drawn as real text or as a web page, with a font
picker spanning the whole Google Fonts library, chapter clean-up rules and find and replace. Light
novels also gained three trackers built for novels, and novel extensions from Tsundoku and IReader
now install beside the LNReader plugins.

Manga pages can be drawn by a new high quality renderer, bringing dual page view, page transition
animations, HDR and a display cutout mode.

Changes marked (from Mihon) come from upstream Mihon, which Reikai is built on.

### Before you upgrade

Upgrading merges any manga or novel you have twice from the same source into one, keeping the
library copy with your read chapters, history, categories, tracking, custom cover and downloads.
Every merged series also rebuilds its combined chapter list once.

Crash reports and anonymous usage data are on by default and start sending after the update, since
the switches did nothing on earlier builds. Turn them off under Settings -> Security and privacy, or
install the `-foss` APK, which carries neither.

The previous novel reader is gone: every novel opens in the shared reader, as text or as a web page
under Settings -> Novel reader -> Rendering mode. A chapter you have already read now opens at its
start unless Resume reading position is on, Continuous chapters and Skip filtered chapters start on,
and in the web page mode a chapter's own scripts no longer run unless Run scripts a chapter embeds
is on.

Your novel library takes on the manga library's sort, filters and grouping, and from then on one
setting covers both. Existing categories carry over as Manga only or Novels only in one Edit
categories list, the Default category follows the global sort, and empty categories are now always
hidden. In library search, `src:` now matches a source's name; use `srcid:` for an id.

Some defaults change. Adding a series that shares a title with one in your library now asks before
grouping them instead of grouping them on its own. Mark duplicate read chapter as read, Track update
errors and Share trackers across merged sources are on, manhwa, manhua and webtoons open in webtoon
mode, and removing a merged series starts with All grouped sources ticked. On a merged series,
Updates, a source chip and new-chapter notifications now open only that source in the reader.

Settings moved: reader settings split into Settings -> Manga reader and Settings -> Novel reader,
Recommendations has its own entry, and the source settings screens, Enable adult sources and a new
Safe / Mixed / 18+ picker that keeps your NSFW choice sit under Settings -> Browse and sources.

The Manga and Novels chips on Browse -> Sources, Extensions and Migrate now filter one mixed list,
and the download queue drops them for one list in one order. The Sources list's Last used starts
empty until you next open a source, the Updates category filter is cleared once, and novel
downloads left in the queue wait for Resume when the app opens.

Extensions now install and update only from a repo whose signing key matches, though a repo with no
key still updates the extensions no keyed repo signs. New backups store your edited details in
Komikku and Yōkai's format, so Reikai 0.3.2 restores them without those edits.

Two repairs need a tap. Novels an earlier build saved with another novel's title and cover, or
wrongly as Completed, stay that way until you refresh them or run the repair under Settings ->
Advanced. If a FlareSolverr test left sources looping on a Cloudflare challenge, reset your user
agent under Settings -> Advanced.

### Library

#### Added

- **A new All chip shows your whole library, manga and novels together, and the Manga and Novels chips now filter it.** One list and one sort, with each series opening in its own reader.
- **Categories can now hold manga, novels or both, picked when you create one, and Edit categories is one list covering them all.** An All / Manga / Novels chip narrows the list, and one drag order covers every category.
- **Library search understands field terms and comparisons on manga and novels, like `author:kubo`, `genre:horror -genre:ecchi`, `unread>5` or `chapter:epilogue`.** Terms combine, so `chapter:finale -genre:horror` does what it reads like.
- **The library's three-dot menu can now refresh tracker data for everything you track in one pass, so sorting and filtering by tracker score use current values.**
- **The library's adult-content filter now works on novels too, judging by genre tags, which is less reliable than the manga filter.**
- **On the Edit categories screen, long-pressing a second category now selects everything between the two.** Long-press one you have already picked to drop it again.
- **With categories in manual order, each card on the Edit categories screen now has a menu to move it straight to the top or bottom, under the Manga and Novels chips too.**

#### Changed

- **Upgrading to this version merges any manga or novel you have twice from the same source into one, keeping the library copy with your read chapters, history, categories, tracking, custom cover and downloads (partly from Mihon).** Upstream: mihonapp/mihon#3805.
- **Manga and novels now share one library sort, filter set and grouping, and on upgrade your novel library takes on the manga library's.** Set any of them under either chip and both follow; per-category sorts are untouched.
- **Library search's `src:` now matches a source's name instead of its id; use `srcid:` for the id, and `id=5` finds an entry by its own id, on manga and novels (partly from Mihon).** Upstream: mihonapp/mihon#3554.
- **Empty categories are now always hidden on every chip, and the "Show empty categories while filtering" option is gone.** The "Show number of items" setting now applies to novels too.
- **Failed manga and novel updates and adult-source update checks are now recorded by default; turn it off with Track update errors under Settings -> Library -> Global update · Manga or · Novels.** The notification opens the list of what failed, or one shared log file when recording is off.
- **The Default category now follows your global library sort instead of keeping a sort of its own.** It is one bucket shared by both libraries, so it cannot be sorted two ways at once.
- **A category you collapse now stays collapsed on both the Manga and Novels chips, and after a restart.** In the novel library it used to spring back open whenever you left.
- **Pulling down to update the library keeps its spinner until the update finishes.** The spinner also shows while a scheduled update runs.
- **Novels whose source has no icon now show a same-site source's icon or the generic source badge, as manga do.**
- **An empty novel library now links to the getting-started guide.**
- **Selected cards in Settings -> Library -> Edit categories now use the same highlight as every other selection.**

#### Fixed

- **The app no longer freezes on the Library while light-novel plugins are being set up.** It could hang long enough for Android to offer to close it, most often on a slow or freshly started device.
- **Typing quickly into the Library, Recents or a source's catalogue search no longer scrambles or drops characters.**
- **Backing out of the category picker no longer adds a novel anyway, and a failed add no longer leaves a manga or novel filed under a category it never joined.** Nothing is written until the add completes.
- **Hidden chapters are no longer opened by the library's continue button or Recents, or queued by downloads from a library selection, on manga and novels.** A hidden chapter still opens when it is the only one left unread.
- **Searching from another screen, Open random entry, Update category and a second tap on the Library button now act on the library chip you are looking at, not always on manga.**
- **In the single-list view, library actions now act on the category you have scrolled to instead of the first one.** That covers Select all, Invert selection, Update category, Open random entry and the hopper's long-press sort.
- **Bulk actions on selected novels now always run to the end, even if the app closes mid-action, as manga's do.** That covers marking read, changing categories, downloading and removing.
- **The novel library no longer slows down while you select novels or type in its search.** Search now waits for a short pause first, like the manga library.
- **Novel library sorting now matches manga: ties stay A to Z under a descending sort, fully-read novels sink under the unread sort, and titles order by your device language.**
- **Library badges no longer cover the unread count or squeeze the title, on covers and list rows.** A grouped series with 408 unread could read as "4"; the source icons now give way first.
- **Novels now show their real language code in the library, and keep it after their source is uninstalled.** Polish and Portuguese no longer share one "Po" group, and `language:` search and group by language keep working.
- **A novel whose source is no longer installed now shows the missing-source warning on its library cover, as a manga does.** On a grouped novel the uninstalled source keeps its place among the source icons.
- **Library search now finds an entry by the title, author, artist, description or genre you set in Edit info.**
- **The continue button on a novel in the library now follows that novel's chapter filters, as manga does.** Set to bookmarked or downloaded chapters only, it opened the first unread chapter regardless.
- **Clearing a novel's history now drops it in the library's Last read sort, as it does for manga.** Novels read before history was kept carry their place over on upgrade and from older backups.
- **Downloaded badges now notice chapters you delete outside the app.**
- **Every category picker now follows your category sort order, on manga and novels.** Adding from Browse, global search, History, a bulk selection, Related manga's See all, a series' own Edit categories or the Updates and History category filter listed them in database order.
- **The library's Change categories action now lists hidden categories, so manga can be moved into one.**
- **Grouping the library by tag or author no longer splits one tag into two groups when sources spell it differently, like Adult and ADULT.**
- **Grouping the library by source now shows real source names on the category tabs, not the raw internal key.**
- **A new-chapters notification now counts unnumbered chapters in its "and N more", and no longer counts a merged series' repeated chapter number as an extra.**
- **The library update no longer refetches a finished adult-source series you have read, and the series keeps the description and status its source gives it.**
- **The novel library-update and download category filters now include the Default (uncategorized) group, as manga's do.**
- **Deleting a category now clears it from the library and Updates filters and from your collapsed categories.**
- **Undo in Settings -> Library -> Edit categories now restores only the categories that delete removed, even after a second delete.**
- **Settings -> Library -> Preferred sources lists the local source again, names each source's language in the order Browse uses, starts right under its tabs, and moves a source on every Up or Down tap, on manga and novels.** An uninstalled source used to swallow the tap.
- **The library filter icon no longer lights up for a custom-interval filter whose update restriction is off.**
- **A category you set back to the global sort now stays that way if the app is closed partway through updating from an older version.**

### Merged series

#### Added

- **Drag a source to the top of Manage sources on a series' page to make it lead that merged series.** Reset order returns it to your Preferred sources ranking.

#### Changed

- **Adding a series that shares a title with one in your library now asks whether to group them instead of grouping them on its own, unless Settings -> Library -> Suggest grouping same-titled series is off.** It asks from Browse, global search and History, with a separate switch for manga and novels.
- **Grouping a series across sources can now be turned off, under "Group series across sources" in the library display menu or Settings -> Library.** Off shows each source as its own library entry.
- **On a merged series, the library, the series' page and History now open the whole group in the reader, while Updates, a source chip and new-chapter notifications open only that source.**
- **Reading a chapter now marks its duplicates read by default, in the same series and on a merged series' other sources, under Settings -> Library -> Mark duplicate read chapter as read.**
- **Removing a merged series from your library now ticks "All grouped sources" by default.** Untick it to remove only the source shown on the cover.
- **Settings -> Advanced now has one "Clear all merges" action per content type, replacing Clear manual merges and Separate all merged series.**

#### Fixed

- **Reading or bookmarking a chapter on a merged series now marks that same chapter on every source, and Skip chapters marked read and the Unread and Bookmarked filters follow it.** A bookmark set before merging also shows in the combined list.
- **A merged series' chapter list now reads straight down on manga and novels instead of alternating between its sources, and a merged novel no longer lists a number-only chapter twice.**
- **Missing chapter warnings no longer invent gaps on a merged series, in the chapter list or between chapters in the reader, on manga and novels.**
- **A merged series' library badge now counts each unread chapter once across all its sources, and the Unread, Started and Bookmarked filters, the sorts, search and the Continue button count the whole group too.**
- **Marking a merged series read from the library, or changing its categories, now applies to every source in the group.** A category only some of its sources are in shows as partly ticked and is left alone unless you change it.
- **A merged series now downloads each chapter once and opens a downloaded copy from any of its sources instead of going online.** Deleting a chapter from the All list removes every source's copy.
- **A merged series now counts once in new-chapter notifications and the Updates widget, instead of once per source.**
- **Merged rows in Updates and History now show and change read, bookmark and download state for every source, and Continue reading from History opens the chapter the library would.** An Updates row's download is its own source's copy, since that is the copy it opens.
- **Selecting a source chip on a merged series now switches the synopsis and tags to that source, and Share and Open in WebView follow it too.** Migrate asks which source to move whichever chip is selected, and your custom title stays visible.
- **On a merged manga, the download controls, Share and Open in WebView now follow the selected source chip's own source, so a chip on a missing extension no longer offers them.**
- **On a merged series, tapping the cover now shows the selected source's cover, and changing the cover is done under the All chip.** Your library shows the group's cover, so an edit made under one source would have looked like it did nothing.
- **Library search now finds a merged series by any of its sources' names, ids or languages, not only its leading source's.** That includes the search a source chip opens.
- **A merged series' library cover, title and badge now come from the same source its chapter list leads with.**
- **A merged series whose top-ranked source has no chapters now lists its other sources' chapters in full.**
- **A source you remove from a merged series now leaves it completely: its chapters stop showing there, and it opens on its own from History or Browse.**
- **Incognito on one source of a merged series now covers only that source's chapters, whichever source you opened the series from.** A private source's chapters stay out of History and your trackers.
- **A merged manga's scanlator filter now covers the source chip you have selected, or every source under All.**
- **Splitting off or removing the source you are viewing no longer leaves the series' page showing another source's chapters.**
- **Migrating away the source whose chip is selected no longer crashes a merged manga's chapter list or leaves a merged novel showing the old source.**
- **The remove dialog's "All grouped sources" count now covers only the sources behind the merged series you selected.**
- **Continuing a merged series from History on another source's copy of a chapter no longer shows that chapter twice in the reader.** The chapters before and after it are the right ones too.
- **Saving Edit info on a merged novel with a source chip selected no longer stores that source's details as your own edits.**
- **A hidden chapter of a merged novel now stays hidden, and skipped by the reader, when its source is not installed.**
- **A merged novel's combined chapter list no longer hides a chapter whose title differs only by a trailing number.**
- **The heart on a merged series' page now removes the source chip you have selected, and under All asks first with "All grouped sources" ticked, on manga and novels.** Before, it always removed the source you opened the page from.
- **Removing a source in Manage sources now offers to delete its downloads once its Undo is gone, as the heart on the series' page does.**

### Updates & History

#### Added

- **Settings -> Appearance -> Combine Updates and History now merges the two tabs into one Recents tab, off until you turn it on.** Its Grouped and Feed views show what to read next and leave out series you are caught up on, beside History and Updates views.
- **Swiping a new-chapter row in Updates now marks it read, bookmarks it or downloads it, on manga and novels, using your chapter swipe actions under Settings -> Library.**
- **The Updates feed can now be searched by title, like History.**
- **History can now be filtered by category, with its own selection separate from Updates.**
- **The Upcoming calendar can now be filtered by category (from Mihon).** Exclude the categories you don't follow closely and the calendar shows only the rest. Upstream: mihonapp/mihon#3607.
- **With Group by series on, a long press on an Updates group now selects everything between it and your last pick, that whole group included.**

#### Changed

- **The Updates category filter is now one list covering manga and novels, so the category pick you had there is cleared on upgrade, and a manga-only category now hides novels.**
- **Updates and History now say when your filter is what emptied the list, with a button that opens the filter.**
- **A manga chapter you have opened now shows its length on Updates and History rows and in the chapter list, as "Page: 5/38".** Novels already showed a percentage.

#### Fixed

- **History rows can now be long-pressed for bulk actions and carry a download button, with the chapter, the time and your place in it each on its own line.** Each row says whether it was updated, read or added.
- **Tapping History again now resumes the most recent thing you read on the library chip you are looking at, whatever you have typed in its search.**
- **Pull to refresh on Updates now spins until the library update has finished.**
- **History and Updates now stay at the top when new rows arrive, if that is where you were.**
- **Moving a series to another category now updates a category-filtered Updates feed straight away.**
- **The combined Updates widget now drops a novel once you have read its new chapters.**
- **Deleting a manga chapter's download from Updates or History now works after its extension is uninstalled.**
- **With Group by series on, an Updates group you expanded now stays open when the screen rotates.**
- **A new-chapter notification for an adult series that gained a newer version now opens that chapter, and its Mark as read and Download actions work.**

### Details

#### Added

- **Holding a series' title, author, artist or source name now offers a library search instead of only copying, and tapping the source name browses that source (partly from Mihon).** Title, author and artist also search all sources; the source name does not on a merged series under All. Upstream: mihonapp/mihon#4002.
- **A series' details overflow can now open its download folder, clear its downloaded chapters and open its source's settings.** Each shows only when it applies and follows the source you are viewing on a merged series; clearing leaves your progress, bookmarks and history alone.
- **The chapter list on a merged series now says which source each chapter came from, on manga and novels.**
- **Related-manga suggestions now label where each one came from, in both the row and the full grid.** The source, the tracker, or the taste reason behind the pick.
- **Settings -> Recommendations -> Related manga placement can now move the related manga row off the details page and into its three-dot menu.**
- **A novel's page now hands its web link to the Android assistant and the recents screen, as a manga's does.**

#### Changed

- **A manga or novel's page now shows the outline of what it is loading instead of a spinner.**
- **Searching a genre from a novel's page now goes back to its source's catalogue with that genre filtered, as manga does.** Opened from anywhere else, it searches your library.
- **The cover viewer now offers Edit only for series in your library or on local storage, where a custom cover is kept.**
- **Set as default in a novel's chapter settings now asks first and can apply the settings to your whole library, as manga's does.**
- **A novel opened from a source, a search or the feed now shows its synopsis expanded on a phone, as manga does.**
- **Sorting a chapter list "By source" now really follows that source's own listing, on manga and novels.** Pick "By chapter number" for the old order.

#### Fixed

- **Chapter selection on a manga or novel page now acts only on the chapters your filters show, and extending a range no longer re-adds a chapter you deselected.** Mark previous as read follows your filters too, and range selection works the same on both.
- **A series whose site shows a placeholder until its cover loads now keeps its real cover.** A series already stuck on the placeholder takes its cover back from its source's listing.
- **Refreshing a manga no longer blanks its author, artist, description or status when its source sends none.**
- **Removing a novel from the library on its page now offers to delete its downloaded chapters, as manga does.** Removing a novel from anywhere also clears its saved cover, a custom one included.
- **A novel's page now warns when its plugin is uninstalled, names the plugin as it was last seen and hides downloads, as a manga's page does for a missing extension.**
- **Turning incognito off now closes a novel's page opened from a source, as it already did a manga's.**
- **When two manga chapters share a number, upload date or name, Resume, Continue reading and Download next now pick the one the reader opens next.**
- **The full-screen cover viewer, Save and Share now use the cover URL you set in Edit info.**
- **Reset all in Edit info now also clears a cover you set by hand, on manga and novels.**
- **Pulling down to refresh a novel's page now downloads its cover again, fixing a broken one, as it already did for manga.**
- **A novel's page now shows its artist, copies its link when you long-press WebView, and shares through the same titled share sheet, as a manga's does.**
- **Page previews on an adult source's details page no longer go blank over time.**
- **Tapping a tag on a series from an adult source or an enhanced source now searches that source in its own tag format, so the search finds results.**
- **Removing an adult-source series from your library and your account favorites now keeps it in the library if the account removal fails.** A message says why, so you can try again.
- **Closing Edit info while Fill from tracker is still loading no longer shows a tracker error.**
- **Related-manga suggestions no longer shrink while they refresh, or disappear when a refresh fails offline.**
- **A related-manga suggestion already in your library is now dimmed and badged even when its source lists it under a different link.**
- **Related-manga suggestions now rank more fairly: tracker picks keep up to 12 places in the row, and a series tracked on several services or a title listing a genre twice no longer counts double.**
- **The related-manga Hide dropped, Hide on-hold and Hide plan-to-read filters now work with every manga tracker.**
- **Turning off tracker recommendations or the taste suggestions in Settings -> Recommendations now removes them from a related row you already opened.**
- **Refresh now in Settings -> Recommendations now starts at once and keeps pulling your tracker libraries after you leave the screen.**
- **The full related-manga grid now says when your filters hide every suggestion, with a button to show them.**
- **The full related-manga grid now follows Items per row while open, range-selects only the covers between the two you press when grouped, and counts only titles that reached your library.**

### Reader

#### Added

- **Manga pages can now be drawn by a new high quality renderer, switched on under Settings -> Advanced (from Mihon).** Adds dual page view, page transitions, a display cutout mode, HDR, pages that fill in as they download, and long strip Min width and Gap sliders. Upstream: mihonapp/mihon#3388, mihonapp/mihon#4029.
- **The novel reader now reads straight on into the next and previous chapters, which Settings -> Novel reader -> Continuous chapters can switch off.** A marker names each boundary, and Add the next chapter at sets how far in the next one appears, 95% by default.
- **Manga can now auto-scroll, turning pages on a timer or scrolling long strips smoothly, set up under Settings -> Manga reader.** Start it from the Auto-scroll button on the bottom bar or the reader's Controls tab; it waits on a page that is still loading.
- **Settings -> Novel reader now picks its font on its own screen, where you can search the whole Google Fonts library or import a file.** Every font's row previews itself, and what you add works in both rendering modes.
- **Settings -> Novel reader can now find and replace text in a chapter before you read it.** Each rule matches plain text or a pattern, and a sample box shows what it would do before you save it.
- **Novel read-aloud now starts from a Read aloud button on the reader's bar and has a sleep timer, with the time left shown in its notification.** Its floating controls read from the paragraph on screen and step between paragraphs.
- **Novel read-aloud can now highlight the sentence being read instead of the paragraph, in a style and colours you set under Settings -> Novel reader.** With sentences, Next and Previous step by sentence too.
- **A manga or novel chapter that fails to load now offers Retry and Open in WebView, where the manga reader used to close on a brief message.** For a source that takes pages, saving the page there opens the chapter.
- **Both readers' bottom bar buttons can now be put in order as well as picked, under Settings -> Manga reader and Settings -> Novel reader or from Edit bottom bar in the reader's top menu.** Drag a button by its handle; the settings button is always shown.
- **Both readers' bars can now name a chapter by its number, or by number and name, under Settings -> Manga reader, Settings -> Novel reader or the reader's Appearance tab.** A number the chapter's name already opens with is not shown twice.
- **The reader's top menu can now reload the open chapter where you are, from its downloaded copy or fresh from the source.**
- **Settings -> Novel reader can switch on selecting, copying and sharing text in the novel reader, which costs link taps in native text mode.** Every other gesture keeps working while it is on.
- **The novel reader's menu now hides when you scroll the page, as long-strip manga's does, with its sensitivity under Settings -> Novel reader -> Navigation.**
- **Settings -> Novel reader and the novel reader's Appearance tab now have Fullscreen and Show content in cutout area switches, both on by default.**
- **Novel chapters in the WebView rendering mode now show the styling their light-novel plugin ships, once the plugin is updated or reinstalled.**
- **Settings -> Manga reader and Settings -> Novel reader can now hide the chapter navigator, which moves the chapter buttons to the ends of the button bar.**
- **Both readers can now put a Scroll to top button on the bottom bar, and the manga reader a Keep screen on button, as the novel reader already could.**
- **Settings -> Novel reader now has a Scroll speed slider for auto-scroll, and novel auto-scroll now moves smoothly at that speed.**

#### Changed

- **The reader's settings button now opens the same sheet for manga and novels, with Reading, Appearance, Controls and Filters tabs, plus Read aloud for novels.** Manga keeps every setting it had, and a novel gets its own, rendering mode and text selection among them.
- **Reader settings are now two entries, Settings -> Manga reader and Settings -> Novel reader, each holding only that reader's options.**
- **Manhwa, manhua and webtoons now open in webtoon mode by default, which Settings -> Manga reader can switch off.** The genres from Edit info, any source of a merged series or the source's name decide it, so a series nothing marks as long strip keeps your default mode.
- **Novels now keep their own brightness, colour filter, grayscale and inverted colours, set from the novel reader's Filters tab.** A brightness or colour filter set in the old novel reader comes back.
- **Auto-scroll in either reader now pauses while your finger is on the screen or a sheet is open over the page, and scrubbing the chapter pauses it a moment instead of turning it off.**
- **Novel auto-scroll now starts by itself only when Settings -> Novel reader -> Start auto-scroll when opening a chapter is on, which it is if you had left auto-scroll on.** The bottom bar button and the Controls tab start or stop it without changing that setting.
- **Novel chapters now follow manga's delete settings: finishing one in the reader no longer deletes it under "After manually marked as read", and one "After reading automatically delete" removes stays downloaded until you leave the reader.**
- **The reader's quick reading-mode and rotation menus now highlight the mode you are reading in, and just opening one no longer sets that mode for the series.**
- **The manga reader's chapter list now shows the page you stopped on in a chapter you have started, as the details screen does.**
- **With Theme based on cover on, the novel reader's bars now take the novel's cover colours, as manga's do.**
- **The novel reader's button bar now starts with text size and theme buttons, unless you have already chosen its buttons.**
- **The hardware bitmap threshold, legacy long strip decoding and custom display profile settings are gone from Settings -> Advanced (from Mihon).** Upstream: mihonapp/mihon#3786.

#### Fixed

- **Read chapters no longer vanish from the manga reader's chapter list, and swiping back from a chapter you just finished reaches the previous one, read or not.** Tapping a read chapter in the list opens it.
- **Download ahead in both readers now fetches only the chapters the reader will actually reach next.** It took read chapters on novels, hidden chapters and skipped duplicates on manga, and on a grouped manga the order its sources were stitched in rather than your chapter sort.
- **Skip duplicate chapters now removes duplicates from a novel's chapter list as it does for manga, and no longer folds chapters with no number, like a prologue and an afterword, into one in either reader.**
- **Each chapter you open in the manga reader now starts where you left that chapter, not where you left the one before it.** Most visible right after jumping in from a page preview.
- **The manga reader now names the chapter you are actually on while you scroll across a chapter boundary.**
- **Novel read-aloud now carries on from the paragraph it was on when you rotate the screen or change a text setting.**
- **Novel auto-scroll now carries on by itself into the next chapter and after a rotation.**
- **Rotating the screen while a chapter is opening no longer leaves the reader stuck loading (from Mihon).** Upstream: mihonapp/mihon#3686.
- **Swiping a chapter in either reader's chapter list now runs your configured swipe action instead of always bookmarking.**
- **Skipping past a novel chapter with Settings -> Novel reader -> Mark chapter read when skipping ahead on now finishes it as reading to the end does, deleting older downloads and marking a merged novel's other copies, without holding up the next chapter.**
- **With Mark chapter read when skipping ahead on (Settings -> Manga reader or Novel reader), a Next that fails to load no longer marks the chapter you are still on as read.**
- **A chapter step in the manga reader that fails to load, or has no chapter to go to, no longer sends you back to page 1.**
- **Bookmarking or marking a novel chapter read just before closing the reader is no longer lost, and a grouped novel's copies are bookmarked together.**
- **Reading time in History is no longer counted twice for one reading session, on manga and novels (from Mihon).**
- **On a grouped manga, the reader's Open in browser, Open in WebView and Share now use the site the chapter came from.**
- **Scrolling into the next manga chapter and straight back no longer leaves the bookmark button and Open in WebView acting on the chapter you left.**
- **Retrying a manga page that failed to load, or is stuck loading, now always fetches it again (partly from Mihon).** Upstream: mihonapp/mihon#3770.
- **The manga long strip no longer leaves a gap after zooming in a resized or split-screen window (from Mihon).** Upstream: mihonapp/mihon#1721.
- **Manga chapters from an excluded scanlator now open from History and Updates (from Mihon).** The reader's chapter list still leaves the excluded scanlator's other chapters out.
- **The novel reader's voice list now follows the read-aloud engine you pick.**
- **The novel reader's chapter list now opens quickly on a grouped novel.**
- **The novel reader's vertical chapter navigator now takes its side and height from Settings -> Novel reader, not from the manga reader's settings.**
- **A novel chapter that fails to load no longer shows in History as the one you read last.**
- **A novel showing chapter numbers instead of titles now labels them in your app language, like manga.**

### Light novels

#### Added

- **The previous novel reader is gone: novels now open in the same reader manga uses, drawn as real text or as a web page under Settings -> Novel reader -> Rendering mode.** A mode change applies the next time you open a chapter.
- **The novel reader now has the manga reader's tap zones plus top and bottom, center and bottom-only layouts, in its Controls tab and Settings -> Novel reader.** Zones can be inverted and shown on the page, and Tap edges to scroll carries over as Top and bottom.
- **Novel read-aloud now pauses for calls, other apps' audio and unplugged headphones, and answers headset buttons, in every rendering mode.** It resumes by itself only after a short interruption, and never starts over a call.
- **Settings -> Novel reader -> Text display now sets your page margins, paragraph indent up to 10em and paragraph spacing.** Each of the four margins moves on its own, and a page padding you had already set becomes your left and right margins.
- **The novel reader has a new near-black theme, and its page background and text can each be set to any colour from the reader's Appearance tab.**
- **Settings -> Novel reader can now tidy up a chapter before you read it.** Hide a heading that just repeats the chapter name, block images and video, split walls of text into paragraphs every 20 to 2000 words, force lowercase, and choose whether a chapter's own styling runs.
- **Settings -> Novel reader can now skip chapters marked read and skip filtered chapters going forward, like manga, with Skip filtered chapters on by default.** The previous-chapter button still reaches the chapter you just finished.
- **A novel's details page can now search the text of every downloaded chapter, from its overflow menu (from Tsundoku).** Matches show in context and open the chapter. Upstream: tsundoku-otaku/tsundoku#433.
- **A novel's details page can now count the words in its downloaded chapters and rate how long they run (from Tsundoku).** Upstream: tsundoku-otaku/tsundoku#434.
- **Novels now show their predicted next release on the details page, and Smart update under Settings -> Library -> Global update · Novels gains Predict next release time.** With it on, a library update skips novels outside their release period.
- **Novel new-chapter notifications now work like manga's: they show the cover and the Reikai icon, name the new chapters with Mark as read and Download, and open the chapter when tapped.** The summary lists the novels that updated.
- **Updating your novel library now shows how far along it is, as a percentage, as manga's does.**
- **A novel from an IReader extension can now take its details, chapters or a chapter's text from a page you open in its WebView menu.** Use it for a site that blocks the app.
- **Adding a duplicate novel now gives you a one-tap Migrate, moving progress, categories, cover and tracking to the new source.**
- **Clear database now also removes novels that aren't in your library.** Novel sources get their own rows, and the keep-read toggle protects novels with reading progress, like manga.
- **A novel's chapter list can now be sorted alphabetically, the fourth sort manga already had, and a newly picked sort starts ascending, as on manga.**
- **Light novel chapters now show their translation group on the details page and in the reader's chapter list.**
- **Settings -> Novel reader can now set how far into a chapter a novel counts it as read, from 50% to 100%.** It stays at 97% until you change it.
- **Settings -> Novel reader can now swap the vertical chapter navigator for a horizontal slider above the bar's buttons.**
- **The novel web page reader can now add your own CSS and JavaScript snippets to every chapter, under Settings -> Novel reader.** JavaScript snippets restored from a backup come back switched off.
- **Settings -> Novel reader can now show a chapter's raw HTML as text, in either rendering mode.** It helps tell a source's broken markup apart from a reader problem.
- **Settings -> Advanced has a switch, off by default, that opens every web page in the app to a computer's browser inspector and shows the novel reader's script errors as toasts.**

#### Changed

- **In the web page rendering mode, embedded frames in a novel chapter are now always stripped, and its scripts and tap handlers too unless Settings -> Novel reader -> Run scripts a chapter embeds is on.**
- **A novel chapter you have already read now opens at its start, like manga, unless Settings -> Novel reader -> Resume reading position is on.**
- **A slow novel source can no longer stall global search, browsing or updates for every other source.**
- **A library novel now keeps its title when its source renames it, unless Settings -> Advanced -> Update library titles to match source is on, as for manga.** With it on, the novel's downloaded chapters move to the new title.
- **Bulk-deleting downloaded novel chapters now asks you to confirm first, like manga.**
- **Share on a novel's details page now sits in the menu, as on manga.**
- **Novel text size now goes from 10 to 40, and line spacing from 0.8x to 5x.**
- **Settings -> Novel reader -> Default rotation now offers Reverse portrait, as the manga reader does.**

#### Fixed

- **Updating your novel library can no longer save one novel's title and cover onto another, and Settings -> Advanced can now repair novels it already hit.** The repair finds the affected novels and re-fetches each from its own source.
- **A read novel chapter no longer comes back unread, or announced and downloaded as new, when its source moves it to a new address or another page of the chapter list.** It keeps its read state and bookmark.
- **A new novel chapter numbered like one you already read now arrives read when the duplicate-chapter setting asks for it, even from another page of the chapter list, and is not announced or downloaded as new.**
- **The novel reader, resuming and next-chapter downloads now follow the order you sorted a novel's chapter list into.**
- **Time spent reading a novel now keeps counting after you switch away and come back.**
- **Ongoing novels from some light-novel sources no longer show as Completed or get skipped by library updates.** Refresh an affected novel to correct the status it was saved with.
- **Light novels a source marks Inactive or Stub now show On hiatus or Licensed instead of Unknown.** A source's Inactive status filter shows up again too.
- **A novel library update no longer undoes a change you make to one of its novels while it runs, such as removing it from the library or editing its notes.**
- **Pulling down to refresh a novel now downloads its new chapters when Download new chapters is on, as it does for manga.**
- **Opening a downloaded novel chapter no longer freezes the reader while it loads, most of all on chapters with pictures.**
- **A link inside a novel chapter now opens in your browser instead of taking over the reader.** A jump to a footnote inside the chapter still works.
- **Read aloud in a novel no longer skips a very long paragraph.** It reads the whole paragraph, broken at its sentences.
- **A novel chapter its source returns empty now says so and offers a retry, instead of opening as a blank page.**
- **A novel chapter with a stray plaintext tag no longer breaks the reader page.**
- **On a merged novel, downloading from the All chip now downloads the chapters All is showing.**
- **Covers and chapter pictures from light-novel sources that ask for their own image headers now load, in both rendering modes and in downloads.** The web page mode also reuses the pictures the text mode already downloaded.
- **Signing in to a site in WebView now signs in light-novel plugins that read the site's saved login rather than its cookies.** Close the WebView after signing in and the plugin picks it up on its next request.
- **WebView and Share on a light-novel plugin's novel now open the page the plugin names for it.**
- **Adding a novel from its details page now files it in your default novel category, and every novel screen uses manga's category picker, with its Edit categories shortcut.** With no categories yet, the picker offers to make one.
- **Refreshing a novel or updating the novel library now says why a novel failed, as manga does, including No chapters found and a source that is no longer installed.**
- **A light-novel source that is no longer installed now shows by its name instead of its id, marked Not installed in your language, in its catalogue, on the novel page, in the reader, in a merged novel's source switcher, on the Migrate tab, in the migration list, in the download queue and in update failures.**
- **A novel's new-chapter notification no longer replaces or dismisses a manga's, a big novel library update keeps its summary and leaves no stray status-bar icon, and tapping the novel summary now opens Updates, as manga's does.**
- **Updating the novel library now tells you when an update is already running, instead of claiming it started a new one.**
- **A novel filter that matches nothing no longer says your library is empty.**
- **Hiding chapter titles on a novel no longer changes its chapter order, and sorting its chapters no longer changes how their titles show.** Each follows your global default until you change it on that novel.
- **Novel chapter dates now hold: a refresh keeps a date the source stops giving, an undated new chapter gets one as on manga, and a month-first date like 12/25/2024 reads correctly.** A date that is not a real day shows no date rather than a rolled-over one.
- **Novel chapter names no longer repeat the novel's title in front.**
- **Light novel names in Browse and search results no longer show raw codes like &amp;.** Before, these codes showed in result rows until the novel was opened.
- **A light novel's title is no longer replaced by a source's "No Title Found" or "Untitled" placeholder.**
- **Downloading a selection of novel chapters no longer fetches the ones already downloaded again.**
- **Novel updates set to Wi-Fi only no longer run on mobile data on Android 8.**
- **Smart update under Settings -> Library -> Global update · Novels now lists its options in the same order as the manga one.**

### Browse & sources

#### Added

- **Browse can now show a Feed tab, turned on under Settings -> Browse and sources -> Show Feed tab, with up to twenty rows of covers, one per source or saved search, that you can drag into order.** A long press removes a row.
- **Any source's filters can now be saved as a named search and re-applied from a chip while you browse that source.** Long-press the chip to delete the search.
- **A manga or novel link shared into Reikai now opens that series when an installed source serves its site, and anything else shared or searched from another app searches manga and novels across all your sources.**
- **Sites that block the app but let its built-in browser in now load without a FlareSolverr server.**
- **Settings -> Advanced -> Solve interactive Cloudflare challenges ticks the verification box for you instead of giving up, and a second switch lets background library updates do the same.**
- **Pick several covers across the Feed's rows and add them to your library together, manga and light novels in one batch.** Each is filed into its own categories.
- **Browse -> Sources now has a search box that filters the list by source name, extension name or id.** Separate terms with commas to match any of them.
- **Novel sources can now be hidden per language, from the switch each language carries in the sources filter.** Switching one off hides all its sources from Browse and search, like manga.
- **Long-press a source in Browse -> Sources to turn incognito mode on for it, light-novel sources included, or for its whole extension on a manga source.**
- **A FlareSolverr server behind a password now works over https or on your own network: sign in under Settings -> Advanced -> FlareSolverr sign-in.** A backup carries the sign-in only with Include sensitive settings turned on.
- **Source catalogues in Browse now offer the panorama comfortable grid, which shows wide covers whole.**
- **Settings -> Browse and sources can now hide the Latest button on Browse -> Sources rows.** Latest stays one tap away inside each source.

#### Changed

- **Browse -> Sources, Extensions and Migrate now each show manga and light-novel sources in one list, grouped by language, with the chips filtering that list and each row saying which kind it is.** Extensions share one Update all for pending updates, and Migrate's sort controls cover the whole list.
- **Global search now searches manga and light-novel sources in one run, with All / Manga / Novels tabs, and a selection can add manga and novels to your library together.** Categories are asked for once per kind, since the two libraries keep their own.
- **Settings -> Browse and sources now picks which extensions load by content warning, Safe, Mixed or 18+, instead of one NSFW switch, and your NSFW choice carries over (from Mihon).** Changes apply without a restart, and installed ones can be left alone. Upstream: mihonapp/mihon#3951, mihonapp/mihon#3952.
- **The Sources list now keeps one "Last used" source across manga and light novels, and it starts empty after this update until you next open a source.** Opening a source while incognito leaves it unchanged.
- **Installed extensions and light-novel plugins that fail to load now appear under Not loaded in Browse -> Extensions (partly from Mihon).** Tap one to see why, copy the error, or uninstall it. Upstream: mihonapp/mihon#3953.
- **Rows in Browse -> Sources now show a flag beside the language, the extension name when a source is named differently, and an 18+ or Mixed label on extensions that declare a content warning, which light-novel plugins never do.**
- **The Repos screen is now one list of cards showing how many extensions or plugins each repo lists, or that it couldn't be reached.** Add repo works out whether an address is an extension store or a novel plugin repo, and turns down one it can't read.
- **Browsing a light-novel source now offers the same toolbar and grid column count as a manga source.** Search, display mode, Select, Open in WebView and the source settings sit in the same places on either.
- **Global search now remembers whether you last searched pinned or all sources, and says when nothing is pinned or nothing was found instead of showing a blank screen.** With nothing pinned it offers to search all sources.
- **Choosing what a manga migrates to now browses the source the normal way, with chips, filters and your grid layout, as light novels already did.**
- **Backing out of a source's search now returns to the source's listing instead of leaving the source.**
- **A light-novel source now offers Latest only when it can really list latest, instead of quietly repeating Popular.** About half the plugins cannot.
- **The duplicate warning when adding a novel now matches manga's: it catches a library novel tracked to the same tracker entry, shows the artist and flags a source that is no longer installed.**
- **Opening an adult-source series whose older version is already in your library now opens that library copy.**

#### Fixed

- **Some extensions no longer crash the app while searching or browsing (from Mihon).** Upstream: mihonapp/mihon#4027.
- **Global search no longer crashes on a result a source lists twice, leaves a finished source spinning, or searches fewer sources when run just after the app opens.**
- **Manga sources that work out their pages with JavaScript now show those pages again, where some chapters opened empty or failed to load.**
- **Scrolling to the end of a source's catalogue no longer shows a "No results found" error over the titles already listed.**
- **Browsing a source, searching in Global search, the feed or a migration, or opening a series while offline now says "No Internet connection" instead of a raw host error, on manga and novels.** The manga reader's failed pages say it too.
- **Adding a manga that is already in your library, from global search, the feed or a browse list, no longer resets its date added or chapter settings.**
- **A manga added from Browse, global search or the feed, and any entry added from a recommendations list, batch add, a shared link or a follows sync, now takes your default chapter settings (partly from Mihon).** The last three also file it in your default category, and re-adding one keeps its date added.
- **The Browse sources filter now covers manga and light novels from any chip, with a Manga / Novels switch between the two halves.**
- **Testing FlareSolverr no longer leaves sources looping on a Cloudflare challenge, and resetting your user agent under Settings -> Advanced fixes one that already is.**
- **Open in WebView now opens the page a Cloudflare challenge blocked, on manga and novels, and manga browse reloads by itself when you come back.**
- **Sites FlareSolverr once unblocked load again after turning FlareSolverr off, without restarting the app.**
- **Pages fetched through a FlareSolverr server on your own network or over HTTPS now come back signed in to the site.** Your cookies are never sent to a solver reached in the clear over the internet.
- **Forms a source posts and pages it fetches through FlareSolverr now arrive intact, so a novel plugin's chapter list loads there.**
- **A FlareSolverr solve that takes over a minute now finishes, and Test calls a slow server slow rather than unreachable.**
- **Testing FlareSolverr now names what went wrong on the row itself, including a solver that is still starting.** The server's own words are a tap away and can be copied.
- **The Cloudflare bypass now gives up in seconds on a challenge the site abandons or a browser process that dies, instead of after half a minute, and a dying browser no longer risks taking the app down.**
- **Clearing a site's cookies in the WebView now removes the ones it shares with its subdomains, so a failed Cloudflare bypass no longer spoils the next request to that site.**
- **Browse, global search and the feed now show your custom cover on a novel in your library, as they do for manga.**
- **Adding a light novel from Browse now keeps the cover already loaded instead of downloading it again.**
- **The Hide entries already in library setting now applies to novel sources too.** Browsing keeps loading further pages when everything on a page is already in your library.
- **Peeking at a possible duplicate no longer throws away the add you were making.** Long-press opens it, and the same question is waiting when you come back.
- **Adding a manga no longer flags an unrelated library manga as a duplicate because both are tracked on a tracker that gives no entry id (from Mihon).** Upstream: mihonapp/mihon#4008.
- **Opening a title from Browse no longer shows it pre-grouped with same-named titles in your library.**
- **Light-novel sources and plugins now sit under their proper language heading, beside manga sources of that language, and show it as a short code in the migration source picker.**
- **An installed light-novel plugin is no longer listed a second time as available to install when a repo offers it at a second address.**
- **The sources whose details the app enhances, a large mainstream one and several adult ones, now open their settings from the extension list and from their own catalogue.**
- **Favorites backup now reaches your account for adult-source series added from Browse, search, batch add or a shared link.**
- **When an adult source replaces a series with a newer version, chapters the new version already had now keep the old version's read state, bookmark and progress.**
- **The large mainstream source the app enhances now uses the language you set as preferred in its settings for follows sync, tracking and sign-in, and follows its extension's description switches.** The final chapter in the description is on by default, as in the extension.
- **Tapping a follows sync action while one is running now says a sync is already running, instead of silently stopping the first partway.**
- **The adult-source favorites backup now runs one at a time and shows its own progress notification instead of saying the library is updating.**
- **Changing the adult-gallery update checker's restrictions now takes effect straight away instead of after the next interval change.** Its restrictions row also hides while the checker is off, as the library's does.
- **Adult-source series show the same star rating in Browse and on their details page.**
- **Light-novel source icons are no longer larger than manga ones in the same list, most noticeably on the Migrate tab.**
- **Two languages whose codes share one name (such as "in" and "id") no longer lose a section in Browse's lists and source filter, on manga and novels.**
- **Browse -> Extensions now shows its list and clears its search at once, without a short pause.**
- **Settings -> Advanced -> FlareSolverr URL can now be cleared once an address is saved.**

### Migration

#### Added

- **Manga and novels now migrate through one shared flow, with the same screens, search options and safeguards.** Novels gain Additional keywords, Advanced search mode, Match based on chapter number, Hide entries without a match and Hide entries without newer chapters.
- **Matches are now offered rather than assumed: accept them one at a time, or all at once.** Tapping an accepted match gives it back so you can pick a different target.
- **A migration now names the entries that failed and offers to retry them, and says how many entries moved when it finishes.**
- **Search a target by hand, or browse a whole source, when the suggested match is wrong.** Every source you chose is searched, and one that fails says so instead of looking empty.
- **Check a match before you commit to it: tap a batch row's match or long-press any result to open its page.** Anything already in your library is marked, and a match whose latest chapter is behind the entry's shows by how much, in red.
- **Choose what a migration carries at the moment you confirm it, on both manga and novels.** Only the options the selected entries can actually use are offered.
- **Leave an entry out of a migration, so a source that never answers can't hold up the rest.** Skipping takes it off the list, as does migrating it, so what's left is always what still needs you.
- **Long-pressing an entry in Migrate's per-source list now selects every entry between it and the last one you tapped.**

#### Changed

- **Migrating a manga or novel with its chapters now carries your reading history and the page you reached in each chapter.** Its History entries and its place in the library's Last read sort follow it to the new source.
- **Migrating no longer asks the target's source for the same thing twice, roughly halving the load a large migration puts on the site.**
- **Single-entry migration search now refuses a match with no chapters before the migrate dialog opens, as the batch list does.**

#### Fixed

- **Cancelling a migration part-way no longer strands an entry of a merged series outside your library, on manga and novels.**
- **Migrating a novel with "Delete downloaded" now also cancels its queued downloads, so nothing keeps downloading from the old source.**
- **Migrating a novel no longer searches sources or languages you have disabled.**
- **When migrating a light novel, a search result already in your library now shows your library's cover, including a custom one.**

### Tracking

#### Added

- **Light novels can now be tracked on RanobeDB, NovelList and NovelUpdates, three services built for novels.** Sign in through a browser window, or paste a personal access token on RanobeDB; not all of them keep a score, reading dates or an on-hold state.
- **Every RanobeDB write replaces that series' entry on the site, clearing its custom labels, notes and volume count, and chapter progress is never sent.** Binding a novel asks first, and with Update RanobeDB while you read on, reading that moves its status writes without asking.
- **Every tracker search except NovelUpdates can now take an id, written as `id:12345`, and a Kitsu search also takes a title's web-address name, written as `id:shadow-slave` (partly from Mihon).** Works on manga and novels alike. Upstream: mihonapp/mihon#3776, mihonapp/mihon#3792.
- **Extensions that sync reading to their own site now hear what you read, add and remove, on manga and novels, once their own tracking setting is on.** A failed sync shows a message.
- **Migrations are now passed on to extensions that sync reading to their own site, on by default with a switch under Settings -> Tracking.**
- **Novels from the NovelUpdates app or plugin now track on NovelUpdates by themselves while you are signed in, and reading moves your NovelUpdates bookmark to that chapter.** Other sources' chapters move it when their number matches a release; library novels bind in one tap from the tracking dialog.
- **NovelUpdates never moves your bookmark backwards unless you ask: turn off Settings -> Tracking -> Never move progress back to follow rereads, or turn on Settings -> Tracking -> Move back on unread to follow chapters you mark unread.**
- **Settings -> Tracking -> Use my own NovelUpdates lists lets each status move a novel to one of your own NovelUpdates reading lists.** Pick the list for each status under Match statuses to lists.
- **Fill from tracker now works for novels with RanobeDB, NovelList and NovelUpdates, which know novels better than the manga services do.** It fills the description, author, artist and genres.
- **Settings -> Tracking has a refresh button on each tracker you sign in to with an account, so a nickname or score format changed on the site reaches Reikai without signing out.** Works for the light-novel trackers too.

#### Changed

- **Kitsu scores now use whichever rating scale your Kitsu account is set to: smileys, stars or the 10 point decimal (from Mihon).** Existing scores are converted on upgrade, on manga and novels. Upstream: mihonapp/mihon#3818.
- **Marking chapters read no longer announces tracker updates, and names any tracker that failed in one message.** A failed update is still retried in the background.
- **MangaUpdates search results now show each entry's rating and creators while you pick one to bind, on manga and novels (from Mihon).** Upstream: mihonapp/mihon#3795.

#### Fixed

- **A tracker set on one source of a merged series now shows, updates and is removed on all of its sources, unless you turn off Settings -> Tracking -> Share trackers across merged sources.** The library's tracking filter, sort and groups follow the group too, and a split or migration leaves each source its own copy.
- **Reading an older chapter from another source of a merged series can no longer push your tracker's progress backwards.**
- **Reading progress queued for a tracker while offline is no longer dropped when the track is refreshed or restored before it is sent, on manga and novels (from Mihon).**
- **A failed tracker link, update, refresh or Fill from tracker now says why in plain words, such as No Internet connection or Log in to AniList again, and a failed novel link no longer crashes the app.**
- **Refreshing, searching or filling from Bangumi, MangaBaka or Hikka while signed out no longer crashes the app.** Signed-out trackers now say to log in again instead of showing raw error text.
- **An expired or revoked AniList sign-in now asks you to sign in again under Settings -> Tracking, instead of failing with an error (partly from Mihon).** Upstream: mihonapp/mihon#3888.
- **AniList tracking now stays under the service's request limit, so a burst of updates is no longer rejected (from Mihon).** Upstream: mihonapp/mihon#3942.
- **Removing a tracker with "Also remove from" now keeps it bound when the service refuses, so you can retry.**
- **Marking a chapter read now updates the tracker status shown on the entry straight away, on manga and novels.**
- **Kitsu tracking restored from an old Yokai backup now refreshes and updates again, on manga and novels.** It repairs itself the first time it is used.
- **Binding or changing the status of a series from your own manga server when you have not started it no longer marks its Chapter 0 read, in Reikai or on the server.**
- **Backing out of the category choice when adding a manga from its page no longer binds its server tracker.**
- **Binding a tracker to a novel you have already read now fills in when you started reading, as it does for manga, and history you removed no longer counts as that day (partly from Mihon).**
- **A tracker's start date is now filled in when the first chapter you read is not chapter 1, on manga and novels.** A date already on the tracker is never replaced.
- **Tapping Tracking on a novel when none of your signed-in trackers cover novels now opens Settings -> Tracking, as it does for manga, instead of an empty sheet.**
- **Sorting the library by tracker score no longer floats signed-out trackers above your rated entries, and counts a merged series' trackers once.**
- **The library's tracking-status groups now always read in reading-progress order (Reading first, Not tracked last), instead of following your category sort.**
- **A MangaBaka score is now saved as the score you pick at every step size, and no longer skews your library's score sort and statistics (from Mihon).** Upstream: mihonapp/mihon#3740.
- **Start and finish dates pulled from MangaBaka no longer land a day early in timezones behind UTC (from Mihon).** Upstream: mihonapp/mihon#3711.
- **A MyAnimeList entry dated with only a year, or a year and month, no longer errors out (from Mihon).** Upstream: mihonapp/mihon#3573.
- **Fill from tracker now says why it found nothing: "No entry found", or a prompt to log in when the tracker needs an account you are signed out of.** Trackers with public listings still fill while you are signed out.
- **Fill from tracker no longer adds a genre the manga or novel already has as a tag in different capitals.** The existing tag keeps its spelling.
- **A dropped connection while your MDList login refreshes no longer signs you out.**
- **Settings -> Tracking -> NovelList server address can now be reset to the built-in address.**

### Downloads & extensions

#### Added

- **Novels from Tsundoku and IReader extension apps now browse, search, read, download and update like plugin novels, and the apps install and update in Browse -> Extensions like manga extensions.** Extensions from IReader's own repo load without a trust prompt.
- **Tap a series in the download queue to see its chapters, cancel one, start one now, move one to the bottom, or read why it failed.** A downloading manga chapter shows its page count.
- **Settings -> Downloads -> Pacing sets the wait between novel chapter downloads, for every source or one at a time, never below what the source asks for.** Manga sources pace themselves.
- **Where a list mixes kinds of novel source (Browse, global search, the feed and migration), each one is labelled JS, APK or IReader.**
- **Settings -> Advanced can now leave the hash suffix off downloaded chapter names, for manga and novels (partly from Mihon).** Existing installs keep it on. Upstream: mihonapp/mihon#3966.

#### Changed

- **The download queue is now one list for manga and novels, replacing the Manga and Novels chips, and any series can be dragged above any other.** Each card names the chapter downloading, and a badge shows its type while both are queued.
- **Extensions now install and update only from a repo whose signing key matches, though a repo with no key, such as a third-party IReader repo, still updates the extensions no keyed repo signs (partly from Mihon).** A download signed with any other key is refused.
- **Browse -> Extensions now names the repo each extension and plugin comes from, and an install that fails says why and offers a retry (partly from Mihon).** When repos with different keys list the same extension, each is shown so either can be installed. Upstream: mihonapp/mihon#3955.
- **Novel downloads left waiting in the queue now wait for Resume when the app opens, as manga's do, though ones cut off by closing the app still resume.**
- **A manga download paused for a lost connection or by Wi-Fi only now says why in its notification, as a novel download does.**
- **Deleting a download by hand now always deletes, even in a category under Settings -> Downloads -> Excluded categories, and a novel's now respects Allow deleting bookmarked chapters.** Excluded categories still hold back automatic deletion after reading.
- **Tapping an installed light-novel plugin in Browse -> Extensions now opens its page, with its version, repo, settings, website and an Uninstall that asks first, replacing the row's delete button.** A long press offers removal, under Available both gestures install, and a failed plugin opens the reason.
- **An installed extension or novel extension app that fails to load now still gets its updates, under Updates with an update button (partly from Mihon).** An update is often what gets it working again.
- **Rows in Browse -> Extensions now read the same for every kind of extension, and a pending update shows the version it brings.**
- **Downloaded novel chapters show as downloaded as soon as the app opens.**
- **Novel plugin repos in Browse -> Extensions now refresh when you pull down, rather than each time you come back or install a plugin.**
- **A downloaded manga chapter now records its upload date in its ComicInfo.xml, and the local source reads a chapter's date from it (from Mihon).** Move the folder into the local source later and the date comes with it. Upstream: mihonapp/mihon#3967.

#### Fixed

- **An outdated manga extension no longer crashes the app when you browse it or open one of its series.** It shows an error instead.
- **Installing an extension through Shizuku works again.**
- **The novel download notification now has Pause and Show entry, as manga's does, and its Cancel cancels the queue instead of pausing it.** A paused queue leaves a notification with Resume and Cancel all.
- **Paused novel downloads now stay paused when the queue is reordered or sorted, no longer fail the chapter being retried, and carry on when resumed straight away.**
- **A novel chapter that failed to download can now be retried, by Resume or its Retry button in the download queue, and stays queued after a restart, as a manga chapter does.**
- **Manga downloads queued without a connection now start on their own once it returns, as novel downloads do.**
- **A novel chapter that fails while off Wi-Fi with Settings -> Downloads -> Only on Wi-Fi turned on now waits for Wi-Fi instead of retrying over mobile data.**
- **Pausing manga downloads from the notification now leaves a paused notification to resume from (partly from Mihon).** Upstream: mihonapp/mihon#2791.
- **Retrying a failed manga chapter, from the download queue or the reader's chapter list, now downloads it again at once, even while other chapters are downloading.**
- **Extensions from a store that cannot be reached no longer show as Orphaned or lose their update badges.**
- **Marking a chapter read with delete-after-read on no longer deletes its download in a category excluded from removal, and a queued novel chapter marked read now leaves the queue.**
- **With Downloaded only on, a novel's chapter list and reader now show only downloaded chapters, as manga's do.**
- **Novel downloads queued before a restart are no longer lost when another chapter is queued right after opening the app.**
- **Sorting or reordering the download queue no longer brings back a chapter that just finished or drops one just queued.**
- **Download queue counts no longer reset when you reopen the queue, or count a cancelled chapter as downloaded.**
- **A downloaded novel chapter with pictures now keeps the line breaks the source draws, and its pictures show offline even when the source offers several sizes.**
- **Downloading a novel chapter again now refetches a picture that failed before.**
- **A novel chapter download now fails at once with the reason when the device is nearly full or the source returns the chapter empty, as manga does, instead of retrying.**
- **Reading a novel in incognito now downloads the next chapters ahead, as reading manga in incognito does.**
- **Deleting a novel's last downloaded chapter now removes its empty folder, as manga does.**
- **Settings -> Advanced -> Reindex downloads now covers novel downloads too, as does restoring a backup.**
- **A chapter row on the details screen now shows a failed or retried download as it happens, merged series included.**
- **A series retitled by a refresh no longer carries off the downloads of another series with the same name on its source.**
- **A novel chapter that finishes downloading while the app rechecks its downloads now stays marked as downloaded.**
- **Clearing a download queue paused by a lost connection now stops the downloader and its notification.**
- **Installing, updating, reinstalling or removing a light-novel plugin no longer shows a false error on a second tap or gets undone by a plugin list reload.**
- **Light-novel plugins now stay on the installed version until you update them from Browse -> Extensions, and an update published at a new link now shows there.**
- **Updating several light-novel plugins at once no longer leaves one listed as updatable however often you update it.**
- **The light-novel plugin update notice now leaves out plugin names under Settings -> Security and privacy -> Hide notification content, and goes away once no plugin needs updating.**
- **The Browse badge on the home screen now counts novel plugin updates from app launch, as the Extensions tab's own badge does.**
- **Under the All chip in Browse -> Extensions, trusting an extension now works and removing a privately installed one asks first, as under the Manga chip.**
- **Updating a privately installed extension no longer switches it to a shared install.**
- **Removing an extension repo now marks its extensions untrusted straight away, instead of after a restart.**
- **An extension repo you remove while the repos are refreshing no longer comes back (from Mihon).**
- **Extensions marked Orphaned lose the mark as soon as you add a repo that lists them, rather than after a restart.**
- **An extension or novel source whose icon is missing or fails to load now shows the default icon instead of a broken image or an empty space.**
- **An extension row no longer shows a stray dot before its version after an install is cancelled.**
- **A resumed manga download now shows the right progress instead of restarting from zero.**
- **A manga download error notification now shows its own time and message instead of an earlier warning's (from Mihon).** Upstream: mihonapp/mihon#3341.
- **The More tab's download row now reads Paused while novel downloads are paused, matching the download queue's own Resume button.**
- **The first tap on a download queue sort now sorts ascending.**
- **The Settings -> Downloads note that download-ahead needs the current and next chapter downloaded now sits under Manga, where it is true, instead of Novels.**

### Backup & restore

#### Added

- **Settings -> Data and storage -> Library List now exports your novels too, and lists a merged series once.**

#### Changed

- **Details you edit yourself now back up in Komikku and Yōkai's format, so they restore in either app and theirs restore here, but Reikai 0.3.2 and older nightly builds restore a new backup without your edits.** Backups from any earlier Reikai still bring them back.
- **Restoring a backup over a manga or novel you already have now keeps its details unless only the backup ever loaded them, and keeps the earlier date it was added (from Mihon).**
- **Light-novel plugin and IReader extension settings now back up and restore with Source settings instead of App settings.** Backups made before this update still bring them back with App settings.

#### Fixed

- **Restoring a backup now brings merged series back exactly as the backup grouped them: unrelated series no longer collapse into one card, and a pair you split stays split.**
- **Restoring a backup with App settings ticked now reinstalls your light-novel plugins by itself, checks each against your added repos, and names any it could not bring back.**
- **Restoring a backup no longer changes your extension installer, trusts extensions or turns on Run scripts a chapter embeds.** Your device keeps its own choice for each.
- **Restoring a backup over a series you already have no longer rewinds it, on manga and novels: chapters keep the further position, and trackers keep your status and score and only move progress forward (partly from Mihon).**
- **Restoring a backup now keeps your default category, update categories, category filters and collapsed categories for manga and novels, including Default and Always ask.** A Yōkai backup keeps Default but leaves out its other category choices, since it saves no way to match them.
- **Restoring a backup now turns Settings -> Library -> Per-category settings for sort on only when a manga or light-novel category keeps its own sort, and keeps the per-category sorts of a backup made by Mihon or by Reikai before 0.3.0.** A hidden category, or one reset to the library sort, no longer turns it on.
- **Picking a backup to restore now opens the system file picker on devices where it would not open before (from Mihon).** Upstream: mihonapp/mihon#3948.
- **One bad entry in a restore no longer takes a hundred others down with it, and a backup holding the same series twice under one source now restores (from Mihon).** Only the entry that actually failed is reported. Upstream: mihonapp/mihon#3667.
- **The restore log now names every light novel, merged series, edited details and manga extension a restore could not bring back, and the rest of the restore carries on.** That includes an extension whose install failed, was cancelled or timed out.
- **With the read-entries option on, a backup now includes novels you have read but removed from your library, as it does for manga.** That keeps their reading history, including for a novel you migrated to a new source.
- **Your own title, author or cover edits on a manga you have read but removed from the library are no longer missing from a backup.**
- **The Categories backup option now covers novel categories both ways: a backup with Library entries off includes them, and a restore with Categories unticked leaves your novels' categories alone.**
- **A backup now keeps the sources you removed from a merged series, so adding one back after restoring on a new device rejoins its series, on manga and novels.**
- **The warning before a restore no longer lists light-novel sources you have installed, and it and the restore log name a missing one instead of showing its id.** Backups made before this update still show the id.
- **Restoring a backup now schedules automatic light-novel updates, adult-source updates and the tracker library refresh straight away.**
- **Novel reading time you cleared from History now survives a backup and restore, so Stats keeps its total, as for manga.**
- **Restoring a backup that lists a chapter or its history twice now restores it once, adding up the reading time and keeping the latest read, on manga and novels (from Mihon).**

### App

#### Added

- **Reikai can now send crash reports and anonymous usage data, both on unless you turn them off under Settings -> Security and privacy.** Onboarding offers the same choice on a fresh install.
- **Every stable release now also has a `-foss` APK with no crash reporting or analytics in it.** It installs as a separate app, so it can sit alongside your normal one.
- **A new Tokyo Night app theme, under Settings -> Appearance.**
- **A background job that fails to start now shows a notification naming it, instead of failing silently every time it runs.**
- **The What's new screen now shows the Note, Tip, Important, Warning and Caution callouts in release notes.**
- **Settings -> About now links Reikai's website and privacy policy.**
- **Settings -> Advanced -> Open debug menu adds debugging toggles and maintenance tools, such as hiding every cover.**

#### Changed

- **Updating the app now happens on the update screen itself, with the download progress on the button (from Mihon).** Tap once more when it finishes to install. Upstream: mihonapp/mihon#3669, mihonapp/mihon#3707.
- **Reikai now checks for app and extension updates every time you open it from cold, instead of waiting days between checks (from Mihon).** Upstream: mihonapp/mihon#3658.
- **Icons across the app are now drawn in Google's newer Material Symbols style (from Mihon).** A few Reikai-only icons, like the novel reader's text-alignment controls and the star ratings, keep their current look. Upstream: mihonapp/mihon#3873.
- **Every help link in the app now opens Reikai's own documentation at reikai.app, and a Nightly build opens the Nightly docs.**
- **The pre-release channel is now called Nightly and has a teal icon, so it is easy to tell apart from the stable app (partly from Mihon).** Downloads keep their file names and installs are unaffected. Upstream: mihonapp/mihon#3760.
- **The two source settings screens that sat at the top of Settings now live under Settings -> Browse and sources, with Enable adult sources and Page preview rows.** The two screens are listed in its Source settings group while their sources are on.
- **Recommendations settings now have their own entry in Settings instead of sitting inside Library.**
- **Settings -> Advanced now opens on Debugging and Help headers, and both Track update errors switches moved to the end of Settings -> Library -> Global update.** Manage notifications sits under Background activity.
- **The manga and novel Hide missing chapter indicators switches now sit together under Settings -> Library -> Behavior, each naming the content type it affects.**
- **Settings search now finds what is on the About screen, like the licenses and the update check.** About is also sorted into Legal and Links sections.
- **Reikai's notification categories in Android's settings now each have their own name, so novel updates, novel downloads and each background sync can be told apart.**

#### Fixed

- **The app update check no longer offers an older release as an update or misses a newer one, and no longer fails on a build with a longer version number.**
- **Hide adult content in notifications now keeps adult titles out of every notification that names a manga or novel.** That covers updates, downloads, backup restores and read aloud.
- **Hide notification content now also covers read aloud and its lock screen, novel downloads and updates, and the follows sync.**
- **With Hide adult content in notifications on, manga update notifications no longer come out blank for ordinary series from a source that carries extra metadata.**
- **Statistics now counts a merged series once instead of once per source, and its Downloaded figure includes novel chapters.**
- **Showing the crash screen no longer runs the app's startup work, such as your data migrations, a second time.**
- **A long series title no longer pushes the chapter numbers out of its update notification.**
- **The Reikai icon on a notification is now the same size as the other notification icons.**

### Other

- Manga and novels now run on one shared implementation in the library, details, browse, migrate, recents, downloads, reader, backup and restore, library updates and tracking, so a change reaches both. Categories for both now live in one table, and custom novel cover files are renamed once on upgrade.
- From Mihon: list screens stop querying a few seconds after you leave them (mihonapp/mihon#3716 through mihonapp/mihon#3762), and installed extensions are read off the main thread during a cold start (mihonapp/mihon#3788).
- Novel plugin settings load and save off the main thread, and Updates, History and Recents no longer start the novel downloader while they show only manga.
- Opening a source while the app is still starting now waits for the extension list instead of reading a half-built one, for manga and novels (partly from Mihon, mihonapp/mihon#3869).
- The manga reader no longer drops a page-turn signal or a preloaded chapter when it is busy, and cancelled novel plugin checks, font downloads and update runs stop instead of being logged as failures.
- Browse, global search and the feed no longer re-read every stored novel each time a novel is saved.
- The Migrate tab and its per-source list no longer re-read the whole novel library every time a chapter changes.
- A series' page no longer rebuilds its chapter list when another series updates.
- Deleting many downloaded novel chapters now does its disk, index and saved-queue work once per batch.
- The novel updates feed reads its newest chapters through an index instead of reading and sorting every library chapter on each refresh.
- Checking adult content sources for newer gallery versions finds chapters through an index instead of reading every stored chapter.
- The library reads gallery tags and alt-titles only while a search is active, instead of on every library refresh.
- The combined updates widget no longer watches for new chapters while none is placed on the home screen.
- One rule now turns a novel chapter's picture and link addresses into full ones, for both readers and downloads.
- The in-app browser, the Cloudflare bypass and the tracker sign-in browser now present one browser identity (from Mihon, mihonapp/mihon#3678), and Shikimori recommendations identify as Reikai like the other Shikimori calls.
- Kitsu tracking, the taste profile and Fill from tracker now use only Kitsu's newer API (partly from Mihon, mihonapp/mihon#3792), and Shikimori progress goes through its own update endpoint (from Mihon, mihonapp/mihon#3810).
- The arm64 download is about 30 MB instead of 44 MB, because native libraries are now compressed inside it. The installed app takes a little more space.
- Twenty-one settings descriptions rewritten shorter and plainer, to match Mihon's.
- Synced from Mihon: Material's adaptive navigation for the bottom bar and tablet side rail (mihonapp/mihon#3834), newer Compose text fields and sliders (mihonapp/mihon#3752), verbose lines kept in the shared crash log (mihonapp/mihon#3682), and Shizuku detected by its permission (mihonapp/mihon#3565).
- Synced from Mihon: refreshed translations (mihonapp/mihon#3563, mihonapp/mihon#3677, mihonapp/mihon#3701, mihonapp/mihon#3938, mihonapp/mihon#3950, mihonapp/mihon#3987), and dependency and toolchain updates up to Android SDK 37.2.
- Synced from Mihon: settings sliders redraw only their own row while dragged instead of the whole settings screen (mihonapp/mihon#3958), and the flag that shows or hides a settings row is renamed to say what it does.
- Synced from Mihon: global search no longer leaves threads behind each time it is opened (mihonapp/mihon#4036).
- Under the hood, synced from Mihon: screens hold state in AndroidX ViewModels (mihonapp/mihon#3594, mihonapp/mihon#3763), and components are wired together at build time, closing a class of release-only crash (mihonapp/mihon#3608, mihonapp/mihon#3965).
- Under the hood, synced from Mihon: extensions load via the platform class loader (mihonapp/mihon#3874), dates use kotlinx-datetime (mihonapp/mihon#3001), category edits write only their column (mihonapp/mihon#3693), and the database waits briefly when busy with four readers, keeping WAL except on low-memory devices.
- Under the hood, synced from Mihon: cancelling an extension install no longer goes through a local broadcast (mihonapp/mihon#3226), and tracker internals were tidied up (mihonapp/mihon#3900, mihonapp/mihon#3908).
- Build and tooling: Gradle's configuration cache is on (partly from Mihon), nightly and pull request builds check database upgrades against a saved older schema, and an on-device test measures scroll position when content is inserted above.
- Removed the Yokai-era database importer, which could not run after the 0.3.2 package change, plus unused code and an unused icon.

## [0.3.2]

### Changes

- **This release cannot update your current Reikai, so it installs alongside it and you will need to move your library across.** Reikai had been identifying itself to Android as Tachiyomi; it now uses its own identifier, and Android treats that as a different app.

**Moving your library across**

- **Back up first from Settings > Data and storage > Backup and restore > Create backup, ticking "Include sensitive settings" so your tracker logins come with it.** Everything else in the backup is included by default.
- **Install this release, then choose the same storage folder when it asks.** Your downloads, local source files and old backups are all in there, and it finds them again once you point at it.
- **Restore that backup, then uninstall the old Reikai.** Left installed, it will keep offering updates it can no longer install.
- **Covers you set by hand are the one thing a backup cannot bring across.** They live inside the old app and are removed with it, so set those again afterwards.

## [0.3.1]

### Additions

- **Create backup now lets you pick Manga, Novels, and Custom entry info separately.** Back up just one content type (which also makes the file smaller), or leave the custom title/cover edits out.

### Fixes

- **Backing up a large library with chapters enabled works again instead of leaving an empty file.** Restoring one no longer runs out of memory either, and a backup that does fail now reports the error instead of quietly stopping.

## [0.3.0]

### Additions

**Details**

- **Novels now show where chapters are missing, like manga.** A "missing chapters" note appears between chapters when the numbering skips ahead, with a header summary; a Settings > Library toggle hides the inline markers.
- **You can now fully edit a manga or novel's details, including its cover (editor ported from Komikku).** Change the title, author, artist, cover URL, description, tags, and status from the details screen; edits are stored separately (Reset restores the source) and show across your library, updates, and history.
- **Fill a manga or novel's info straight from a bound tracker.** In Edit info, tap Fill from tracker to pull the title, author, artist, cover, description, and genres from a linked tracker (with a picker when more than one is linked).
- **You can now hide individual chapters on a manga, just like novels.** Select chapters and hide them from the list; a "Show hidden chapters" toggle reveals them dimmed so you can unhide, and hidden chapters stay out of bulk downloads and the Resume button.
- **The novel chapter list now has a fast-scroll thumb, like manga.** Drag it down the right edge to jump through a long list of chapters.
- **Long-press a novel's In-library button to edit its categories.** It opens the same category picker manga details already had on long-press.
- **Copy a manga's source name by long-pressing it.** Its title and author already copied on long-press; now the source name does too.
- **You can now switch the related-manga suggestions off completely.** Settings > Library > Recommendations. With it off the details page does none of the searching the suggestions need, so titles open faster on sources that limit how often you can ask them for a page.

**Library & updates**

- **Your novel library can now filter, sort, and group by tracker, matching manga.** Filter novels by each linked tracker, sort by tracker score, and group by tracking status from the library settings sheet.
- **Sort your manga library by download count, the way novels already could.** A new Downloaded option in the library Sort tab orders titles by how many chapters you have downloaded.
- **Adding a novel now warns you when a similar one is already in your library, like manga.** Favoriting a novel from its details screen or your history first flags any matching titles, so you can open the existing one instead of ending up with a duplicate.
- **Refreshing your novel library now shows an "Updating library" confirmation, like manga.** Tapping refresh on the Novels chip shows that message (or "An update is already running" if one is in progress) instead of doing nothing visible.
- **The Updates screen now shows when your novels last updated.** The last-updated line follows the Novels chip, and the All chip shows whichever of manga or novels refreshed more recently.

**Reader**

- **The novel reader can now open the chapter in your browser or share its link.** Both join "Open in WebView" in the reader's overflow menu, matching the manga reader.
- **Choose which buttons sit on the novel reader's bottom bar, like manga.** Add quick auto-scroll, keep-screen-on, and bionic-reading toggles, one-tap theme and text-size pickers, plus web view, browser, and share, from Settings > Reader.
- **Volume keys now scroll the novel reader, and a slider sets how far each press scrolls in novels and long-strip manga.** Turn volume keys on for novels in Settings > Reader (invert optional); paged manga still turns a whole page.
- **The novel reader now shows your reading percentage while you read, like manga's page number.** It sits at the bottom while the toolbars are hidden; turn it off under Settings > Reader.
- **Manhwa, manhua and webtoons can now open in webtoon mode on their own, via the new "Auto webtoon mode" toggle under Settings > Reader (ported from Komikku).** It goes by the series' tags, so if a source never tags one you can add the tag yourself in Edit info; a mode you set on a series always wins.

**Downloads**

- **Manga and novel downloads now show one card per series you can reorder, not a row per chapter.** Cancel or bump a whole series to the top or bottom, and the All view stacks both types in one list.
- **Pause and resume novel downloads, like manga.** The download queue's Pause/Resume button now works for novels (and in the All view one tap drives both manga and novels); a paused queue stays paused across app restarts.
- **Downloading from a novel that isn't in your library now offers to add it, like manga.** Your first download on a browse-opened novel shows an "Add to library?" prompt, asked only once.

**Browse & search**

- **Add many novels to your library at once, the way manga already could.** In a novel source's browse or global search, tap Select, pick several results, and add them all in one step with a single category choice.
- **Novel global search now shows a progress bar while sources are still searching, like manga.** It fills as each source finishes and clears once the search completes.

**Tracking**

- **Track your manga on MangaBaka, a new tracker synced from Mihon (mihonapp/mihon#3047).** Sign in from Settings > Tracking, then bind a title to sync status, progress, score, and dates like the other trackers (Fill from tracker works too).
- **Track your light novels on Shikimori, Hikka, and MangaBaka.** These trackers now search novels directly instead of returning manga, and trackers that can't tell novels apart (Bangumi, MdList) no longer appear in the novel tracking sheet.

### Changes

- **Tag suggestions on the adult sources now cover newer artists, characters and parodies (refreshed from Komikku, komikku-app/komikku@2011491510).** The built-in tag list was refreshed, and gains a "location" namespace.
- **With "Per-category setting for sort" on, the library now uses one global sort that each category can override.** Set the global from the toolbar, override a category from its header, and clear it with "Reset to global sort"; works for manga and novels.
- **Manga and novel options in Settings now sit in separate, clearly labeled sections.** Reader, Downloads, and Library each split into "· Manga" / "· Novels" sections instead of interleaved rows or "(Novel)" suffixes.
- **The novel reader now has its full set of reading and accessibility options.** They sit under Settings > Reader in the Novels section, alongside the manga ones.
- **The novel chapter selection bar now shows only the actions that apply.** Like manga, it hides mark-unread, delete, or mark-previous when your selection doesn't allow them, instead of always showing every icon.
- **The novel details header now shows a status icon, matching manga.** It sits next to the source and flips between Ongoing, Completed, and the rest.
- **Editing a novel's details now requires adding it to your library first, matching manga.** The Edit info action appears on the details screen once the novel is in your library.
- **Novel and grouped covers in the Updates list now open the title's details.** Novel rows did nothing on cover-tap before and grouped rows just expanded; both now open details, while the rest of the row still opens the chapter or expands the group.
- **Pick which reading modes use the vertical chapter navigator, and set its height (synced from Mihon, mihonapp/mihon#3531).** The single long-strip on/off toggle is now a per-mode picker with an adjustable height slider.
- **The novel reader's progress bar is now a full chapter-navigation rail, like manga.** Prev/next skip buttons flank the scroll slider on the reader edge, and its height and side follow the same Reader vertical-navigator setting as manga.
- **A novel source's Filter chip now lights up when a filter or search is active, like manga.** It used to never show as selected, so there was no sign your filters were applied.
- **Novel global search now floats sources with results to the top as they arrive, like manga.** Sources that return nothing, are still loading, or errored sink below the ones with hits, instead of staying in a fixed order.
- **Novel sources now enable and disable the same way as manga.** Disabling a novel source removes it from the Sources list (it no longer stays dimmed in place), and the filter button on the Novels chip opens a new screen listing every novel source so you can turn them back on.
- **A failed novel browse page now keeps its Retry message on screen until you act, like manga.** The error snackbar no longer disappears on its own after a few seconds.
- **Mark a novel's tracking as private while binding it (for trackers that support it), like manga.** The private toggle now appears in the search step, instead of only after the bind.

### Fixes

**Details & chapters**

- **Novel chapters now show their release dates (ported from Tsundoku).** The date from the source's chapter list is read instead of being left blank.
- **Novel titles and chapters no longer show raw HTML codes (ported from Tsundoku).** Escaped text (like an ampersand shown as its HTML code) is decoded, and stray control characters are stripped, which also keeps download folder names clean.
- **Swiping a novel chapter now does what your swipe setting says.** The left and right actions were reversed on novels, so swiping to mark-as-read bookmarked instead.
- **A novel's hidden chapters are no longer pulled into bulk downloads, and the Resume button skips them too.** They're skipped just like the chapter list already hides them.
- **A novel's "Show hidden chapters" menu item now disappears once nothing is hidden.** Unhiding your last hidden chapter used to leave a stale entry in the overflow menu.
- **Related manga suggestions are now relevant.** They were searched one word of the title at a time, so common words like "the" and "in" pulled back whatever those happened to match.
- **A manga's own details and chapters now load before its related suggestions.** On sources that limit how often you can ask them for a page, the suggestions used to compete with the chapter list, so the page you actually opened filled in last.
- **The related manga row no longer jumps while you scroll it.** Cards with a longer title were taller than the rest, so the row resized as they scrolled into view.

**Merged series**

- **Chapters open in the right order on a series you have from more than one source.** Opening a chapter that the combined list had set aside as a duplicate could put it out of sequence, so tapping next or previous went to the wrong chapter.
- **Finishing a novel chapter now marks that chapter read across the novel's other merged sources, like manga.** When "Mark duplicate chapters as read" is on, completing a chapter in a multi-source novel also marks the same-numbered chapters from its other sources read.
- **Marking a merged novel's chapter read or bookmarked now carries across all its sources, like manga.** The change used to touch only the source you tapped, so switching source chips left the same chapter unread or unbookmarked on the others.
- **A merged series no longer lists every chapter twice.** Chapters from a source with rich gallery-style metadata were skipping the cross-source dedup, so the unified "all" view doubled up; they now collapse to one row per chapter like the others.
- **You can now merge two sources tracked on different services.** A manual merge was quietly undone when the two entries were linked to different trackers (say one on AniList, the other on MyAnimeList); it now keeps them merged.
- **On a merged series, a source's rating and "More info" link now show when you view that source.** They were hidden unless the merge happened to be anchored on that source.

**Library & updates**

- **Your manga and novel libraries now each remember their own scroll position.** Switching between them with the Manga/Novels chip no longer carries one view's scroll over to the other.
- **The library's "Jump to category" hopper now opens on your current category and jumps there instantly.** It used to start at the top of the list (leaving the current category off-screen) and animate a slow scroll that stuttered on categories with hundreds of items.
- **Marking a novel chapter read from the Updates list now deletes its download, like manga.** With "delete after read" on, that cleanup ran everywhere except the Updates screen; now it runs there too.
- **Deleting downloaded novel chapters from the Updates screen now asks for confirmation first.** Matching manga, so a mixed manga-and-novel selection can no longer lose the novel files before you confirm.

**Reader**

- **The reader's page slider now updates for each chapter's page count (synced from Mihon, mihonapp/mihon#3549).** It kept the previous chapter's number of steps, so on a longer chapter the slider stopped short of the last page.
- **The reader now skips chapters you've hidden when you tap next or previous.** Reading forward or back no longer lands on a hidden chapter, in both the manga and novel readers, and hidden chapters stay out of the reader's own chapter list.
- **The novel reader's tap-to-hide toolbars, progress saving, and read-aloud now work in release builds.** They ran through an internal bridge that release-build optimization was stripping out, so they only worked in debug builds until now.
- **The reader's bars now hide correctly after you use the page slider (synced from Mihon, mihonapp/mihon#3567).** Tapping to change pages right after dragging the chapter-navigator slider used to leave the top and bottom bars stuck on screen.

**Downloads**

- **Your downloaded novels now survive reinstalling, restoring a backup, or moving storage.** Novel downloads are saved under stable, readable folders (source, title, chapter) and detected from disk, so the app no longer forgets them and re-downloading isn't a silent no-op.
- **Downloading a novel's next chapters no longer stops short when earlier ones are still queued.** The "next N" download actions now skip chapters already waiting in the queue before counting, so you get a full batch, matching manga.
- **A failed novel chapter download now shows a notification instead of failing silently.** Before, the only trace was a queue entry that disappeared when the app restarted.
- **A download whose server can't resume a partial image no longer fails the chapter (synced from Mihon, mihonapp/mihon@4a66b8b5d).** An image request answered with HTTP 416 is now caught and retried from scratch instead of erroring out.

**Novel sources & browsing**

- **Novel sources that space out their own requests no longer get blocked.** Reikai now honors the short waits these sources ask for between requests, so they stop failing partway through browsing or downloading.
- **Novel browse filters that were silently missing now show up.** Some sources' checkbox filter groups never appeared in the filter sheet; they now render alongside the others.
- **Switching a novel source between Popular and Latest no longer keeps your old filters.** The filter draft now resets on the switch, matching manga, instead of silently staying applied to the new listing.
- **Novel global search no longer shows every source spinning before you search.** Source rows with loaders now appear only once a search is actually running, matching manga; a blank query clears the list.
- **Novel browsing no longer stops loading for good after a brief network error.** A failed page now offers a Retry and keeps paging when you scroll on, instead of ending the list.
- **The novel sources list now shows a "Last used" section.** Your most recently opened novel source sits at the top, like the manga sources list.
- **The novel sources list no longer shows a Filter button that had nothing to configure.** It opened the manga-only filter screen.
- **The Extensions filter is now hidden on the Novels chip, where it only opened a manga-only language list.** It did nothing useful for novels.

**Migration**

- **Clearing the search box while picking a novel's migration target no longer strands the row on a spinner.** A blank re-search is ignored (and its accept button disables), so the row's candidates and overflow menu stay reachable.
- **Migrating a novel now keeps its reader and chapter-list settings.** It can also delete the old source's downloaded chapters, matching how manga migration works.

**Tracking, settings & backup**

- **Hikka tracker media types now read cleanly, matching the other trackers (synced from Mihon, mihonapp/mihon#3560).** A type like "one_shot" now shows as "one shot" in tracker search.
- **Searching your settings no longer crashes the app.** Two settings that shared a name (the manga and novel versions of a toggle) could collide in the search results and bring it down.
- **Restoring a backup now warns about missing novel sources and logged-out novel trackers too.** The pre-restore check already flagged these for manga; it now covers novels so you don't silently lose novel data on restore.

### Other

- Novel source browsing now detects the end of the results without a wasted extra fetch, and prefetches the next page so the "load more" footer is accurate about whether more results follow. Ported from Tsundoku.
- The similar-titles carousel no longer spends a request on sources that can't return related titles, and closes the response it does make. Ported from Komikku.
- A novel plugin saved incompletely (an interrupted download) now re-downloads itself on next use instead of staying broken.
- The light-novel plugin runtime now provides Buffer, Blob, Response.arrayBuffer(), fuller response headers, and an X-XSRF-TOKEN header for Laravel-based sources, so plugins that rely on these no longer fail.
- Settings search now scrolls to and highlights the exact matched row (even when two settings in different content-type groups share a name) and indexes the recommendations screen so its options are searchable.
- Formatted the codebase to pass ktlint/spotless, so the formatter runs cleanly and can be enforced going forward.
- The manga and novel History/Updates rows, cover dialog, details screen, and reader bars now render through shared components instead of near-duplicate copies, so a change to one reaches both. Groundwork for the unified content UI.
- The manga and novel browse, global-search, and migration result cells now render through one shared browse cell, so the two catalogues stay identical and can't drift.
- The manga and novel source long-press options dialog (pin, enable/disable) now renders through one shared dialog.
- The manga and novel global search now render through shared components (the per-source result section, the result card row, and the source-filter chips), so the two search screens stay identical; their look is now unified (result card size, section header, and the chip row match).
- The manga and novel notes editors now render through one shared screen, so a change to the notes editor reaches both.
- The manga and novel library Display settings tab now render through one shared composable, so a change to it reaches both.
- The manga and novel "change categories" dialog now share one category-diff helper, so their checked/mixed logic can't drift.
- Enhanced and delegated sources now use the wrapped source's home URL for "Open in WebView", and redundant internal source overrides were dropped. Mirrors Komikku.
- Synced upstream Mihon changes: correct `extensionLib` metadata reading, Hikka tracker hardening, a dropped redundant code-shrink build flag, a zstd proguard keep, aboutLibraries v15, a refreshed set of community translations, and assorted dependency and CI bumps.

## [0.2.1]

### Additions

- **Track your reading on Hikka, a new tracker synced from Mihon (mihonapp/mihon#1386).** Sign in from Settings > Tracking, then bind a title and your progress stays in sync with your Hikka account, just like the other trackers.
- **Settings > Tracking now shows which account you're signed in to (synced from Mihon, mihonapp/mihon#3533).** Each connected tracker displays its username under its name, so it's clear at a glance which account is linked.

### Fixes

- **Turning off "Tracker recommendations" now gives a source-only Related carousel.** The switch previously still showed tracker suggestions for titles you track; now it hides every tracker-derived suggestion (direct recommendations and the taste-based ones), so off leaves only the source's own related titles.
- **Installing several extensions at once no longer freezes the app partway through (ported from Komikku, komikku-app/komikku#1652).** The installer no longer runs under Android's short-service time limit, which could kill it while it waited on the system's install prompts.
- **Canceling one extension install no longer cancels an unrelated one (ported from Komikku, komikku-app/komikku#1649).** The installer now matches a cancel to the right download and won't queue the same extension twice.
- **AniList tracking now shows a clear message when it's down or your login expired (ported from Komikku, komikku-app/komikku#1591).** Instead of a generic failure it surfaces AniList's own error, and points you to re-login when the token has expired.
- **The library's "Jump to category" picker now shows the Default category.** Its row was blank before; it now reads "Default" like the other categories.

## [0.2.0]

### Additions

- **Track your reading with MDList.** Sign in from Settings > Tracking, then bind a title and its follow status and rating stay in sync with your account, the same way the other trackers work.
- **One of the most-used manga sources now shows full details on its entries.** Author, artist, status, description, a star rating with its score, and namespaced tags (demographic, content rating, genres) now come from the source's own data instead of a bare listing.
- **Browse the manga you follow on MDList and add them to your library.** Once you're signed into MDList, tap the new Follows button in that source's Browse filter to see your follows and add them, one or many at once.
- **Add many titles to your library at once.** Tap Select in Browse, global search, or a recommendations "See all" grid to pick several and add them together; long-press still adds one.
- **Sync your MDList library both ways from one screen.** A new settings screen imports every title you follow on MDList into your library, filtered by follow status, and pushes your library titles back to your account as reading.
- **Jump to a random title on one of the most-used manga sources.** Open that source's Browse filter and tap Random for a surprise pick.

### Fixes

- **Cover-based theming now tints a title the first time you open it.** Previously the cover's accent color only appeared after a title was in your library and the app had been reopened; now it shows on first open when browsing, for both manga and novels.
- **The themed app icon now shows the logo instead of a shapeless blob (thanks [@Orifarius](https://github.com/Orifarius)).** With Material You themed icons enabled, the home screen icon keeps the letter and flame detail in your wallpaper's colors.

### Other

- Synced two upstream Mihon fixes: the app no longer crashes when sent to the background (replacing Reikai's earlier local notes-screen workaround), and storage folders served by non-system file providers (some cloud-storage and file-manager apps) work again.

## [0.1.8]

### Changes

- **Recommendations now include MangaUpdates similar titles, not just its community picks.** A title's related-series list pulls from both MangaUpdates buckets.
- **Shikimori tracker search now shows authors, artists and a description.** Looking up a title to track also makes fewer network requests than before.

### Fixes

- **A dropped connection now pauses downloads and picks back up on its own.** Losing network mid-download shows a resumable Paused notification and resumes automatically once you're back online, instead of failing the chapter and freezing on a stuck progress bar.
- **A stuck or failed download no longer holds up the ones you queue next.** Adding chapters while a failed download sits in the queue starts them right away instead of staying paused until you manually resume.
- **Cloudflare-protected sources that rely on FlareSolverr respond faster instead of hanging.** Once the in-app browser can't clear a site's challenge, browsing it, opening a title, and loading chapters hand off to FlareSolverr straight away instead of re-waiting 30 seconds on each request.
- **Shikimori recommendations work again after the site's domain change.** They were still pointed at the old address, so they had stopped showing up.
- **The notes editor no longer crashes when you background the app or select text.** Editing a title's notes is stable again.
- **Restored downloads appear right after a backup restore.** The download state no longer waits for an app restart to catch up.

## [0.1.7]

### Fixes

- **Chapters now open again on sources that run their own JavaScript.** Some sources decode their page list with an in-app JavaScript engine; a missing engine class made those chapters fail to open, and it is restored.
- **Uninstalling a light-novel source works right after installing it.** The trash button used to do nothing until you closed and reopened the app.

### Other

- Synced upstream Mihon changes: dependency and tooling updates, the Shikimori tracker's new domain, and compatibility fixes for a newer XML library and Material components.

## [0.1.6]

### Additions

- **Import adult galleries from a link.** Share or open a supported adult-source gallery link and pick Reikai to add it straight to your library, landing on its details page with chapters ready.
- **Batch add galleries from the More menu (once adult sources are enabled).** It takes a pile of pasted gallery URLs, or a visited-galleries export, and imports them one by one with a live progress list.
- **More adult sources are now built in, no extension to install.** They browse, read, and import directly once adult sources are enabled, including one that previously needed an extension that no longer works.
- **Adult-source browse shows a rating, category, page count and more on each result.** Browsing the built-in adult sources now lays out each result's rating, category, page count, language, uploader and date instead of a bare cover and title.
- **Search adult-source library entries by tag, with namespaces, wildcards and exclusions.** Type queries like `artist:name`, `parody:*hero*`, or `-language:japanese` to filter by captured tags; plain title search is unchanged.
- **Adult-gallery details now show grouped, tappable tags and a full info panel.** Tags appear grouped by namespace (tap one to search it), and a panel above the description lists the rating, uploader, page count, size, language and upload date.
- **Preview an adult gallery's pages from its details screen.** A grid of page thumbnails sits above the description; tap one to open the reader at that page, tap More previews for the full gallery with page-to-page navigation, and set how many rows show (0 hides it) in Appearance settings.
- **Remove every source of a merged series in one step (manga and novels).** Deleting a merged library entry now offers an "All grouped sources" option that clears the whole group at once instead of leaving the other sources behind.
- **Keep adult content off your lock screen (Security and privacy, on by default).** The new "Hide adult content in notifications" setting strips adult titles and covers from notifications across all adult sources; your normal library notifications are unaffected.

### Changes

- **Interrupted downloads now resume instead of restarting, on sources that support it.** A download cut off mid-page, or by the app closing, continues from where it stopped instead of re-fetching finished pages, piling up duplicates, stalling, or needing a manual restart.
- **The adult-gallery update checker shows a clearer notification.** Its progress matches the library updater, and any galleries that fail to update raise a notification you can tap to see exactly which ones, instead of failing silently.

### Fixes

- **Browsing adult content sources now loads past the first page.** The built-in adult-source browse stopped after the first set of results; it now pages all the way through.
- **Built-in adult sources show their own icon on library covers.** The sources that ship without an installable extension no longer fall back to a generic icon on a cover's source badge, matching how they already appear in Browse.
- **Merged galleries update when you refresh from their details.** A source merged into an entry from elsewhere used to stay stale until you reopened it from Browse; refreshing the details now fetches every merged source at once.
- **Adult-source image-quality options now take effect.** The account image-quality picker listed outdated resolutions, so most choices silently did nothing; it now matches the site's current tiers.
- **Merged adult galleries now show every source's chapters.** Combining the same gallery across two adult sources no longer drops one from the unified chapter list.
- **Built-in adult sources no longer trip site rate limits.** They throttle their requests to stay within each site's limits, avoiding bans.

### Other

- Build the app and publish previews only when an app-affecting file changes; docs and other repo-only updates no longer trigger a build.

## [0.1.5]

### Fixes

- **Merging a series' sources now takes one tap.** Selecting the cards for the same series from different sources and tapping Merge now combines all of them at once, including same-title copies that were auto-grouped, instead of needing several taps to fully coalesce. Most noticeable after restoring a backup. Applies to both the manga and novel libraries.

## [0.1.4]

### Fixes

- **Cloudflare bypass proxy handles JSON and sessionless solvers.** Pages fetched through a bypass proxy (FlareSolverr or Byparr) now load correctly when the response is JSON, and Byparr's sessionless mode is supported, instead of failing with a parse error or showing nothing.

## [0.1.3]

### Additions

- **Migrate failing entries from the update-errors screen.** Select entries that failed their last update (the update-errors list), tap Migrate, and they go straight into the migration flow to move them onto a working source. The list is opt-in: turn on Settings → Advanced → Track manga update errors and Track novel update errors first, then open it from the library overflow menu. ([#15](https://github.com/unseensnick/Reikai/issues/15))

### Fixes

- **Extensions re-trust themselves once their repository is present.** After updating from an old build, restoring a backup, or adding a repository by hand, installed extensions no longer stay "untrusted" until you restart the app: they re-check automatically as soon as the repository lands. A "Re-check extensions" action in the Browse → Extensions overflow menu can trigger the same re-check on demand. ([#14](https://github.com/unseensnick/Reikai/issues/14))

## [0.1.2]

### Additions

- **Migrate light novels from Browse → Migration.** The Migration tab now has the same All / Manga / Novels switch as the rest of Browse: pick a novel source to see its saved novels, select the ones to move, and run them through the existing novel migration flow. A source still shows (with its last-known name and icon) even after its plugin is uninstalled, so you can always migrate away from it.

### Fixes

- **Fixed the crash on launch after updating from an old Yōkai-Y2K build.** Updating in place from a pre-rebase (1.9.x) build left a database the new app couldn't open, so it crashed on startup. It now recovers your manga and novel libraries plus your extension repositories automatically on first launch (a brief notice shows while it restores), with your previous data kept safe. Merged series come back unmerged, so re-create any merges you want. ([#11](https://github.com/unseensnick/Reikai/issues/11))

## [0.1.1]

### Other

- Expanded automated test coverage for backup restore, novel chapter sync, and metadata parsing, and fixed an internal cookie-removal helper that could miss cookies after the first.
- Stopped logging a harmless cast error for every installed extension at startup (the extension lib version is now read without the failing conversion).

## [0.1.0]

### Additions

- **Read a merged manga straight through all its sources.** Opening a merged series in the reader now flows through the whole group: the in-reader chapter list shows every source's chapters (each labeled with its source), and reaching the end of one source's chapters continues into the next without leaving the reader. Downloads and tracker updates follow each chapter's own source.
- **Open Reikai's settings from Android's system settings.** Reikai now appears as a configurable app in Android Settings; opening it there jumps straight to the in-app Settings screen. (Synced from Mihon.)
- **Built-in adult content sources.** Turn on Settings → Advanced → Enable adult sources to add them to Browse, then search with full filters (including tag autocomplete as you type), open an entry, and read it.
- **Find saved entries by tag in your library.** Library search now also matches the indexed tags of adult-source entries, so typing a tag name surfaces every saved entry carrying it.
- **View an entry's full metadata.** Adult-source entry details get an info action (overflow menu) that lists every captured field: tags, uploader, rating, size, page count, language, and dates, with long-press to copy. A dedicated settings screen lets you log in to the account-backed source and set image quality, titles, and tag thresholds; your choices are synced to your account automatically.
- **More adult sources gain searchable tags.** Installing additional adult-source extensions now records each entry's namespaced tags (artist, group, parody, character, and more) into your library, so library tag search and the info viewer work for them too.
- **Keep favorited adult-source entries up to date.** A background checker re-checks your favorited entries for newer versions and pulls them in, merging the new version's pages while keeping your read progress and bookmarks. Set how often it runs (and any Wi-Fi / charging limits) in its settings.
- **Back up favorites to your account.** Turn on Favorites backup in the source's settings, and favoriting an entry also adds it to your account's favorites, so your library can stay disposable while the account keeps a record. Removing an entry from your library leaves it on the account unless you tick "Also remove from favorites" in the confirmation. A "Back up all favorites now" button pushes everything already in your library.
- **App backups now include adult-source tags.** A backup of an adult-source entry now carries its captured tags, so restoring brings them straight back: library tag search and the info viewer work immediately, without re-opening each entry.
- **Choose which source to migrate for a merged series.** Migrating a manga or novel that's merged across several sources now opens a picker first, so you can move just the source(s) you want (the rest of the group stays put); an entry that isn't merged skips straight through as before.

**Light novels**
- **First-class in your library.** A Manga / Novels chip switches the library between the two; novels get the same grid, grouping, badges, multi-select, and Filter / Sort / Display sheet as manga, with their own categories.
- **Browse and install novel sources.** Add LNReader plugin repos from the Repos screen and browse novel sources in a catalogue styled like manga: Popular / Latest, filters, source settings, search, in-library badges, long-press to add, and pin favorite sources to the top.
- **Global search across novel sources.** One query searches every installed novel source at once, each filling in its own row; filter by Pinned / All / Has results, and tap a source to open its full results.
- **A full novel details screen.** Matches the manga layout, with chapter multi-select, a Filter / Sort / Display sheet, hideable chapters, Edit info, WebView / Share, and per-page loading for huge chapter lists. Saved novels open instantly from local storage and refresh on demand.
- **A full-screen novel reader.** LNReader-style typography with a live Display / Theme sheet (fonts, size, spacing, margins, light / sepia / mint / dark / black themes, plus custom brightness and a colour filter with blend modes, the same controls as the manga reader and kept separate from it), saved scroll position, a prefetched next chapter, and tap-to-hide immersive mode. The bottom bar carries chapter skip, a chapters list to jump around, a rotation toggle, and a WebView button that opens the current chapter on the source site; the top bar bookmarks the current chapter, and a progress seekbar tracks how far you've read.
- **Read novels aloud (text-to-speech).** A floating play button voices the chapter with your device's voices, highlighting and scrolling each paragraph as it goes. A new TTS settings tab picks the voice (filter the list by language), speed, pitch, and auto-advance to the next chapter; the button fades while playing and can be dragged anywhere. Playback keeps going when you leave the app or turn the screen off, with play / pause / stop on the lock screen, the notification, and headset buttons.
- **More reader controls (General tab).** The novel reader settings are reorganized into General / Display / TTS tabs, and the new General tab adds bionic reading (bold the start of each word), remove extra spacing, auto-scroll with a speed control, a vertical progress seekbar, tap the top / bottom edge to scroll, and swipe left / right between chapters.
- **Offline downloads.** Save chapter text with inline images, one at a time or in batches, on a single background queue that paces itself per source and resumes after a restart.
- **Reorder and sort the novel download queue.** Drag chapters to set the download order (it now survives a restart), or sort by upload date or chapter number. In the combined queue, sorting applies to manga and novels together.
- **More novel download settings, matching manga.** Under Settings → Downloads: delete a chapter when you mark it read (from the reader, the chapter list, or the library), keep only the last N read chapters downloaded, never delete bookmarked chapters, exclude categories from auto-delete, and download-ahead (auto-download the next few chapters as you read).
- **Per-title novel update notifications.** When favorited novels gain new chapters, you now get one notification per novel (grouped together) that opens that novel when tapped, instead of a single "N novels" line.
- **Background updates in the Updates tab.** Favorited novels re-check on a schedule (interval, device restrictions, category include / exclude, Smart update) and optionally auto-download; new chapters join a unified All / Manga / Novels Updates feed.
- **Home-screen widget for manga and novel updates.** A resizable widget shows your recently updated manga and novels together in labeled sections; tap a cover to open it. Add it from your launcher's widget picker (the manga-only Updates widget is still there too).
- **Novels in the History tab.** Reading a novel records it in History; the tab interleaves recently read manga and novels (All / Manga / Novels chip), newest first, with search, tap to resume, and delete or clear.
- **Cross-source merge.** Combine the same novel from several sources into one cover and one deduplicated chapter list with a source switcher and shared read state, by hand or automatically by title.
- **Migrate novels to another source, one or many at once.** From a novel's overflow menu, or by multi-selecting several novels in the library, each novel auto-searches your sources and suggests a match you can accept or change; picking carries read / bookmark / scroll-progress (matched by chapter number), categories, your custom cover, notes, and tracker links, and re-downloads any chapters you had saved offline. Choose Copy (keep the original too) or Migrate (replace it). Cover and notes options appear only when a selected novel actually has them. A source picker first lets you choose which sources to migrate to and drag them into priority order, so matches come from the sources you prefer. Each novel is then shown side by side with its match (covers, source, and chapter counts) so you can compare at a glance; tapping a cover opens that novel's details to read the description, and a match with fewer chapters than your current source is flagged in red. Changing a match lists the alternatives as browse-style rows grouped by source.
- **Track novels on AniList, MyAnimeList, MangaUpdates, and Kitsu.** A Tracking action on a novel binds it to any tracker you're already signed into, then set status, chapters read, score, and dates. Reading progress pushes automatically as you finish chapters (and queues to retry if you're offline).
- **Plugins stay current.** The Browse badge counts pending plugin updates, checked in the background, with one-tap reinstall and real plugin icons.
- **Pull to refresh a novel's details.** Swipe down on a novel's page to recheck its info and pick up new chapters.
- **Incognito mode now covers novels.** With Incognito on, reading a novel records no history, saves no progress (no resume position or read state), and skips tracker sync, and opening a novel source no longer updates Last Used, matching how manga behaves.
- **Keep the screen on while reading a novel.** A new switch in the novel reader's Display settings holds the screen awake, just like the manga reader.
- **Lock the novel reader's orientation.** Pick a per-novel orientation (Default, Portrait, Landscape, or a locked variant) from the reader's Display settings, with a global default under Settings → Reader, just like the manga reader. "Default" follows the global default.
- **Novel downloads respect "Download only over Wi-Fi".** With that setting on (Settings → Downloads), novel chapter downloads now wait for Wi-Fi instead of using mobile data, the same as manga.
- **Failed novel downloads retry before giving up.** A chapter download that hits a network blip or a momentarily busy source now retries a few times with a short backoff, instead of failing on the first stumble.
- **Novels now appear in Statistics.** An All / Manga / Novels switch on the Stats screen shows reading time, library size, chapters, and tracker stats for novels too, or both content types combined.
- **Mark chapters read when you skip ahead (novels).** Turn on "Mark chapter read when skipping ahead" for Novels (Settings → Reader) and tapping Next in the reader marks the chapter you skipped past as read, just like the manga reader.
- **Per-novel notes.** Keep a private markdown note on any saved novel from its details screen (overflow menu → Notes), using the same editor as manga. Saved with the novel and included in backups.

**Library**
- **Cross-source merge for manga.** A series from several sources shows as one cover with combined unread counts and one deduplicated chapter list behind a source switcher; merge by hand or automatically by title, with Manage sources and a Preferred sources ranking.
- **Dynamic grouping.** Group the library by source, tag, author, language, status, or tracking status instead of by category, with collapsible groups, in both views and for both manga and novels.
- **Single-list view with a category hopper.** An optional one-scroll view of collapsible categories with a floating jump-to hopper, plus per-category sort, refresh, and select-all.
- **Pull down to update the whole library in single-list view.** Swipe down from the top of the one-scroll category view to start a library update (the same as the overflow menu's Update library), for both manga and novels.
- **"Downloaded only" mode now covers the novel library.** Turn it on (More menu) and the novel library hides novels with no downloaded chapters, matching manga; the Filter sheet's Downloaded chip locks on while the mode is active.
- **Category sort order and hidden categories.** Order categories Off / A to Z / Z to A everywhere they appear, and hide a category without deleting it (it round-trips through backups, including Komikku).
- **Delete categories with undo.** Long-press to multi-select categories (Select all / Invert) and delete several at once, or delete one from its row; either way an Undo snackbar lets you take it back. Works on both the Manga and Novels category tabs.
- **Library update-errors screen.** Opt in under Settings → Advanced for an Update errors list of entries that failed their last update, grouped by reason.
- **Panorama grid and source-icon badges.** A comfortable grid that shows wide covers uncropped, and an optional source-icon badge on covers.
- **Adult-content and category filters** in the library filter sheet.
- **Add to your library from global search.** Long-press a result in global search to add it (with the category picker and possible-duplicate check) or remove it if it's already saved, the same as the per-source browse screen. Works for both manga and novels.

**Manga details & recommendations**
- **Related-manga recommendations carousel.** A Related row suggests similar titles (with in-library badges) from the source and, when enabled, from AniList, MyAnimeList, MangaUpdates, and Shikimori; tap to open or global-search, and a See all grid bulk-adds with category handling.
- **A Recommendations settings screen** (Settings → Library → Recommendations) toggles tracker recs per tracker, builds a taste profile from your tracker libraries, and offers style, serendipity, auto-refresh, and library / status filters (all off by default).
- **Two-finger range selection** on manga and novel chapter lists: press two rows to select everything between them.

**Reader**
- **New options:** resume reading position, pages to preload (default 4), and mark a chapter read when you skip ahead.
- **A customizable bottom bar and an in-reader chapters list** to jump to, bookmark, or download chapters without leaving the reader.
- **Cover-color theming.** Tint the reader and manga details with each manga's cover color (Settings → Appearance, on by default).

**Networking**
- **Cloudflare bypass proxy support.** Route a blocked source through a self-hosted bypass proxy instead of the in-app WebView (Settings → Advanced → Networking); the WebView solver stays the default and the fallback.

**Backup & restore**
- **Your novel library is now backed up.** A backup captures your favorited novels with their chapters, read state, categories, history, tracker links, and cross-source merges; restoring on a fresh install brings the whole novel library back. Restoring over an existing library keeps whichever copy is newer, so an older backup won't overwrite edits you've made since (matching how manga restore works). Older backups made before this still restore fine.
- **Installed sources come back on restore.** A backup now records which manga extensions and novel plugins you had installed, so a restore reinstalls them automatically; anything whose repo is missing is listed in the restore log so you know what to add back by hand.

### Changes
- **See whether an extension update is for manga or novels at a glance.** On Browse → Extensions, the Manga and Novels chips now carry a count of their pending updates, so you can tell which side the update is on instead of just seeing one number on the tab.
- **Adult-source entries show their source logo in your library.** Saved entries from the built-in adult sources now use the source's mark as their badge, instead of a generic icon, matching the Browse source list.
- **Hide a novel source you don't use.** Long-press a source in Browse → Sources and Disable it: it dims in the list and drops out of global search, while staying installed and updating. Long-press again to re-enable.
- **Add a novel to your library straight from History.** A novel in the History tab that you haven't saved now shows an add-to-library button (like manga history rows); it favorites the novel and drops it in your default novel category, or asks.
- **New novels can auto-land in a default category.** Pick a default novel category under Settings → Library → Categories (next to the manga one); novels you add then go straight there instead of always asking, the same as manga.
- **Filter a novel's chapters by downloaded.** The novel chapter Filter sheet adds a Downloaded toggle (show only downloaded, or only not-downloaded) next to Unread and Bookmarked, the same as manga.
- **See which novels failed to update.** Turn on "Track novel update errors" (Settings → Advanced) and novels that fail an update are recorded; the Update errors screen gains All / Manga / Novels chips so both libraries share one list. Tracking is independent per type.
- **Update just a category of novels, and refresh novels from the Novels library.** A category's refresh button and pull-to-refresh on the Novels chip now update novels (they previously kicked off a manga update by mistake), and you can update a single novel category like manga.
- **Track a novel privately.** A tracked novel's Tracking sheet now has a "Track privately" toggle in the per-tracker menu (for trackers that support it, like Kitsu and AniList), keeping that entry off your public tracker profile, the same as manga.
- **Adult-source settings have their own place in Settings.** With adult sources enabled, the source's settings now appear as their own top-level Settings category (with its logo) between Security and Advanced, instead of being tucked inside Advanced. The "Enable adult sources" switch stays in Advanced, and the category hides again when you turn it off.
- **The built-in adult sources show their logo.** They now display their source mark in Browse instead of a blank placeholder icon.
- **More adult-source settings.** The settings screen adds Incognito mode (keeps that reading out of your history), Language filtering and Front-page categories (which sync to your account), and an updater-statistics view.
- **Adult-source favorites backup is gentler and more reliable.** Backing up a large library to your account now paces itself with a gradual backoff instead of a fixed delay, so it is less likely to trip the source's rate limits, and each favorite push retries a few times so a brief network hiccup no longer silently drops it.
- **Adult-source entry details show their full tags from the library.** Opening a saved adult-source entry from your library now expands the description and tag cloud by default, the same as when browsing the source. These entries have no description and their tags are the content, so they no longer hide behind a single sideways-scrolling row.
- **Renamed the fork to Reikai.** Installs upgrade in place (same package ID), and the launcher shows the new R-monogram icon and "Reikai" label.
- **The library Display options sheet is now tab-aware,** so a filter or category change made on the Novels tab no longer reaches into the manga library.
- **Extensions no longer tied to a repository are labeled "Orphaned"** instead of "Obsolete", with a clearer note that they won't receive updates.

### Fixes
- **Changing an adult source's update settings no longer crashes the app.** On optimized (preview / release) builds, changing the update checker's "Automatic updates" schedule crashed the app; it now applies normally.
- **Saved adult-source entries no longer get re-fetched on every library update.** They are now skipped by the regular library update (their dedicated update checker still handles them), so updates finish faster and stop needlessly hammering those servers.
- **No more duplicate built-in adult source if you also install its stock extension.** With built-in adult sources enabled, the matching stock extension is now hidden and its sources skipped, so it can't shadow or double up the built-in one.
- **The adult-source settings category shows up the moment you enable adult sources.** Turning on Settings → Advanced → Enable adult sources now reveals (and turning it off hides) the category on the main Settings screen immediately, instead of only after leaving Settings and coming back.
- **No more empty "Favorites backup" header in the adult-source settings.** Its options all need an account login and were hidden when logged out, leaving just the header; the whole section now appears only once you are logged in.
- **The restore screen opens reliably after you pick a backup file.** Choosing a backup (Settings → Data and storage → Restore, or from onboarding) sometimes needed a second tap before the "what to restore" options appeared; it now shows on its own.
- **Restoring a backup no longer lists your extensions twice.** After a restore, each reinstalled extension could appear both as a normal (trusted) entry and as a phantom "untrusted" duplicate. The reinstall now runs cleanly and a trusted extension correctly clears any stale untrusted entry, so the list is right immediately (no app restart needed).
- **Restoring a backup no longer drops random manga into the Default category.** A timing issue let some manga restore before their categories existed, so they landed in Default; categories now finish restoring first, so every manga keeps its categories. (A long-standing Tachiyomi-lineage bug.)
- **Manga merge groups now survive a restore to a fresh install.** Merges were saved as internal ids that change on restore, so groups could come back wrong; they are now saved as stable source + URL references and rebuilt correctly, the same way novel merges already were.
- **Migrating a merged manga or novel keeps the merge.** Moving an entry that belongs to a multi-source merge group used to drop it out of the group (and on the manga side leave a stale reference to the old source); migration now puts the new source in the old one's place, so the series stays merged. Works whether you merged the sources by hand or they auto-grouped by title.
- **A novel's "Download → Next 5/10/25" now advances through the book.** It used to keep re-picking the first chapters (already downloaded) and queue nothing on repeat taps; it now skips downloaded chapters and continues to the next batch.
- **The novel reader no longer crashes on a chapter with repeated paragraphs** (blank lines, scene breaks, recurring phrases).
- **Adding a light novel no longer creates a duplicate library entry** when you add the same novel again.
- **Novel plugins load and uninstall reliably.** Installed plugins now load in parallel and retry on the next Browse/Library open instead of needing repeated cold restarts; installing no longer hangs, uninstalling fully removes a plugin (even one installed from more than one repo), and reinstalling from a new repo replaces the old one. The Browse → Extensions (Novels) tab shows a restored repo right away and, when a repo can't be reached, offers Retry instead of claiming you have no repos.
- **Typing fast in the novel library search no longer scrambles or drops characters.** The search box now updates instantly per keystroke (matching the manga library) instead of lagging behind a background refresh, so a quick query like "shadow" filters correctly instead of coming out as "haodws".
- **Reading no longer fails with a random missing-image error.** When a cached page had gone missing from disk, opening it could throw a FileNotFoundException; the reader now treats it as not cached and re-fetches. (Synced from Mihon.)

### Other
- **Novel library writes are now surgical.** Favorite, cover, chapter-flag, and orientation changes update only the column they touch instead of rewriting the whole novel row, matching how the manga side works.
- **Fixed a startup crash in optimized builds.** Preview and release builds crashed on launch (a code-shrinker rule didn't cover the light-novel package); they now start normally.
- **Reikai is now built on the Mihon base.** The previous release was a fork of Yokai; this cycle rebases the app onto Mihon, so the core manga reader (library, details, reader, tracking, extensions, backups) is Mihon's, with Reikai's own features (light novels, cross-source merge, recommendations, and the library, reader, and theming additions above) rebuilt on top. This is why the core UI looks different; the `.y2k` package id is preserved so existing installs upgrade in place.
- **Support for TachiyomiX 1.6 extensions** (via the Mihon sync): the newer extension format installs and loads, existing extensions keep working, sources can attach hidden metadata carried through backups, and older backups still restore.
- **Synced upstream changes from Mihon:** Coil / OkHttp / Firebase updates, a SQLite driver build that avoids a rare database stall on a cancelled write, lifecycle-bound background tasks, and auto-following extension repositories that moved to the newer index format (now also reading gzip-compressed indexes and stores that keep their extension listing in a separate file).
- **Faster app startup.** Refreshed the bundled startup profiles (synced from Mihon) so common screens warm up sooner on first launch.
- **Faster backup restore.** Restoring a large library now batches its database writes in chunks, cutting restore time on big libraries; the speedup covers both manga and novels.
- **More Mihon upstream sync:** updated translations, refreshed app-shortcut icon colors, a Catppuccin theme tweak for clearer unread and downloaded badges, support for the newer tachiyomix extension metadata, and networking and dependency cleanup.
- **Crash screen points to Reikai's bug tracker.** If the app hits an unexpected error, the crash screen now suggests opening a GitHub issue (instead of Mihon's Discord), and the shared error/log files (crash, restore, library update) and the library CSV export are named for Reikai.

## [1.9.7.5.9]

### Additions
- **Taste profile** under Settings → Library → Recommendations. Pull your library from AniList / MyAnimeList / Kitsu (per-tracker toggles), auto-refresh on a `Never` / `7 days` / `30 days` schedule, manual refresh button with a 60 s cooldown, and a last-refresh summary line. Used by the related-mangas carousel to personalize what gets shown
- **Candidate injection** under Settings → Library → Recommendations (both default on). *Tag search on current source* runs your top taste-profile tags as searches on the current source. *Cross-recommendation from favorites* looks your top-rated tracked manga up on the current source and pulls each match's related-mangas list. Silently produce nothing when the taste profile is empty
- **Reranking** under Settings → Library → Recommendations. Master toggle *Rerank by taste* (default on) reorders the carousel against your taste profile and drops manga already in your library. Two sliders tune the behavior: *Recommendation style* (Popular ← → Personalized) controls how much taste weighs vs. the source's own popularity ordering; *Serendipity* (Familiar ← → Adventurous) reserves exploration slots and boosts rare tags. Both sliders are disabled when *Rerank by taste* is off
- **Filters** under Settings → Library → Recommendations (both default on). *Hide already-tracked* drops Reading / Completed entries from the carousel. *Hide dropped* drops Dropped entries. Plan-to-read and On-hold entries are now allowed back into the carousel as reminders
- **Full-screen "See all" browse** for related mangas. A "See all (N)" card appears at the end of the carousel when the pool exceeds 30 candidates; tapping opens a dedicated grid with no cap. Long-press to multi-select; bulk actions include *Add to library* (single category-set applied to every selection), *Select all*, and *Invert selection*. Grid column count scales to screen width, so foldables and tablets get more columns

### Changes
- Related-mangas carousel now activates source-native related-mangas data by default on every HTTP-backed extension. Hundreds of installable extensions immediately surface real source data alongside the existing keyword-search fallback and tracker recommendations
- Related-mangas carousel now collapses duplicates across streams. A manga returned by source-native, an AniList recommendation, and a favorite's related list at once now shows as one card instead of three
- Slow tracker recommendation endpoints no longer hang the related-mangas carousel. Each fetch now has a 15 s cap; on timeout the slow tracker is skipped and the carousel finishes populating from the rest
- Bulk-adding many mangas via the "See all" browse no longer freezes the UI for a few hundred ms while the category picker processes the selection

### Fixes
- Long-pressing a card in the related-mangas carousel no longer surfaces the chapter-list context menu by mistake
- Multi-selecting sources in **Manage Sources** and removing them from a group now writes the correct merge state and refreshes the chip row in place without needing to reopen the manga
- After fully un-merging a group and re-merging a subset, members that were left out are no longer silently re-admitted on the next library refresh
- Multi-source groups with members bound to different tracker IDs for the same service no longer silently overwrite one with the other on reconciliation. Ties leave both members untouched; propagation runs only when there's a strict majority

### Other
- New per-tracker library cache for the taste profile. Cache is rebuilt on demand from the tracker APIs and isn't included in app backups
- Documentation reorganized: `docs/` now holds only user-facing guides; maintainer-only docs moved under `docs/dev/`. New [`docs/backup-restore.md`](docs/backup-restore.md) covers Y2K ↔ upstream Yōkai backup compatibility and the `.yokai` → `.y2k` package-suffix migration

## [1.9.7.5.8]

### Changes
- **Package ID changed to allow installing alongside upstream Yōkai.** Release builds are now `eu.kanade.tachiyomi.y2k` (was `.yokai`). Existing Y2K installs need to back up → install the new build → restore; backup files are forward-compatible.

### Additions
- Manage Sources sheet now supports multi-select with two bulk actions: split selected sources from the group, or remove the selected entries from the library entirely. Tapping anywhere on a row toggles its checkbox, and both actions show an undo snackbar so accidental selections can be reverted within the grace period
- Tracker links now mirror across multi-source groups: adding a tracker on one source automatically links the same tracker on every still-in-library sibling, and both manual merges (Library multi-select) and auto-grouped same-title entries propagate existing trackers onto any newly joined source. Toggle in Settings → Tracking. Removing a manga from the library — via the Manage Sources sheet, the heart-button popup (single or "remove all sources"), or Library multi-select — also cleans up that manga's tracker rows. Explicit tracker-chip removal and Split actions leave siblings' trackers untouched
- Related-mangas carousel on the manga details screen — shows similar titles below the description, sourced from the current source (native related-mangas API where supported, otherwise a keyword-search fallback) and from public tracker recommendations (AniList, MyAnimeList via Jikan, MangaUpdates community ratings). Tracker recommendations work without a tracker login — if you've tracked the manga the remote id is used directly, otherwise a title-search resolves it. Tap a source-origin card to open the manga's details page; tap a tracker-origin card to jump to Global Search with the title pre-filled so you can pick a source to read on. Hidden when nothing is returned. Configurable under Settings → Library → Recommendations: master toggle for tracker recommendations and per-tracker on/off for AniList, MyAnimeList, MangaUpdates

### Fixes
- Source-switcher chips on the manga details screen now refresh when returning from another screen — previously, adding a same-title source via Global Search and pressing back left the chip bar showing the old set of sources until you backed out to Library and came back

### Other
- Source-API: added `getRelatedMangaList` with three opt-in flags (`supportsRelatedMangas`, `disableRelatedMangasBySearch`, `disableRelatedMangas`) and a built-in keyword-search fallback. Powers the new related-mangas carousel; sources can override `fetchRelatedMangaList` to provide native suggestions

## [1.9.7.5.7]

### Changes
- Cloudflare handling realigned with upstream: WebView is now the primary solver and FlareSolverr (when configured) is used as a fallback only if the WebView solve fails — drastically cuts wait time on most challenges

### Fixes
- FlareSolverr no longer rewrites the global User-Agent preference; the FlareSolverr-derived UA is now pinned per-host instead, preventing cross-source UA pollution
- FlareSolverr now returns the page response directly (proxy mode) instead of just cookies. Cookie/UA replay from FlareSolverr to OkHttp is unreliable for sites on Cloudflare's stricter bot-management tier because cf_clearance is bound to TLS / `__cf_bm` session fingerprint that OkHttp can't replicate; serving FlareSolverr's own response sidesteps the binding problem
- FlareSolverr now reuses a single browser session across all calls and skips the 30-second WebView pre-attempt for hosts already known to need FlareSolverr; subsequent requests after the first solve drop from ~42 s to ~1–3 s. Fixes a serialization bug in the same change that was throwing a MissingFieldException on the session-create response and short-circuiting the entire FlareSolverr path.

## [1.9.7.5.6]

### Additions
- Remove merged source groups from library in one step: new "Remove all sources from library" option in the manga detail favorite-button popup; bulk library delete now automatically includes all sources in any selected merged group

## [1.9.7.5.5]

### Additions
- Category bulk delete: long-press any category in Settings → Library → Edit categories to enter multi-select mode, then delete all selected categories at once with a single confirmation dialog and an undo snackbar

### Fixes
- Fix debug and nightly builds showing "Yōkai" instead of "Yōkai-Y2K" in the app launcher
- Fix crash when opening Manage Sources sheet: add no-arg constructor to satisfy Conductor's state-restoration requirement
- Fix source-switcher chips not appearing on large-screen / foldable devices: add chip row views to sw600dp-port and sw600dp-land layout variants
- Fix FlareSolverr re-challenging the same site multiple times in rapid succession: cookie removal is now deferred until an actual solve begins, and a 30-second reuse window prevents redundant solves for batch requests whose 403 responses arrive after a concurrent solve has already completed

## [1.9.7.5.4]

### Fixes
- Fix repeated Cloudflare challenge solves when switching between manga listing tabs: FlareSolverr cookies are now stored with proper domain scope (leading dot preserved) so they apply to all subdomains, and concurrent requests for the same host share a single solve instead of triggering parallel ones
- Fix `AndroidCookieJar.remove()` silently failing to delete cookies whose names had a leading space after splitting on `;`

## [1.9.7.5.3]

### Additions
- Add FlareSolverr support for Cloudflare bypass; configure the service URL in Settings → Advanced → Network

## [1.9.7.5.2]

### Other
- Update in-app GitHub links to point to unseensnick/yokai-y2k instead of upstream

## [1.9.7.5.1]

### Additions
- Add multi-source manga grouping: same-title library entries collapse into a single card with a source-count badge
- Add source-switcher chip row in manga details to switch between grouped sources
- Add manual merge/unmerge: "Merge selected" in library multi-select, long-press a chip to remove an entry from a group
- Add "Manage sources" sheet in manga details overflow menu to add or remove entries from a source group
- Add category sort order setting (off / A→Z / Z→A) under Settings → Library

### Other
- Rebrand to Yōkai-Y2K (fork of upstream Yōkai 1.9.7.5)

## Earlier releases

Versions before `1.9.7.5.1` are inherited from upstream Yōkai (Reikai began as a fork of Yōkai 1.9.7.5). See the [Yōkai project](https://github.com/null2264/yokai) for that history.
