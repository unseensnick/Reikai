# Changelog

All notable changes to this project will be documented in this file.

A release is grouped by the area it touches (Library, Reader, Tracking, ...), and within an area by a simplified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/): `Added` for new features, `Changed` for behaviour and visual changes, `Fixed` for bugfixes. `Other` is the last area and holds technical changes with no user-facing effect. Releases up to 0.3.2 group the other way round, by `Additions` / `Changes` / `Fixes` first and area second.

Reikai uses its own [Semantic Versioning](https://semver.org/) from the Mihon-based releases onward. The earlier `1.9.7.5.x` versions tracked the upstream Yōkai release Reikai was based on.

## [Unreleased]

### Highlights

Manga and light novels stop being two apps in one. A new All chip shows your whole library as a
single list, a category can hold both kinds, and one search grammar, sort, filter and grouping
covers everything. Browse, global search, Extensions and Migrate each became one list too.

Grouped series got the fix that mattered most. A chapter is now identified by the group's own
stitching instead of by chapter number, which two sources of one series rarely agree on. Reading,
bookmarking, downloading, counting and notifying all land on the right chapter now.

Migration was rebuilt. Manga and novels share one flow, matches are offered rather than assumed,
failures are named and can be retried, and you choose what carries across at the moment you confirm.

Updates and History can merge into a single Recents tab under Settings -> Appearance, with Grouped,
Feed, History and Updates views, search, swipe actions and category filtering.

Light novels gained three trackers built for novels, a font picker spanning the whole Google Fonts
library, chapter clean-up rules, find and replace, and a new reader: novels now open in the same
reader manga uses, drawn as real text or as a web page. Novel extensions from tsundoku and IReader
now install beside the LNReader plugins, and their novels work like any other.

Manga pages can be drawn by a new high quality renderer, bringing dual page view, page transition
animations, HDR and a display cutout mode.

Crash reporting and anonymous usage data are opt-out under Settings -> Security and privacy, and
every release now also ships a foss build with neither in it.

### Library

#### Added

- **A new All chip shows your whole library, manga and novels together.** One list, one sort, with each series opening in its own reader; the Manga and Novels chips now simply filter it.
- **A category can now hold both manga and novels.** Pick whether a new category shows in manga, novels or both when you create it.
- **Edit categories is now one list covering manga and novels, with an All / Manga / Novels chip to show one library at a time.** Each row says which libraries it applies to and one drag order covers them all; a category you create starts on the library you are looking at.
- **Library search understands field terms and comparisons, like `author:kubo`, `genre:horror -genre:ecchi` or `unread>5`.** One grammar for manga and novels, so a query means the same thing on every entry the All chip shows.
- **Library search can now find an entry by one of its chapter names, with `chapter:epilogue`.** Works on manga and novels, and combines with everything else, so `chapter:finale -genre:horror` does what it reads like.
- **The library's three-dot menu can now refresh tracker data for everything you track, in one pass.** Scores and statuses were only pulled when you opened an entry, so sorting or filtering by tracker score read whatever was last cached.
- **Settings -> Library -> Recommendations can now move the related-manga carousel off the details page into its three-dot menu.**
- **Long-pressing a second category on the Edit categories screen now selects everything between the two.** Long-press one you have already picked to drop it again.
- **With categories in manual order, each card on the Edit categories screen now has a menu to move it straight to the top or bottom.** It works under the Manga and Novels chips too, where dragging is off.

#### Changed

- **Manga and novels now share one library sort, filter set and grouping, and your novel library takes on the manga library's.** Set any of them under either chip and both follow; per-category sorts are untouched.
- **The Default category now follows your global library sort instead of taking a sort of its own.** It is one shared bucket across both libraries, so it could not sensibly be sorted two ways at once.
- **A category you collapse stays collapsed on both the Manga and Novels chips, and after a restart.** Collapsing is now remembered per category rather than per chip; in the novel library it used to spring back open every time you left.
- **Empty categories are now always hidden, and the "Show number of items" setting is obeyed on novels too.** A category with nothing to show never renders a bare header, on any chip.
- **A category that only applies to one library now says so in the filter picker.** Each row carries "Manga only" or "Novels only" under its name, so picking one while looking at the other library is no longer a silent surprise.
- **Failed library updates are now recorded by default for manga and novels, and the notification opens the list of what failed.** Switch it off under Settings -> Advanced and the notification opens one log file covering both libraries instead.

#### Fixed

- **A new-chapters notification naming several chapters now counts the unnumbered ones in its "and N more".** One whose chapter number arrived twice from a merged series no longer counts it as an extra chapter.
- **Typing quickly into the Library, Recents or a source's catalogue search no longer scrambles your text.** It reordered and duplicated characters, and after a Compose update dropped them.
- **Library badges no longer crowd out the unread count or the title.** A cover with 408 unread on a grouped series could read as "4", and a list row gave its title away to the source icons; the badges now share a measured width and the icons give way first.
- **A grouped series' chapter list now reads straight down instead of alternating between its sources.** Two sources rarely agree on what number a chapter is, so the list was being ordered by a number that means something different on each one, on manga and novels alike.
- **Missing chapter warnings no longer invent gaps on a grouped series, in the chapter list or between chapters in the reader, on manga and novels alike.** They compared numbers across two sources that count differently, and believed a volume extra's title enough to claim hundreds were missing.
- **A grouped novel no longer lists a chapter twice when its title is only a number.** With no words to match on, both sources' copies survived; they are now paired by where they sit between the chapters that do have titles.
- **The app no longer freezes on the Library while light-novel plugins are being set up.** It could hang long enough for Android to offer to close it, most often on a slow or freshly started device.
- **Chapter range selection now works the same on manga and novels, and keeps out the chapters you dropped.** Extending a range used to re-add a chapter you had deselected, and on novels the next long press took it back.
- **Inverting a library selection in one category no longer clears your picks in the others.**
- **The library update no longer refetches a finished gallery from an adult gallery source once you have read it.** Its page also keeps the description and status the source gives it.
- **Reset all in Edit info now clears a cover you set by hand, so the series goes back to the source's own cover.** It reset the text fields and left the picked cover in place, on both manga and novels.
- **Downloaded badges now notice chapters you delete outside the app.** The check was meant to run hourly but restarted its clock on every launch, so opening the app more often than that meant it never ran.
- **On a merged series, tapping the cover shows the selected source's cover, and changing the cover is done on the All chip.** Your library shows the group's cover, so an edit made under one source would have looked like it did nothing.
- **Settings -> Library -> Preferred sources no longer opens with a large empty gap above the list.** A short ranking was being centred on the screen, which read as a broken page; it now starts under the tabs.
- **Backing out of the category picker no longer adds a novel anyway.** Nothing is written until you confirm, matching how manga has always behaved.
- **A series that fails to be added no longer ends up filed under a category it never joined.** If the add cannot complete, nothing is written at all now, on both manga and novels.
- **Every category picker now follows your category sort order.** Adding from browse, global search, History, a bulk selection or a series' own Edit categories listed them in database order instead, on both manga and novels.
- **Searching from another screen, Open random entry, updating one category and the Library button's second tap now act on the library chip you are looking at.** All four picked manga whichever chip was selected.
- **A big novel library update no longer loses its summary notification.** Past a certain number Android refuses the rest, which cost novel updates their summary and left a stray system-drawn icon in the status bar.
- **You can now move manga into a hidden category from the library's Change categories action.** Hidden categories were missing from that list, so there was no way to pick them.
- **Grouping the library by source now shows real source names on the category tabs, not the raw internal key.**
- **Novels in the library now show their real language on the language badge and in group by language.** Polish and Portuguese novels shared one "Po" group, and most other languages showed a clipped name instead of the code manga uses.
- **Grouping the library by tag or author no longer splits one tag into two groups.** Sources that spell a tag differently, like Adult and ADULT or Sci-Fi and Sci Fi, now land in a single group.
- **The library settings sheet now offers its adult-content filter on novels too, based on genre tags.** It is less reliable than the manga one, which can use a source's own flag.
- **Novel library sorting now matches manga: ties stay A to Z under a descending sort, fully-read novels sink under the unread sort, and titles order by your device language.**
- **Two novel search prefixes changed spelling: `id:5` is now `id=5`, and `src:slug` is now `srcid:slug`.** They are the manga library's spellings, so both libraries answer one grammar.
- **Typing in the novel library's search no longer rebuilds the list on every keystroke.** It waits for a short pause first, like the manga library.
- **Selecting novels in the library is no longer slow.** It used to get worse the more novels you had.
- **Library actions now act on the category you have scrolled to in the single-list view.** Select all, Invert selection, Update category, Open random entry and the hopper's long-press sort all acted on the first category instead.
- **Library search now reads the title, author, artist, description and genre you set in Edit info, not just the source's.** So an entry you renamed is findable by the name you gave it.
- **The novel library-update and download category filters now include the Default (uncategorized) group.** You can include or exclude novels that are not in any category, matching the manga filters.
- **Excluding a term from library search now applies to adult-source entries too.** A query like `-genre:horror` used to leave them in the results regardless.
- **Bulk actions on selected novels now always run to completion.** Marking read, changing categories, downloading or removing could quietly stop partway if the app closed mid-action; manga already ran these to the end.
- **Deleting a category now clears it from the library and Updates filters.** No filter is left pointing at a category that no longer exists.
- **The library's continue button and Recents now pass over chapters you hid, on manga and novels.** A hidden chapter still opens when it is the only one left unread.
- **Downloading from a library selection no longer queues chapters you hid, on manga and novels.** That covers every option, bookmarked chapters included, as the series page already did.
- **A novel whose source is no longer installed now shows the missing-source warning on its library cover, as a manga does.** On a grouped novel the uninstalled source keeps its place among the source icons instead of vanishing.
- **In Settings -> Library -> Preferred sources, one tap of Up or Down now always moves a source, on manga and novels.** A source you had uninstalled sat hidden in the ranking and swallowed the tap; it now keeps its place while the others move past it.
- **The continue button on a novel in the library now follows that novel's chapter filters, as manga and the novel's own page do.** Set to bookmarked or downloaded chapters only, it opened the first unread chapter regardless.
- **A category you set back to the global sort now stays that way if the app is closed partway through updating from an older version.** The upgrade step that keeps per-category sorts could run a second time and mark it as custom again.
- **Clearing a novel's history now drops it in the library's Last read sort, as it does for manga.** The sort now reads a novel's reading history; novels read before history was kept carry their place over on upgrade and from older backups.

### Merged series

#### Added

- **Drag a source to the top of Manage sources on a series' page to make it lead that merged series.** The combined chapter list follows it; Reset order returns to your global Preferred sources ranking.
- **Adding a series from Browse, global search or History now asks whether to group it with a match already in your library.** Tap select, choose which ones it joins, and it lands in their categories too.

#### Changed

- **Source grouping is now optional, via "Group series across sources" in the library display menu or Settings -> Library.** Off shows each source as its own library entry.
- **On a merged series, your library, its page and History open the whole group, while Updates, source chips and new-chapter notifications open just that one source.** The reader follows whichever you came from.
- **Reading a chapter now marks its duplicates read by default, whether a second listing of it in the same series or its copy on a merged series' other sources; change it under Settings -> Library.** The setting is "Mark duplicate read chapter as read".
- **Removing a merged series from your library now ticks "All grouped sources" by default.** Untick it to remove only the source shown on the cover.
- **Settings -> Advanced now has one "Clear all merges" action per content type instead of two, under its Library section.** The two did the same thing.

#### Fixed

- **A hidden chapter of a merged novel now stays hidden, and skipped by the reader, when its source is not installed.**
- **A merged series' combined chapter list and unread count now catch up after anything that changes its chapters or its sources.** Removing a source or adding it back, a renamed, renumbered or removed chapter, a half-finished update, a follows sync, a gallery import, a detail repair.
- **A merged series whose top-ranked source has no chapters now lists its other sources' chapters in full.** Novels showed none of them, and manga dropped the ones with no chapter number.
- **A source you removed from a merged series now opens on its own from History or Browse, on manga and novels.** From a group of three or more, its page listed the other sources' chapters and a manga failed to open in the reader.
- **A merged manga's library cover, title and badge now come from the source its chapter list leads with.** A source with most of its chapters under a hidden scanlator could lead one and not the other.
- **Removing a merged series no longer overstates how many sources it will take with it.** The count now covers the grouped sources it can actually reach, so it matches the number of entries you selected.
- **Splitting or removing the source you are currently viewing no longer leaves the series' page showing another source's chapters.** The title and cover stayed the one you opened while the chapter list quietly became someone else's.
- **Migrating one source of a merged series no longer breaks that series' page.** With that source's chip selected, the chapter list could crash on manga and show the old source on novels.
- **Change categories on a merged series no longer drops categories that only some of its sources were in.** Those categories now show as partly ticked, and are left alone unless you change them.
- **Reading or bookmarking a chapter on a grouped series now lands on that same chapter across its sources, and shows as read there too.** It matched by chapter number, which two sources rarely agree on, so a mark could hit a neighbour or spread between unrelated works on a gallery source.
- **The library's Started and Bookmarked filters now count a merged series as started or bookmarked when any of its sources is, on manga and novels.** The Total chapters sort and the read and total search terms count the combined chapter list too, not the leading source's.
- **A merged series' unread count now counts each chapter once across its sources, instead of only the leading source's.** The library badge, the unread filter, the sort and the Continue button all follow the number its chapter list shows.
- **Marking a merged series read, or moving it between categories, now applies to every source in the group.**
- **A merged novel's combined chapter list no longer hides a chapter whose title differs only by a trailing number.**
- **A merged manga's scanlator filter now covers the source chip you have selected, or every source under All.** It used to list and hide only the source the series was opened from, so a sibling's chapters by a hidden scanlator still showed.
- **Library search now finds a merged series by any of its sources' names, ids or languages, not only its leading source's.** So does the library search you open from a source chip on the series' page.
- **Saving Edit info on a merged novel with a source chip selected no longer stores that source's details as your edits.** Opening the editor from a selected source and saving untouched used to keep its differing title, tags and cover as permanent overrides.
- **Share and Open in WebView now follow the source chip you have selected, on novels as well as manga.**
- **Selecting a source chip on a merged manga now shows that source's synopsis and tags, as novels already did.** They stayed on the leading source while the title and cover changed.
- **Migrating and cover edits now act on the whole merged series whichever source chip is selected, and a custom title stays visible under a chip.**
- **A merged series now downloads each chapter once, however many of its sources carry it, and deleting one removes every source's copy.** Download next follows the group's combined list in your chapter sort, and the download badge counts each chapter once.
- **A chapter you bookmarked before merging its series now still shows as bookmarked in the combined list.** The Bookmarked filter on the series page and in the reader follows the same answer.
- **The reader's "Skip chapters marked read" now skips a chapter read on another source of a merged series.** So do the Unread filters on the series page and in the reader.
- **A merged row in Updates and History now shows, and changes, the read and bookmarked state for every source, as the library does.** Marking read, bookmarking or deleting a download on it applies to the whole group, in the Recents tab too.
- **Continue reading a merged series from History now opens the same chapter the library would, and carries on through the whole group.** History ignored the series' chapter sort, so a series sorted by name or upload date resumed somewhere else, and a merged light novel continued through one source only.
- **A merged series' library update now announces a new chapter once, not once per source that carries it.**
- **The Updates widget now draws one cover per merged series.** It drew one per source, so a grouped series filled the grid.
- **A chapter you continue from History on another source of a merged series no longer appears twice in the reader.** The copy you opened takes that chapter's place, so the chapters before and after it are the right ones.
- **On a merged novel, the reader's chapter list now shows a chapter as read, bookmarked or downloaded when any source's copy is, and names each chapter's source.** Deleting a download from it removes every source's copy, so the row stops reading as downloaded.

### Updates & History

#### Added

- **A long press on a grouped row in Updates now selects everything between it and your last pick, that whole group included.** It only ever selected the group you pressed.
- **You can now search the Updates feed.** Type part of a title to narrow it, the way History already worked.
- **History can now be filtered by category.** Its filter icon sits in the toolbar and its selection is its own, so filtering Updates leaves History alone.
- **Swipe a row in Updates to mark it read, bookmark it or download it, using the actions you already picked under Settings -> Library.** Both swipe directions work, on manga and novels alike.
- **Settings -> Appearance can now merge Updates and History into one Recents tab, with Grouped, Feed, History and Updates views.** Grouped and Feed show what to read next and leave out series you are caught up on; the tab is off until you turn it on.
- **The Upcoming calendar can now be filtered by category (synced from Mihon, mihonapp/mihon#3607).** Exclude the categories you don't follow closely and the calendar only shows the rest.

#### Changed

- **The Updates category filter is now one list covering manga and novels, and the category pick you had there is cleared.** A Manga / Novels chip narrows the list you pick from, and a manga-only category now hides novels, as in the library.
- **Updates now tells you when a filter is what emptied the feed, with a button straight to it.** It used to say "No recent updates" whether nothing was new or your own filter had hidden everything.
- **The Updates and Upcoming filter icons now light up for every filter you have set, not just the chapter ones (synced from Mihon, mihonapp/mihon#3772).** A category filter used to leave them plain, so a narrowed feed looked unfiltered.
- **A manga chapter you have opened now says how long it is on the Recents rows and in the chapter list, as "Page: 5/38".** The length is only known once you have opened it; novels already showed a percentage.

#### Fixed

- **History rows can now be long-pressed for bulk actions, the same as everywhere else.** Bookmark, mark as read or unread and download from the selection, the way the combined tab already worked.
- **History rows now show the chapter, the time and your place in it, each on its own line, with a download button.** Each row says whether it was updated, read or added.
- **With Updates' "Group by series" on, its rows now restack as soon as you merge or unmerge sources.** They used to wait until the screen was rebuilt.
- **Moving a series to another category now updates the Updates feed straight away.** The feed kept filtering by wherever the series was when you opened the screen, until you left and came back.
- **Tapping History again now always resumes the most recent thing you read, from the library you are looking at.** A search you had typed in could send it to a different entry, and with the Novels chip on it could pick up a manga instead.
- **Pull to refresh on Updates now spins until the library update has actually finished.** It stopped after a second whatever the update was doing.
- **An Updates row you expanded to see its new chapters now stays open when the screen rotates.**

### Details

#### Added

- **Holding the title, author or artist on a series now offers to search your library or every source, instead of only copying.** A library search opens the content type the series belongs to, so a novel never searches your manga.
- **Holding a series' source name now offers a library search for that source's entries, except on a merged series under All (partly synced from Mihon, mihonapp/mihon#4002).** Tapping the name still browses the source.
- **A series' details overflow can now open its download folder in your file manager.** On a merged series it opens the source you are viewing, or under All the first source holding downloads, and shows only when that folder exists.
- **A series' details overflow can now clear its downloaded chapters.** It clears the source you are viewing on a merged series, appears only when there is something to clear, and leaves your reading progress, bookmarks and history alone.
- **A series' details overflow now opens its source's settings.** It appears only when the source has any, and on a merged series it opens the settings of the source you are viewing.
- **A novel's page now hands its web link to the Android assistant and the recents screen, as a manga's does.** On a merged novel it is the link of the source you are viewing.
- **The chapter list on a merged series now says which source each chapter came from.** Manga rows name the source before the scanlator; novel rows name the source, and neither shows anything when the series is not merged.

#### Changed

- **A manga or novel's page now shows the outline of what it is loading instead of a spinner.** It matters most for a novel opened from Browse, which waits on the source before anything appears.
- **Related-manga suggestions now label where each one came from, in both the carousel and the full grid.** The source, the tracker, or the taste reason behind the pick.
- **Searching a genre from a novel's page now goes back to its source's catalogue with that genre filtered, as manga does.** Opened from anywhere else, it searches your library.
- **A novel's Download menu now leaves out chapters your filters hide while Settings -> Novel reader -> Skip filtered chapters is on, as manga's does.** With it off, it picks from every page of chapters stored.
- **Set as default in a novel's chapter settings now asks first and can apply the settings to your whole library, as manga's does.** A message confirms the change.
- **A novel opened from a source, a search or the feed now shows its synopsis expanded on a phone, as manga does.**

#### Fixed

- **A series whose site shows a placeholder until its cover loads now keeps its real cover.** A series already stuck on the placeholder takes its cover back from its source's listing.
- **Searching a genre from a manga's page no longer applies it to a different source's catalogue further back.**
- **Tapping a series' source name now opens that source to browse.** It used to run a search for the source's own name, and from most screens it did nothing at all.
- **Page previews on an adult source's details page no longer go blank over time.** Their thumbnails were remembered against links that expire, so a series you had opened before came back as numbered blanks.
- **A novel's page now shows its artist, when it has one separate from the author.** Manga pages already did; tap it to search, like every other field there.
- **The full-screen cover viewer, Save and Share now use the cover URL you set in Edit info.** They kept showing the source's original cover while the series page showed yours.
- **Long-pressing a novel's WebView button now copies its link, like manga.**
- **Removing a novel from the library on its page now offers to delete its downloaded chapters, as manga does.** Removing a novel from anywhere also clears its saved cover, a custom one included, so a re-add starts clean.
- **Closing Edit info while Fill from tracker is still loading no longer shows a tracker error.**
- **Titles you just added from the full related-manga grid no longer come back selected with your next pick.**
- **Tapping a tag chip on a series from an adult gallery source or an enhanced source now searches that source in its own tag format.** Every source got one gallery site's format, so most of those searches found nothing.
- **The full related-manga grid now says when your filters hide every suggestion, with a button to show them.** It used to open on a blank page.
- **The related-manga Hide dropped, Hide on-hold and Hide plan-to-read filters now work with every manga tracker.** A series tracked only on MangaUpdates, Hikka, MangaBaka or MDList was never hidden.
- **The related-manga row on a manga's page now keeps up to 12 places for tracker recommendations, even when a source fills it with its own.** Its most popular picks also come from suggestions your filters leave visible.
- **Related-manga suggestions no longer shrink while they refresh, or disappear when a refresh fails offline.** The ones you had stay until a complete new set arrives.
- **When two manga chapters share a number, upload date or name, Resume, Continue reading and Download next now pick the one the reader opens next.** Ties follow the source's own order, as they already did for novels.
- **Chapter selection on a manga or novel page now counts and acts on only the chapters your filters show.** On manga, Select all and Invert also picked hidden chapters, so a bulk action could change rows you could not see.

### Reader

#### Added

- **A manga or novel chapter that fails to load now offers Retry and Open in WebView, where a manga reader used to close on a brief message.** For a source that takes pages, saving the page there opens the chapter.
- **Manga pages can now be drawn by a new high quality renderer, switched on under Settings -> Advanced (synced from Mihon, mihonapp/mihon#3388).** It brings dual page view, page transitions, a display cutout mode, HDR, a Min width slider for the long strip modes, and a page Gap slider for Continuous vertical.
- **Settings -> Novel reader can switch on selecting, copying and sharing text in the novel reader, which costs link taps in native text mode.** Both readers keep every other gesture while it is on.
- **The novel reader carries the full option set: bionic reading, Remove extra spacing, tap zones, Swipe between chapters, volume-key scrolling and auto-scroll.** Settings -> Novel reader gains a Scroll speed slider for the last of them.
- **The novel reader now reads straight on into the next and previous chapters, which Settings -> Novel reader -> Continuous chapters can switch off.** A marker names each boundary, and Add the next chapter at sets how far in the next one appears, 95% by default.
- **Settings -> Novel reader can now find and replace text in a chapter before you read it.** Each rule matches plain text or a pattern, and a sample box shows what it would do before you save it.
- **The novel reader can now read a chapter aloud, from a Read aloud button on the button bar.** Its floating controls read from the paragraph on screen, step between paragraphs and set a sleep timer whose time left shows in the notification.
- **The novel reader highlights the paragraph being read aloud, or with Highlight sentence on the sentence, in a style and any colours you set under Settings -> Novel reader.** With sentences, Next and Previous step by sentence too.
- **Settings -> Novel reader now picks its font on its own screen, where you can search the whole Google Fonts library or import a file.** Every font's row previews itself, Serif, Sans serif and Monospace included, and what you add works in both rendering modes.
- **Bottom bar buttons under Settings -> Manga reader and Settings -> Novel reader now set the order of the reader's buttons, not just which ones show.** Drag a button by its handle, the settings button included, which is always shown.
- **The reader's top menu can reload the open chapter where you are, from its downloaded copy or fresh from the source.**
- **Both readers' bars can now name a chapter by its number, or by number and name, under Settings -> Manga reader, Settings -> Novel reader or the reader's Appearance tab.** Each reader keeps its own choice, and a number the chapter's name already opens with is not shown twice.
- **Settings -> Manga reader and Settings -> Novel reader can now hide the chapter navigator, which moves the chapter buttons to the ends of the button bar.**
- **Both readers can now put a Scroll to top button on the bottom bar.** It returns a novel to the chapter's start and manga to the chapter's first page.
- **Both readers' bottom bar can now be edited without leaving the reader, from Edit bottom bar in the top bar's menu.**
- **Novel chapters in the WebView rendering mode now show the styling their light-novel plugin ships, such as the boxes some chapters draw stats in.** A plugin already installed picks it up on its next update or reinstall.

#### Changed

- **The reader's settings button now opens the same sheet for manga and novels, with Reading, Appearance, Controls and Filters tabs, plus Read aloud for novels.** Manga keeps every setting it had, and a novel gets its own, rendering mode and text selection among them.
- **With Theme based on cover on, the novel reader's bars now take the novel's cover colours, as manga's do.**
- **The novel reader now holds far less memory for chapters with tall pictures.** A part scrolled onto quickly can look blurry for a moment until its sharp slice loads.
- **Novels now keep their own brightness, colour filter, grayscale and inverted colours, set from the novel reader's Filters tab.** A brightness or colour filter set in the old novel reader comes back.
- **The novel reader's button bar now starts with text size and theme buttons, unless you have already chosen its buttons.** They are the quickest way to change either while reading.
- **Reader settings are now two entries, Manga reader and Novel reader, each holding only that reader's options.** The single Reader screen had grown to 68 rows with the novel options scattered through it under "· Novels" labels.
- **The reader's quick reading-mode menu now highlights the mode you are reading in, and just opening it no longer sets that mode for the series.** A series following your default used to show an empty grid.
- **Manhwa, manhua and webtoons now open in webtoon mode on their own, and can be switched off under Settings -> Manga reader.** It reads each source's own genre tags, so a series none of your sources tags keeps using your default reading mode.
- **The hardware bitmap threshold, legacy long strip decoding and custom display profile settings are gone from Settings -> Advanced (synced from Mihon, mihonapp/mihon#3786).** All three configured the legacy decoder, which manga pages no longer use.
- **Novel chapters that "After reading automatically delete" removes now stay downloaded until you leave the reader, as manga chapters do.** Paging back to one offline used to fail.
- **Finishing a novel chapter in the reader no longer deletes it under "After manually marked as read", which now applies only to marking by hand, as for manga.**

#### Fixed

- **A merged series now opens in webtoon mode when any of its sources calls it a manhwa, manhua or webtoon.** Before, only the source the chapter came from was consulted, and that is usually not the one carrying the tag.
- **Chapters you have read no longer disappear from the manga reader's chapter list, and tapping one opens it.**
- **Rotating the screen while a chapter is opening no longer leaves the reader stuck loading (synced from Mihon, mihonapp/mihon#3686).**
- **Swiping back from a manga chapter you just finished now reaches the previous chapter, read or not.** It used to say there was no previous chapter.
- **Swiping a chapter in either reader's chapter list now runs your configured swipe action instead of always bookmarking.**
- **A novel showing chapter numbers instead of titles now labels them in your app language, like manga.**
- **The manga reader now names the chapter you are actually on while you scroll across a chapter boundary.** It briefly showed the previous chapter's title and page count beside the new chapter's page number.
- **Each chapter you open in the manga reader now starts where you left that chapter, not where you left the one before it.** Most visible right after jumping in from a page preview.
- **Skip duplicate chapters now removes them from a novel's chapter list, instead of only stepping over them.** Download ahead and delete after reading counted the duplicates, so they fetched fewer chapters than asked and could delete the wrong one.
- **Download ahead in the novel reader no longer fetches chapters you have already read.** It passes over them to the next unread ones, as the manga reader does.
- **A novel chapter marked read by skipping past it now also deletes older downloads, as reading it to the end does.** On a merged novel its copies on the other sources are marked too.
- **A novel chapter that fails to load no longer shows in History as the one you read last.**
- **Read-aloud now carries on when the novel reader rebuilds its page, from the paragraph it was on.** Rotating the screen or changing a text setting used to stop it and drop its notification.
- **Skipping forward in a novel with "Mark read on skip" on no longer waits on your trackers before the next chapter loads.** The chapter you left is finished in the background instead.
- **Auto-scroll now carries on into the next novel chapter by itself.** You had to show and hide the toolbar again to restart it, and a rotation stopped it the same way.
- **The novel reader's voice list now follows the read-aloud engine you pick.** It kept offering the previous engine's voices, so choosing one there left read-aloud speaking in the new engine's default.
- **The novel reader's chapter list now opens quickly on a grouped novel.**
- **The novel reader now has its own Fullscreen, cutout and progress rail settings under Settings -> Novel reader and its Appearance tab, starting from the defaults.** They were set from the manga reader screen for both readers.
- **On a grouped manga, Open in browser, Open in WebView and Share now use the site the chapter came from.** They built the link from the source you opened the series under, which often named a page that does not exist.
- **Download ahead on a grouped manga now fetches the chapters the reader will actually reach next.** It followed the order the sources were stitched in rather than your chapter sort.
- **Skip duplicate chapters no longer folds chapters with no number, like a prologue and an afterword, into one.** Each is kept in its place in both readers.
- **Download ahead in the manga reader no longer fetches hidden chapters or duplicates the reader skips.**
- **Scrolling into the next manga chapter and straight back no longer leaves the reader on the chapter you left.** The bookmark button and Open in WebView could act on it.
- **Bookmarking or marking a novel chapter read just before closing the reader is no longer lost, and a grouped novel's copies are bookmarked together.** Before, a bookmark could land on some sources' copies and not others.
- **Retrying a manga page that failed to load now always fetches it again (from mihonapp/mihon#3770).**

### Light novels

#### Added

- **Novels in your library now get smart update: a predicted next release on the details page, and an option under Settings -> Library -> Global update · Novels to skip novels outside their release period.**
- **Novel sources can now be hidden per language, from the switch each language carries in the sources filter.** Switching one off hides all its sources from Browse and search, like manga.
- **Adding a duplicate novel now gives you a one-tap Migrate, moving progress, categories, cover and tracking to the new source.**
- **A novel from an IReader extension can take its details, chapters or a chapter's text from a page you open in its WebView menu.** It is the way through for a site that blocks the app.
- **Settings -> Advanced can now repair novels that are showing another novel's title or cover.** It finds the affected entries and re-fetches them from their own source.
- **Clear database now also removes novels that aren't in your library.** Novel sources get their own rows on the screen, and the keep-read toggle protects novels with reading progress, like manga.
- **A novel's update notification now names the chapters it found and offers Mark as read and Download.** It only ever said how many there were, and gave you nothing to do about them.
- **Updating your novel library now shows how far along it is, as a percentage.** Manga already did.
- **Long-pressing an installed light-novel plugin in Browse -> Extensions now offers to remove it.** It asks first, since Android has no uninstall prompt of its own for a plugin.
- **A novel's chapter list can now be sorted alphabetically, the fourth sort manga already had.**
- **Settings -> Novel reader can now tidy up a chapter before you read it.** Hide a heading that just repeats the chapter name, block images and video, split walls of text into paragraphs, force lowercase, and choose whether a chapter's own styling runs.
- **Novels now open in the same reader manga uses, drawn as real text, or as a web page under Settings -> Novel reader -> Rendering mode.** The previous novel reader is gone; a mode change applies the next time you open a chapter.
- **Settings -> Novel reader can now skip chapters marked read and skip filtered chapters going forward, like manga, with Skip filtered chapters on by default.** The previous-chapter button still reaches the chapter you just finished.
- **Novel read-aloud now pauses for calls, other apps' audio and unplugged headphones, and answers headset buttons, in every rendering mode.** It resumes by itself only after a short interruption, and never starts over a call.
- **Settings -> Novel reader -> Text display now sets your page margins, paragraph indent and paragraph spacing.** Each of the four margins moves on its own, and a page padding you had already set becomes your left and right margins.
- **The novel reader has a new near-black theme, and its page background and text can each be set to any colour from the reader's Appearance tab.**
- **Settings -> Novel reader can now set how far into a chapter a novel counts it as read, from 50% to 100%.** It stays at 97% until you change it.
- **Settings -> Novel reader can now show a chapter's raw HTML as text, in either rendering mode.** It helps tell a source's broken markup apart from a reader problem.
- **The novel web page reader can now add your own CSS and JavaScript snippets to every chapter, under Settings -> Novel reader.** JavaScript snippets restored from a backup come back switched off.
- **Settings -> Advanced has a switch, off by default, that opens every web page in the app to a computer's browser inspector and shows the novel reader's script errors as toasts.**
- **Settings -> Novel reader can now swap the vertical chapter navigator for a horizontal slider above the bar's buttons.**
- **The novel reader now has the manga reader's tap zones plus top and bottom, center and bottom-only layouts, in its Controls tab and Settings -> Novel reader.** Zones can be inverted, and a reader who had Tap edges to scroll on keeps it as Top and bottom.

#### Changed

- **Share on a novel's details page now sits in the menu, as on manga.**
- **Novel text size now goes from 10 to 40, line spacing from 0.8x to 5x, paragraph indent up to 10em, and Split walls of text up to 2000 words.**
- **Sorting a chapter list "By source" now really follows that source's own listing, on manga and novels.** It was quietly sorting by chapter number instead, so a source that lists its chapters out of numeric order looked tidier than it is; pick "By chapter number" for the old result.
- **Scripts, embedded frames and tap handlers in a novel chapter are now stripped before it renders, unless you allow them under Settings -> Novel reader.** Chapter markup comes from the source rather than from Reikai.
- **A slow novel source can no longer stall global search, browsing or updates for every other source.** Each now runs in its own engine, and idle ones free their memory after a minute.
- **Bulk-deleting downloaded novel chapters now asks you to confirm first, like manga.**
- **A novel chapter you have already read now opens at its start, like manga, unless Settings -> Novel reader -> Resume reading position is on.** It used to reopen on its last screen.
- **A library novel now keeps its title when its source renames it, unless Settings -> Advanced -> Update library titles to match source is on, as for manga.** With it on, the novel's downloaded chapters move to the new title instead of reading as not downloaded.

#### Fixed

- **Pulling down to refresh a novel now downloads its new chapters when Download new chapters is on, as it does for manga.**
- **A new copy of a novel chapter you already read now arrives read, even when it lands on another page of the chapter list.**
- **A read chapter on a novel with a paged chapter list no longer comes back unread and announced as new after a refresh.** A chapter that moves to the next page keeps its read state and bookmark.
- **A novel chapter its source returns empty now says so, where it used to open as a blank page.**
- **WebView and Share on a light-novel plugin's novel now open the page the plugin names for it.** They joined the stored path to the site, which for some plugins is not a real page.
- **Light-novel plugins now stay on the installed version until you update them from Browse -> Extensions.** They used to switch to the newest one on their own whenever Android cleared the app's cache.
- **A light-novel plugin update published at a new link now shows as an update in Browse -> Extensions.**
- **Time spent reading a novel now keeps counting after you switch away and come back.** It was recorded as none from that point until the next chapter.
- **Read aloud in a novel no longer skips a very long paragraph.** It reads the whole thing, broken at its sentences, where the voice engine used to reject anything past its own size limit and move on in silence.
- **Novel auto-scroll now moves at the speed you set, rather than creeping along at a fraction of it in visible jerks.** The reader page's own stylesheet was animating every step of the scroll.
- **On a merged novel, downloading from the All chip now downloads the chapters All is showing.** It fetched one source's chapters while you were looking at another's, so nothing on screen ever appeared as downloaded.
- **A link inside a novel chapter now opens in your browser instead of taking over the reader.** A chapter could previously send the reader's own view to any page it liked.
- **A novel chapter with a stray plaintext tag no longer breaks the reader page.** Everything after the tag, the reader's own code included, used to show as text.
- **Opening a downloaded novel chapter no longer freezes the reader while it loads.** Chapters with pictures in them were the worst affected.
- **Opening a novel with a lot of chapters is faster.** The reader was reading every chapter back out of the database one at a time before it could show you anything.
- **Searching every novel source at once no longer closes the app.** Starting several sources together could take the whole app down before any results arrived, so the All filter in novel global search was the quickest way to hit it.
- **Adding a novel from its details page now files it in your default novel category.** It opened the category picker every time instead, even with a default set, the one add path that ignored the setting.
- **Novels now use the same category picker manga does.** It carries the Edit categories shortcut, and with no categories yet it offers to make one instead of doing nothing at all.
- **Updating the novel library now tells you when an update is already running.** It used to say it had started a new one every time.
- **A novel filter that matches nothing no longer says your library is empty.** Your novels are still there, behind the filter.
- **Novel update notifications now carry the Reikai icon instead of a generic book, like manga.**
- **Updating your novel library can no longer save one novel's title and cover onto a different novel; refresh an affected entry to restore its details.**
- **Updating several light-novel plugins at once no longer loses one of them.** Two updates finishing close together could leave a plugin listed as updatable however often you updated it.
- **A novel's full-cover view now loads on sources that need a referer.** Opening it before the source finished resolving left the request without one for as long as the page stayed open.
- **An adult content source's update notice no longer dismisses the novel library's error notice.** The two shared a notification slot, so one silently replaced the other.
- **Picking the Black reader theme again draws the novel reader's progress percentage in the same dimmed white as the text, instead of pale yellow.** A reader already set to it keeps the old colour until the theme is picked again.
- **The novel reader, resuming and next-chapter downloads now follow the order you sorted a novel's chapter list into.** They walked the source's order or the chapter number, so on a novel sorted by name or date they reached a different chapter.
- **A new novel chapter numbered like one you have already read now arrives read, when the duplicate-chapter setting asks for it, and is no longer announced or downloaded as new.** The setting only reached manga before.
- **A novel chapter its source moved to a new address no longer comes back as new in update notifications, or downloads again.**
- **Covers and chapter pictures from light-novel sources that ask for their own image headers now load, in both reading modes and in downloads.** The WebView reading mode also reuses the pictures the text mode already downloaded.
- **Ongoing novels from some light-novel sources no longer show as Completed, and library updates no longer skip them.** Refreshing a novel corrects the status it was saved with.
- **A novel library update no longer undoes a change you make to one of its novels while it runs, such as removing it from the library or editing its notes.**
- **Refreshing a novel's details now says why it failed, as manga's does, where it used to stop in silence.** A source that lists no chapters now reads as No chapters found there and in library update errors.
- **A novel library update now reports a novel whose source is no longer installed, as manga's does, where it used to skip it in silence.** Update errors, library grouping and search name a removed source as it was last installed rather than by its id.
- **A novel's new-chapter notification no longer replaces or dismisses a manga's, and tapping the novel summary now opens Updates, as manga's does.** The two could share a notification slot, so one silently took the other's place.
- **Hiding chapter titles on a novel no longer changes its chapter order, and sorting a novel's chapters no longer changes how their titles show.** Each follows your global default until you change it on that novel.
- **A novel chapter dated month first, like 12/25/2024, now shows that date instead of one months later.** A date that is not a real day now shows no date rather than a rolled-over one.
- **Downloading a selection of novel chapters no longer fetches the ones already downloaded again.**
- **A light-novel plugin that stops answering now fails with an error after three minutes, so a migration moves on to the next entry instead of searching the stuck one forever.**
- **Smart update under Settings -> Library -> Global update · Novels now lists its options in the same order as the manga one.**

### Browse & sources

#### Added
- **Sites that block the app but let its built-in browser in now load without a FlareSolverr server.**
- **A FlareSolverr server behind a password now works: sign in to it under Settings -> Advanced.** Credentials already typed into the address move across on upgrade, and neither field is included in a backup unless you turn on private settings.

- **A chapter in the local manga source now takes its date from the Year, Month and Day in its ComicInfo.xml (synced from Mihon, mihonapp/mihon#3967).** Only the file's last-modified date could set it before.
- **Any source's filters can now be saved as a named search and re-applied from a chip while you browse that source (ported from Komikku).** Long-press the chip to delete the search.
- **Browse can now show a Feed tab, turned on under Settings -> Browse, with one row of covers for every source or saved search you add to it (ported from Komikku).** A row holds that source's latest, or what the saved search returns; the tab takes twenty, and a long press removes a row.
- **The Feed's rows can now be dragged into the order you want.** The order sticks across restarts, and comes back with a backup restore.
- **Pick several covers across the Feed's rows and add them to your library together.** Manga and light novels in one batch, each filed into its own categories.
- **Browse -> Sources now has a search box that filters the list by source name, extension name or id.** Separate terms with commas to match any of them.
- **Long-press a source in Browse -> Sources to turn incognito mode on for it, light-novel sources included, or for its whole extension on a manga source.**
- **Settings -> Browse can now hide the Latest button on Browse -> Sources rows.** Latest stays one tap away inside each source.
- **Source catalogues in Browse now offer the panorama comfortable grid.** Wide covers show whole instead of cropped.

#### Changed

- **The Repos screen is now one list of cards, each showing how many extensions or plugins a repo lists, or that it couldn't be reached.**
- **Add repo now works out whether an address is an extension store or a novel plugin repo, and turns down one it can't read.**
- **Rows in Browse -> Sources now show a flag beside the language, the extension name when a source is named differently, and an 18+ or Mixed label from the extension's content warning.** Light-novel plugins declare no content warning, so only manga and light-novel extension apps show one.
- **Settings -> Browse and sources now picks which extensions load by content warning, Safe, Mixed or 18+, instead of one NSFW switch (synced from Mihon, mihonapp/mihon#3951, mihonapp/mihon#3952).** Your NSFW choice carries over, changes apply without a restart, and the filter can leave installed extensions alone.
- **Installed extensions and light-novel plugins that fail to load now appear under Not loaded in Browse -> Extensions (synced from Mihon, mihonapp/mihon#3953).** Tap one to see why, copy the error, or uninstall it.
- **The Sources list now shows manga and light-novel sources in one list, grouped by language.** Each row says which kind it is while both are showing, the chips filter that one list instead of switching between two, and the language groups run in the same order as on Extensions.
- **The Sources list now remembers one "Last used" source across manga and light novels.** Opening either kind updates it, except while incognito, and it starts empty after this update until you next open one.
- **The Extensions list now shows manga extensions and light-novel plugins in one list, sectioned the same way.** Pending updates share one section with a single Update all, searching filters both halves at once, and each row says which kind it is.
- **The Migrate list now shows manga and light-novel sources in one list, sorted together.** The sort controls cover the whole list instead of vanishing when both kinds are showing.
- **Global search now searches manga and light-novel sources in one run, with All / Manga / Novels tabs at the top.** Sources are ordered together, so whichever kind found something rises above the ones still working.
- **A global search selection can now add manga and novels to your library together.** Categories are asked for once per kind, because the two libraries keep their own.
- **Global search now remembers whether you last searched pinned sources or all sources.**
- **A light-novel source now browses in your chosen grid column count, like manga does.** Both kinds of source draw their results through one grid, so the display mode means the same thing on either.
- **Backing out of a source's search now returns to the source instead of leaving it.** The grid goes back to the source's listing, and backing out again leaves as before.
- **Choosing what a manga migrates to now browses the source the normal way, with chips, filters and your grid layout.** It used to open a stripped-down grid, while light novels already used the full one.
- **Browsing a light-novel source now offers the same toolbar as a manga source.** Search, display mode, Select, Open in WebView and the source settings sit in the same places on either.
- **A light-novel source only offers Latest when it can really list latest.** Around half the plugins ignore the request and hand back the popular list, so the chip is hidden on those instead of quietly repeating Popular.
- **The Browse sources filter now covers manga and light novels from one screen, whichever chip you opened it from.** A Manga / Novels chip switches halves; the All and Manga chips used to reach only the manga sources.

#### Fixed
- **Tapping "Back up all favorites now" again while an adult source's favorites backup runs no longer starts a second one.** Two at once pushed every favorite twice.
- **When an adult gallery source replaces a gallery with a newer version, chapters the new version already had now keep the old version's read state, bookmark and progress.** Only chapters new to it carried them over, while History still showed them as read.
- **The large mainstream source the app enhances now syncs follows, tracks and signs in through the language you set as preferred in its settings.** It always used the first enabled language.
- **Series details from the large mainstream source the app enhances now follow its extension's switches for adding alternative titles and the final chapter to the description.** The final chapter is on by default, as in the extension.
- **Tapping a follows sync action while one is running now says a sync is already running, instead of silently stopping the first partway.**
- **Browsing a source or opening a series while offline now says "No Internet connection" instead of a raw host error, on manga and novels.** The manga reader's failed pages say it too.
- **Manga sources that work out their pages with JavaScript now show those pages again, where some opened chapters empty or failed to load.**
- **Global search and the feed no longer crash when a source lists the same result twice.**
- **Global search no longer shows a blank screen when nothing is pinned or no source found anything.** It says which, and on Pinned offers to search all sources.
- **Forms a source posts through FlareSolverr now reach the site intact, so a novel plugin's chapter list loads there.** A space in a form no longer arrives as a plus sign either.
- **Pages fetched through a FlareSolverr server on your own network or over HTTPS now come back signed in to the site.** Your cookies are never sent to a solver reached in the clear over the internet.
- **A page fetched through FlareSolverr now reaches the source whole when a block of preformatted text on it starts with a bracket.** It was mistaken for a JSON reply and cut down to that one block.
- **A Cloudflare bypass that takes over a minute now finishes instead of failing for no visible reason.** Reikai gave up at the very moment it had asked the server to answer by, and the test button called such a server unreachable rather than slow.

- **The seven sources whose metadata the app enhances now open their settings, from the extension list and from their own catalogue.** The extension list gave an empty page and the catalogue had no settings button; they include a large mainstream source and several adult ones.
- **Light-novel sources and plugins now group under their language, beside the manga sources of that language, and show it as a short code in the migration source picker.** Plugin repos name a language in that language ("Español"), which the app read as a language of its own and could not put a heading on.
- **An installed light-novel plugin is no longer listed a second time as available to install.** It happens when a repo offers the plugin at a second address.
- **A light-novel source whose plugin is gone now says "Not installed" on the Migrate list and leads it.** Those hold the novels you can no longer open, and only manga sources were flagged before.
- **A source in global search no longer spins forever with its results already fetched.** Sources that finished at the same moment could erase each other, leaving one stuck on loading.
- **The Hide entries already in library setting now applies to novel sources too.** Browsing keeps loading further pages when everything on a page is already in your library.
- **Light-novel source icons are no longer larger than manga ones in the same list.** Most noticeable on the Migrate tab, where both appear one under the other.
- **The duplicate warning for a novel now shows its artist and flags a source that is no longer installed, like the manga one.**
- **Peeking at a possible duplicate no longer throws away the add you were making.** Long-press opens it, and the same question is waiting when you come back.
- **Opening a title from Browse no longer shows it pre-grouped with same-named titles in your library.**
- **A global search run moments after opening the app now waits for your sources instead of quietly searching fewer.** Manga and novels alike, and the searched source list no longer depends on how fast the app finished starting up.
- **Testing FlareSolverr now names what went wrong on the row itself, including a solver that is still starting.** A server behind a password, a wrong address and one that is simply down all read the same before; the server's own words are a tap away and can be copied.
- **Testing FlareSolverr no longer leaves sources looping on a Cloudflare challenge, and resetting your user agent under Settings -> Advanced fixes one that already is.** The test used to store FlareSolverr's browser as your app-wide agent, which the in-app bypass could never get past.
- **A Cloudflare challenge the site abandons now fails in seconds rather than after half a minute.** With the solver on, one it has already started pressing keeps going, since those are often reissued.
- **The Cloudflare bypass no longer risks taking the app down when its browser process dies.** It ends the request instead of waiting out the timeout.
- **A Cloudflare challenge that arrives after a redirect is now solved instead of waited out.** The bypass watched the address you asked for rather than the one the challenge was served on.
- **A failed Cloudflare bypass no longer poisons the next request to that site.** It left the rejected clearance behind, so the retry hit a plain refusal with no offer to open the page yourself.
- **Clearing a site's cookies in the WebView now removes the ones it shares with its subdomains.** Those were left behind, so a site could stay signed in or stay challenged after a clear that reported success.
- **Open in WebView now opens the page a Cloudflare challenge blocked, so there is something to solve.** It opened the source's front page, which often carries no challenge at all, so nothing cleared and Retry kept failing. Works on manga and novels.
- **Manga browse now reloads by itself when you come back from the WebView.** Novels already did.
- **Browse -> Sources and Extensions now show their list as soon as it is ready, without a short pause first.** Clearing the search box restores the full list at once too.
- **Two languages whose codes share one name (such as "in" and "id") no longer lose a section in Browse's lists and source filter, on manga and novels.**
- **A light-novel source that is no longer installed now says so in your language and by its name, in its catalogue, on the novel page and in the reader, as a manga source does.**

### Migration

#### Added

- **Manga and novels now migrate through one shared flow, so both get every migration feature.** Whichever you are moving, the screens, options and safeguards are the same.
- **A migration now names the entries that failed and offers to retry them.** A failure used to be logged and reported as a success, leaving entries silently where they were.
- **Choose what a migration carries at the moment you confirm it, on both manga and novels.** Only the options the selected entries can actually use are offered.
- **Matches are now offered rather than assumed: accept them one at a time, or all at once.** Tapping an accepted match gives it back so you can pick a different target.
- **Leave an entry out of a migration, so a source that never answers can't hold up the rest.** Skipping takes it off the list, as does migrating it, so what's left is always what still needs you.
- **A finished migration tells you how many entries moved.**
- **Search a target by hand, or browse a whole source, when the suggested match is wrong.** Every source you chose is searched, and one that fails says so instead of looking empty.
- **Check a match before you commit to it: tap a batch row's match or long-press any result to open its page, and anything already in your library is marked.** A match whose latest chapter is behind the entry's shows by how much, in red.
- **Set how a migration searches before it runs: extra keywords, advanced search mode, ranking by chapter count, and filters for unmatched entries or ones already up to date, on manga and novels alike.**

#### Changed

- **Migrating a manga now keeps the page you reached in each chapter, as migrating a novel keeps your place.**
- **Migrating no longer asks the target's source for the same thing twice, roughly halving the load a large migration puts on the site.** That matters most where rate limits bite.
- **Migrating a manga or novel with its chapters now brings its reading history along.** Its History entries and its place in the library's Last read sort follow it to the new source.

#### Fixed

- **Migrating a novel no longer searches sources or languages you have disabled.** The migration search now respects the same source filter as global search.
- **A light-novel search result already in your library now shows the cover your library shows, including one you set yourself.**
- **Cancelling a migration part-way no longer leaves a merged series half-moved.** The entry could disappear from your library while still counting toward a merged series, with no way to reach it and put it back.
- **Migrating a novel with "Delete downloaded" now stops its queued downloads and no longer downloads those chapters again on the new source.** The queued ones used to keep downloading into the source you had just moved away from, and the files they wrote stayed behind.

### Tracking

#### Added
- **Settings -> Tracking has a refresh button on each tracker you sign in to with an account, so a nickname or score format changed on the site reaches Reikai without signing out.** Works for the light-novel trackers too.

- **Binding a tracker to a novel you have already read now fills in when you started reading, as it does for manga.**
- **Light novels can now be tracked on RanobeDB, NovelList and NovelUpdates, three services built for novels.** Sign in through a browser window on any of them, or paste a personal access token on RanobeDB; what each keeps in sync differs, because not all of them store a score, reading dates or an on-hold state.
- **Turning on list matching in the tracking settings points NovelUpdates statuses at your own reading lists, not just the five it starts with.** Each status picks the list it moves a novel to.
- **Filling a novel's details from a tracker now works with RanobeDB, NovelList and NovelUpdates, which know novels better than the manga services do.** It fills the description, author, artist and genres.
- **Every tracker search except NovelUpdates can now take an id, written as `id:12345` (synced from Mihon, mihonapp/mihon#3776).** AniList, Bangumi, Hikka, Kitsu, MangaUpdates and Shikimori join MyAnimeList and MangaBaka, and RanobeDB and NovelList take one too.
- **A Kitsu search can now take a title's web-address name too, written as `id:shadow-slave` (synced from Mihon, mihonapp/mihon#3792).** Handy when you have the Kitsu link but not the number, and it works on manga and novels alike.
- **Extensions that sync reading to their own site now hear what you read, add and remove, on manga and novels, once their own tracking setting is on.** A failed sync shows a message.
- **Migrations are now passed on to extensions that sync reading to their own site, on by default with a switch under Settings -> Tracking.**
- **NovelUpdates now moves your reading-list bookmark to the chapter you read, for novels from its app or plugin and for other sources' chapters whose number matches a release.**
- **Novels added from the NovelUpdates app or plugin now track there on their own once you sign in to NovelUpdates.**
- **Rereading an earlier chapter leaves NovelUpdates where it is, unless you turn off Settings -> Tracking -> Never move progress back.**
- **Settings -> Tracking -> Move back on unread moves your NovelUpdates bookmark back when you mark chapters unread.** It is off by default.

#### Changed

- **MangaUpdates results now show each entry's rating and creators while you pick one to bind (synced from Mihon, mihonapp/mihon#3795).** Covers manga and novels alike.
- **Kitsu scores now use whichever rating scale your Kitsu account is set to, smileys, stars or the 10 point decimal (synced from Mihon, mihonapp/mihon#3818).** Existing scores are converted on upgrade, for manga and novels alike.
- **Marking a chapter read now updates the tracker status on the entry straight away, on manga and novels.** It kept showing the status from before the push, so an entry could sit on "plan to read" while the service already said reading.

#### Fixed
- **Binding or changing the status of a series from your own manga server when you have not started it no longer marks its Chapter 0 read, in Reikai or on the server.** Reading progress of 0 was taken as "read up to chapter 0".
- **Backing out of the category choice when adding a manga from its page no longer binds its server tracker.**

- **Fill from tracker now says "No entry found" when the tracker has no such entry.** A failure with no reason says "Unknown error" instead of ending in a blank.
- **Start and finish dates pulled from MangaBaka no longer land a day early in timezones behind UTC (synced from Mihon, mihonapp/mihon#3711).**
- **AniList tracking now stays under the service's request limit (synced from Mihon, mihonapp/mihon#3942).** The old ceiling sat above what AniList allows, so a burst of updates could come back rejected.
- **A tracker set on one source of a merged series now shows, updates and is removed on all of its sources, unless you turn off Share trackers across merged sources.** The chip, reading progress, mark-as-read, refresh and the Tracked filter follow the whole group.
- **Reading an older chapter from another source of a merged series can no longer push your tracker's progress backwards.**
- **Breaking up a merged series, or migrating one of its sources, now hands each remaining source its own copy of the tracker.** Every way out of the library does it: splitting from Manage sources, removing from the library, the series page, browse, migration, and Settings' "Clear all merges".
- **A merged series is now filtered, sorted and grouped by tracking status from every one of its sources, not just its main one.**
- **The library's tracking-status groups now always read in reading-progress order (Reading first, Not tracked last), instead of being sorted alphabetically by your category sort.**
- **Sorting the library by tracker score no longer floats signed-out trackers above your rated entries.** A merged series also counts each tracker once instead of doubling it across sources.
- **The tracker refresh notification now uses the app's own refresh icon.** It was showing a generic Android sync glyph.
- **An expired AniList sign-in now says so and points you at Settings, instead of failing with a generic error (synced from Mihon, mihonapp/mihon#3888).** The expiry check had been reading the stored time as a far-future date, so the app kept sending credentials AniList had already rejected.
- **A MyAnimeList entry dated with only a year, or a year and month, no longer errors out (synced from Mihon, mihonapp/mihon#3573).**
- **A score you pick on MangaBaka is now saved as that score, at every step size (synced from Mihon, mihonapp/mihon#3740).** With steps larger than 1 it was sending the score's position in the list instead.
- **A MangaBaka score no longer skews your library's tracker-score sort and your statistics.** Its 0 to 100 scale was being read as if it were out of 10, so one scored entry floated to the top and pulled the average with it.
- **When linking or updating a tracker fails, on manga or novels, the message now says why in plain words, such as No Internet connection or Log in to NovelList again.** A failed novel link used to crash the app, and a failed auto-match said no match was found.
- **Binding a novel that is already on your NovelList reading list now keeps its status, progress and score there.** It used to reset them; a novel with chapters read locally moves to Reading unless it is Completed.
- **Marking a chapter read on a merged novel now sets your trackers to that chapter, not to a higher number another of its sources gave its copy.**

### Downloads & extensions

#### Added

- **Tap a series in the download queue to see its chapters, cancel one, start one now, move one to the bottom, or read why it failed.** A downloading manga chapter shows its page count.
- **Settings -> Downloads -> Pacing sets the wait between novel chapters, for every source or one source at a time, never below what the source asks for.** Manga sources pace themselves.
- **The novel download notification now has Pause and Show entry, as manga's does.** A paused queue leaves a notification with Resume and Cancel all, and a failed chapter's has Show entry.
- **Novels from novel extension apps, IReader's included, now browse, search, read, download and update like plugin novels, and the apps install and update in Browse -> Extensions like manga extensions.**
- **When a list mixes kinds of novel source, each one is labelled JS, APK or IReader.** Browse, global search, the feed and migration all do it.
- **Extensions from IReader's own repo load without a trust prompt, as a signed repo's do.**

#### Changed

- **A downloaded manga chapter now records its upload date in its ComicInfo.xml (synced from Mihon, mihonapp/mihon#3967).** Move the folder into the local source later and the date comes with it.
- **The download queue is now one list for manga and novels, and any series can be dragged above any other.** The Manga and Novels chips are gone; a badge names each card's type while both are queued.
- **Download queue cards show the chapter downloading now, and their counts no longer reset when you reopen the queue.**
- **Novel downloads left waiting in the queue no longer start on their own when the app opens, as with manga, though ones cut off by closing the app still resume.**
- **Downloaded novel chapters show as downloaded as soon as the app opens.** The novel download folder is no longer rescanned on every launch, only hourly, as manga's is.
- **Rows in Browse -> Extensions now read the same for every kind of extension, and a pending update shows the version it brings.** Available rows show their version under their language heading, installed rows their language and version.
- **Novel plugin repos in Browse -> Extensions now refresh when you pull down, rather than each time you come back or install a plugin.**
- **Reading a novel in incognito now downloads the next chapters ahead, as reading manga in incognito does.** Incognito keeps your history out, not your downloads.
- **Deleting a novel's downloads by hand now keeps bookmarked chapters, and read chapters in categories kept from removal, as manga does.** Settings -> Downloads decides both, and removing a novel from the library still clears all of its downloads.

#### Fixed

- **The Browse badge now counts light-novel plugin updates from app launch, as it does extension updates, without Browse being opened first.**
- **The light-novel plugin update notice now leaves out plugin names under Settings -> Security -> Hide notification content, and goes away once no plugin needs updating, as the extension notice does.**
- **Installing an extension through Shizuku works again.**
- **An extension row no longer shows a stray dot before its version after an install is cancelled.**
- **Extensions marked Orphaned lose the mark as soon as you add a repo that lists them, rather than after a restart.**
- **An extension whose repo publishes no icon now shows the default source icon instead of a broken image.**
- **A novel source whose icon fails to load shows the placeholder instead of an empty space.**
- **The Browse badge on the home screen now counts novel plugin updates, as the Extensions tab's own badge does.**
- **Updating a privately installed extension no longer switches it to a shared install.**
- **Trusting an extension now works from Browse's All chip.** The prompt only appeared with the Manga chip selected; under All, tapping the shield or the row did nothing at all.
- **Removing a privately installed extension from Browse's All chip now asks for confirmation.** A long press removed it outright, where the Manga chip has always confirmed first.
- **A resumed image download now shows the right progress instead of restarting from zero.**
- **Cancel on the novel download notification now cancels instead of pausing.** The queue came back and carried on the next time you opened the app.
- **A novel chapter that failed to download can be started again, by Resume or by Start downloading now, and stays in the queue after a restart, as a manga chapter does.** Resume skipped it, so it sat in the queue as an error with no way to get it going short of queueing it afresh.
- **An outdated manga or novel extension no longer crashes the app when you browse it or open one of its series.** It shows an error instead, as a library update already did, and a novel extension's chapters fail the same way when read or downloaded.
- **A downloaded novel chapter with pictures now keeps the line breaks the source draws.** Saving it folded them into spaces, so a chapter laid out with its own breaks read as one run-on block offline.
- **A downloaded novel chapter's pictures now show offline even when the source offers them in several sizes.** Both readers went back to the source for the larger ones.
- **Tapping install, update or reinstall twice on a light-novel plugin no longer shows a false install error.**
- **The download queue no longer counts a cancelled chapter as downloaded.**
- **Manga downloads queued without a connection now start on their own once it returns, as novel downloads do.**
- **With Downloaded only on, a novel's chapter list and reader now show only downloaded chapters, as manga's do.**
- **Deleting a novel's last downloaded chapter now removes its empty folder, as manga does.**
- **Settings -> Advanced -> Reindex downloads now covers novel downloads too, as does restoring a backup.**
- **Pausing manga downloads from the notification now leaves the paused notification to resume from.** It used to vanish, leaving the queue paused with no way back from the shade.
- **Reordering or sorting a paused novel download queue no longer starts it downloading.**
- **Pausing novel downloads no longer marks the chapter being retried as failed and drops it from the queue.**
- **A novel chapter its source returns empty now fails to download at once and says why, instead of retrying and reporting an unknown error.**
- **The Settings -> Downloads note that download-ahead needs the current and next chapter downloaded now sits under Manga, where it is true, instead of Novels.**
- **The More tab's download row now reads Paused while novel downloads are paused, matching the download queue's own Resume button.**
- **Resuming novel downloads right after pausing them now carries on with the queue.** A Resume that landed while a chapter with pictures was still saving could leave the queue sitting idle until you tapped it again.
- **The first tap on a download queue sort now sorts ascending.** The sort sheet opened showing chapter number as already applied, so the first tap flipped it to descending.
- **A novel chapter download now fails at once with an out-of-space message when the device is nearly full, as a manga one does.** It used to retry against the full disk and fail with a raw system error.

### Backup & restore

#### Changed

- **Light-novel plugin and IReader extension settings now back up with Source settings instead of App settings.** Backups made before this update still bring them back with App settings.
- **Details you edited yourself now back up in Komikku and Yōkai's format, which Reikai 0.3.2 and older nightly builds restore without them.** They restore in either app, theirs restore here, and backups from any earlier Reikai still bring them back.
- **Settings -> Data and storage -> Library list now exports your novels too, and writes a merged series once.**

#### Fixed

- **Restoring a backup now keeps a default category set to ask every time, and no longer changes category settings the backup did not include.**
- **Restoring a backup now brings your light-novel plugins back by itself, and names any it could not.** A backup carries a plugin's address but not its script, so the novel sources it restored could not open anything until you reinstalled each plugin by hand, with the restore reporting no errors.
- **Restored light-novel plugins are now always checked against your added repos before they load.** A backup made after an earlier check could switch the check off.
- **Restoring a backup now keeps Default as your default category and in your update categories, and a Yōkai backup no longer sends them to its last category.** A Yōkai backup's other category choices are left out, since it saves no way to match them.
- **The restore log now names every manga extension a restore could not reinstall, including one whose install failed, was cancelled or timed out.** It used to list only those whose repo was missing.
- **Your own title, author or cover edits on a series you have read but removed from the library are no longer missing from a backup.** They were dropped whenever the backup included read entries outside the library.
- **Picking a backup to restore now opens the system file picker, which some devices could not open before (synced from Mihon, mihonapp/mihon#3948).**
- **A backup now keeps the sources you removed from a merged series, so adding one back after a restore on a new device rejoins its series, on manga and novels.** A two-source series with one removed used to come back not merged at all.
- **Restoring a backup no longer collapses unrelated series into one.** Two series you had grouped separately came back as a single card whenever your device already had a source of each merged together.
- **Restoring a backup now leaves merged series it says nothing about untouched.** They keep their group, their order and their leading source.
- **Restoring a backup no longer re-merges a pair you deliberately split.**
- **A restore that stops part way no longer leaves your merged series split apart.** Merged series you had grouped before the restore used to come back apart if it failed or you cancelled it mid-way; now the grouping either survives whole or is replaced whole.
- **Restoring a backup no longer fails outright because of one merge entry it can't apply.** That entry is skipped, matching how novels already behaved.
- **A problem while restoring merged series or edited details no longer stops the rest of the restore.** It is recorded in the restore log instead, and the other content type finishes.
- **One bad entry in a restore no longer takes a hundred others down with it (synced from Mihon, mihonapp/mihon#3667).** The rest of the batch is retried one at a time, so only the entry that actually failed is reported.
- **A backup holding the same series twice under one source now restores instead of failing (synced from Mihon, mihonapp/mihon#3667).**
- **A light novel that fails to save during a restore is now named in the restore log instead of silently going missing with other novels restored alongside it.**
- **Restoring a backup now keeps your novel category filters and default category instead of quietly dropping them.**
- **A backup made with Categories on but Library entries off now includes your novel categories.** Manga backups already did.
- **Restoring with the Categories option unticked no longer files your novels into categories anyway.** Manga restores already left them alone.
- **A backup with the read-entries option on now includes novels you have read but removed from your library, like manga.** Their read history used to drop out of the backup, including after migrating a novel to a new source.
- **Novel reading time you cleared from History now survives a backup and restore, as manga's does.** Stats still counts it, but the backup left it out, so the total dropped after a restore.
- **Restoring an older backup no longer rewinds a manga chapter you had read further on this device.** Novels already kept the further position.
- **Restoring a backup no longer overwrites a light novel's tracker status, score and dates with the backup's, matching manga.**
- **The warning before a restore and the restore log now name a missing light-novel source instead of showing its id.** Backups made before this update still show the id.
- **The warning before a restore no longer claims your light-novel sources are missing.** It read the source list before the plugins had loaded, so a restore begun from a fresh launch listed every one of them.

### App

#### Added

- **A background job that fails to start now shows its own notification naming it.** It used to fail silently, and a scheduled one kept failing the same way every time it ran.
- **The What's new screen now draws GitHub's Note, Tip, Important, Warning and Caution callouts.** They were dropped entirely before, so a warning written into a release note never reached you.
- **Reikai can now send crash reports so bugs get found and fixed faster, and both they and anonymous usage data are opt-out under Settings -> Security and privacy.** Onboarding offers the same choice on a fresh install.
- **Every release now also has a `-foss` APK with no crash reporting or analytics in it at all.** It installs as a separate app, so it can sit alongside your normal one.
- **A new Tokyo Night app theme, selectable under Settings -> Appearance.**
- **Icons across the app are now drawn in Google's newer Material Symbols style (synced from Mihon, mihonapp/mihon#3873).** Eleven that Mihon does not ship, like the novel reader's text-alignment controls and the gallery star ratings, keep the look they have now.
- **Settings -> About now links Reikai's website and privacy policy.** Both open reikai.app, which is where the documentation lives.
- **Settings -> Advanced -> Solve interactive Cloudflare challenges ticks the verification box instead of giving up, while you use the app or, with a second switch, during background library updates.**

#### Changed

- **Each of Reikai's own notification categories in Android's settings now has its own name, so novel updates, novel downloads and each background sync can be told apart.**
- **Settings -> Advanced now opens on Debugging and Help headers, and the two Track update errors switches moved to the end of Settings -> Library -> Global update.** Manage notifications sits under Background activity.
- **Settings search now finds what is on the About screen, like the licenses and the update check.** About is also sorted into Legal and Links sections instead of one flat list.
- **The two "Hide missing chapter indicators" settings now sit together under Settings -> Library -> Behavior, each saying which content type it affects.** The novel one used to sit in the novel update group, with nothing telling the two rows apart.
- **Every source's settings now live in one place, under Settings -> Browse and sources.** The two that had their own entry at the top of Settings moved into a Source settings group there, joined by "Enable adult sources" from Advanced and "Page preview rows" from Appearance.
- **Recommendations is now its own entry in Settings instead of sitting inside Library.** One tap instead of three.
- **Nightly builds now have a teal icon, so they are easy to tell apart from the stable app.** They were both purple before.
- **The pre-release channel is now called Nightly, matching Mihon (synced from Mihon, mihonapp/mihon#3760).** The About screen and the release title say it; downloads keep their file names and installs are unaffected.
- **Updating the app now happens on the update screen itself, with the download progress on the button (synced from Mihon, mihonapp/mihon#3669 and mihonapp/mihon#3707).** Tap once more when it finishes to install.
- **Reikai now checks for app and extension updates every time you open it from cold (synced from Mihon, mihonapp/mihon#3658).** It used to wait days between checks, so a fresh build could sit unoffered.
- **Every help link in the app now opens Reikai's own documentation at reikai.app.** They pointed at Mihon's site, which does not cover what Reikai adds.
- **Help links in a Nightly build now open the Nightly docs, which describe what that build runs.** Stable builds still open the stable docs.

#### Fixed

- **The update check no longer crashes or goes quiet on a version with more parts than the release tag.** It also stops offering an older release whose last number happens to be higher, and stops missing one that adds a part.
- **A date older than about a month now says how old it really is (synced from Mihon, mihonapp/mihon#3696).** Something read 40 days ago was described as 10 days old, wherever a date is shown relatively.
- **Statistics now counts a merged series once instead of once per source.** The title, completed, started and tracked figures all read higher than the library they describe.
- **Statistics now counts your downloaded novel chapters.** The Downloaded figure only ever counted manga.
- **With Hide adult content in notifications on, update notifications no longer come out blank for series from a source that carries extra metadata.** The setting was treating those as adult.
- **Hide adult content in notifications now keeps adult titles out of every notification that names a series.** That covers updates, downloads, restores and read aloud for manga and novels alike, and a novel from an extension app labelled 18+ counts as adult.
- **Hide notification content now also covers read aloud and its lock screen, novel downloads and updates, and the follows sync.** Manga's own update and download notifications already followed it.
- **A long series title no longer pushes the chapter numbers out of its update notification.**
- **The Reikai icon on a notification is now the same size as every other notification icon.** It was drawing about a fifth smaller than its neighbours in the shade.
- **A crash can no longer run your data migrations, library recovery or a backup restore a second time.** The crash screen runs in its own process, which was repeating the app's whole startup.

### Other

- Backing up and restoring the library now runs one loop for manga and novels: which series are backed up, which parts each carries, and how a failed restore batch is retried are each written once, so the two can no longer drift.
- A novel library update now predicts a paged novel's next update once, after its last page, instead of once for every page it fetched.
- Keeping merged series in step with the library now costs one pass over the grouped entries on each library write, rather than a walk of the whole library.
- Smart update skipping, the chapter-sync rules for new chapters and what binding a tracker backfills are now each written once for manga and novels, so the two can no longer drift. The novel library update also decides what to skip from the library's chapter counts, rather than loading every novel's chapters first.
- The app's native libraries are now compressed inside the download, which keeps it near its old size although the high quality renderer added about 27 MB of them: the arm64 download is about 31 MB where it would have been 53 MB. Once installed the app takes a little more space, since Android unpacks them.
- A category link that no picker for its content type could show is now refused where it is written, so a manga can no longer be filed under a novels-only category or the reverse.
- Database upgrades are now checked in every nightly and pull request build against a saved copy of an older schema, so a schema change shipped without the step that upgrades existing installs fails the build instead of reaching a device. The check was wired up before but had nothing to compare against.
- Gradle's configuration cache is on, which takes a no-op incremental build from about 26 seconds to under 2. Isolated projects is on beside it, and each module's resource class now carries only that module's own resources (partly synced from Mihon).
- The app now wires its components together at build time rather than looking them up while running, closing a class of release-only crash, and installed extensions resolve their dependencies from that same fixed list (synced from Mihon, mihonapp/mihon#3608 and mihonapp/mihon#3965).
- Updates, History and Recents no longer start the novel downloader while they show only manga, and a manga download's progress now redraws only the progress rings rather than every download icon and the selection bar.
- The novel reader does less work on the main thread: a text colour, line spacing or alignment change keeps each chapter's measured layout, a replaced chapter render stops early, the web page mode reads a font file once rather than on every page build, and the page is no longer re-indented as a whole.
- The search bar and the tracker sign-in fields now use Compose's newer text-field implementation, and the reader's progress sliders its newer slider state, following a Compose bump synced from Mihon (mihonapp/mihon#3752).
- Cancelling an extension install no longer goes through a local broadcast, which the Material library dropped at 1.14.0 (synced from Mihon, mihonapp/mihon#3226).
- The novel reader's session model now runs under unit tests against a real in-memory database, which pins how it handles a failed chapter load, a retried reload from the source, the next-chapter threshold setting and a merged novel's source labels.
- Dependency and toolchain updates synced from Mihon: the app compiles against Android SDK 37.2, and appcompat, paging, webkit, okhttp, okio, conscrypt, Firebase, Injekt, SQLDelight and its driver, the image, markdown and rich-text renderers and the build plugins all moved up.
- Added an on-device test that measures how a scrolling list holds its position when content is inserted above the reader, which settled a design question for the novel reader. Test only, nothing in the app changed.
- The bottom navigation and the tablet side rail are now drawn by Material's own adaptive navigation component instead of hand-rolled copies (synced from Mihon, mihonapp/mihon#3834).
- The in-app browser, the Cloudflare bypass and the tracker sign-in browser now present one browser identity, so a site checking the user agent against its client hints sees them agree and a clearance earned while signing in stays valid (synced from Mihon, mihonapp/mihon#3678).
- Category renames, reorders and flag changes each write through their own query instead of one update that touched every column (synced from Mihon, mihonapp/mihon#3693).
- Looking up a source now waits for the extension scan instead of reading a half-built list, so a screen opened during startup gets a slow answer rather than a wrong one (synced from Mihon, mihonapp/mihon#3869). Novel sources changed the same way.
- Translated strings refreshed across most locales (synced from Mihon, mihonapp/mihon#3563, mihonapp/mihon#3677, mihonapp/mihon#3701, mihonapp/mihon#3938, mihonapp/mihon#3950 and mihonapp/mihon#3987).
- A shared crash log now carries verbose lines when verbose logging is on, instead of always filtering to errors (synced from Mihon, mihonapp/mihon#3682).
- Extensions are now class-loaded through the platform's own delegate-last loader rather than a hand-rolled one (synced from Mihon, mihonapp/mihon#3874).
- Dates and times are now handled by the Kotlin standard library and kotlinx-datetime rather than java.time, matching Mihon (synced from Mihon, mihonapp/mihon#3001).
- The library, details, browse, migrate, recents, downloads and reader surfaces now run on one shared implementation across manga and novels, covering list assembly, filtering, sorting, selection, the dialogs and the queue, so a change reaches both instead of being written twice.
- Manga and novel categories now live in one shared table with a content-type column read through one repository, a library section is a distinct type rather than a category with a negative id, and custom novel covers are stored under a name carrying the content type, moved once on upgrade.
- The details screens lost code nothing reached: a search mode the header never used, a per-type switch both types set the same way, a merge-chip field nobody read and a library search fallback no caller took.
- Every screen now holds its state in an AndroidX ViewModel instead of Voyager's ScreenModel, in its own field rather than through a shared base class, matching Mihon so future upstream changes to a screen apply cleanly (synced from Mihon, mihonapp/mihon#3594 and mihonapp/mihon#3763).
- Shizuku detection now probes for the Shizuku permission instead of a fixed package name (synced from Mihon, mihonapp/mihon#3565).
- Shikimori progress updates now go through the service's own update endpoint instead of re-posting the list entry (synced from Mihon, mihonapp/mihon#3810).
- Kitsu tracking now runs entirely on Kitsu's GraphQL API rather than the older one it is replacing (partly synced from Mihon, mihonapp/mihon#3792). The recommendation taste profile and Fill from tracker moved across too, so nothing is left on the old endpoint.
- Tracker internals tidied across all eleven upstream trackers, covering token expiry math, refresh-response parsing, helper visibility and how each tracker learns its own id (synced from Mihon, mihonapp/mihon#3900 and mihonapp/mihon#3908). No change to sign-in, search or progress sync.
- Twenty-one settings descriptions rewritten shorter and plainer, to read like the ones Mihon ships rather than a paragraph under each switch.
- The migration source list now saves its order off the UI thread, once per change instead of possibly twice.
- Every list screen now stops querying a few seconds after you leave it, instead of running as long as the app does: the library, Recents, the source and extension lists, and the category, migration, cover and upcoming screens (synced from Mihon, mihonapp/mihon#3716 through mihonapp/mihon#3762).
- Installed extensions are now read off the main thread, so they no longer hold up a cold start (synced from Mihon, mihonapp/mihon#3788).
- Extension trust is re-checked from the repo list itself rather than by the two screens that happened to change it, so adding or removing a repo anywhere re-checks straight away, and a re-check can no longer be undone by the startup scan finishing after it.
- The manga reader now queues its internal events instead of discarding one when it arrives while the reader is busy. Two of them were sent in a way that could be dropped silently, which would have cost a page-turn signal or left a preloaded chapter unshown.
- The one-time import of a Yokai-era database on first launch is removed. It could not run since the 0.3.2 package change, which installs the app beside a Yokai-era build rather than over it.

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
