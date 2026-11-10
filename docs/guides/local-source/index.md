---
title: Local source
titleTemplate: Sources
description: For users who would like to download and organize their own media.
---

# Local source

If you like to download and organize your media, then you want to know how to manage your own series in Reikai.

::: warning
This page explores some advanced features.
:::

There are two local sources: one for manga, which reads folders of pictures and comic archives, and one for light novels, which reads EPUB books. Both appear in <nav to="sources"> as **Local source**.

## Importing files

The quickest way in is to let Reikai copy the files for you:

* Pick **Import files** in the ⋮ menu of the <nav to="main_library"> or of a **Local source** list.
* Or open an EPUB or comic archive from another app with **Open with** or **Share**, and pick Reikai. With the import screen already open, the file joins the ones you picked.

Reikai reads each file and shows it as the series it will become, with its cover and author. Books that belong to one series (by their series details, or a shared name for comics) are grouped together as its volumes. Tap the pencil to rename a series, or the cross to leave it out. An EPUB can go in as a novel or, for an image-only comic in EPUB form, as manga. With **Add to library** on, the series are added to your library, in your default category or the one you pick.

<img
  class="only-light"
  src="/docs/guides/local-source/import.light.webp"
  alt="The import screen listing four books, each with its cover, author and a Novels or Manga choice"
  width="286"
  height="600"
  loading="lazy"
  decoding="async"
/>
<img
  class="only-dark"
  src="/docs/guides/local-source/import.dark.webp"
  alt="The import screen listing four books, each with its cover, author and a Novels or Manga choice"
  width="286"
  height="600"
  loading="lazy"
  decoding="async"
/>

The files are copied into the folders described below, so you can delete the originals afterwards. When a file is already there, you choose to replace it, keep both copies, or skip it. A book that belongs to a series you already have is added to it as a new volume; tap **Keep separate** to import it as a series of its own, or **Add to series** to pick another series for it.

Files protected with DRM cannot be read. Remove the protection with the tool you bought the book from, or buy it DRM-free.

## Light novels

Local novels live in a `localnovels` folder in your storage location, beside the `local` one. Each folder inside it is a series, and each EPUB in that folder is a volume. Volumes are read in the order of their series number when every book has one, and in name order otherwise. An EPUB placed straight in `localnovels` is a series of its own.

* Chapters come from the book's table of contents. A file that holds several chapters is divided at them, and a part title with no text of its own joins the chapter after it.
* A book with no table of contents gets a chapter per file, or, for a book that is one long file, a chapter per heading.
* Pictures, and the book's own layout such as indents and centred text, carry over. Its text colours give way to your reader theme.
* To change a series' details, put a `details.json` in its folder (the format is in [Advanced editing](/docs/guides/local-source/advanced)). A `cover.jpg` or `cover.png` there replaces the book's own cover.

Chapters you add later appear on the next library update, or when you pull to refresh the series.

## Saving a novel as an EPUB

Any novel, local or not, can be saved as an EPUB file to read in another app or keep as a copy. Open the novel, then **Export as EPUB** in the ⋮ menu. The dialog says how many chapters are on the device: downloaded ones, or every chapter of a local novel. **Download the rest** queues the others first. You can leave out pictures and export only a run of chapters.

To save several at once, select them in the <nav to="main_library"> and pick **Export as EPUB** from the ⋮ menu at the bottom. Reikai asks for a folder and writes one file per novel.

## Creating local manga

1. In the location you specified as your storage location (e.g., `/Reikai/`), create a `local` folder if one is not there yet. Place correctly structured series inside that (e.g. `/Reikai/local/`).

    > If adding series in folders it is recommended to add a file named `.nomedia` to the local folder so images do not show up in the gallery.

1. You should now be able to access the series in <nav to="sources"> under **Local source**. If the list is filtered to novels by the chip row at the top, switch it back to manga or all.

If you add more chapters then you'll have to manually refresh the chapter list (by pulling down the list).

Supported chapter formats are folders with pictures inside (such as `.jpg`, `.png`, etc), archive files (`ZIP`/`CBZ`, `RAR`/`CBR`, `7Z`/`CB7` and `TAR`/`CBT`), or an `EPUB`.
But expect better performance with directories and `ZIP`/`CBZ`.

Note that a single folder or archive file is treated as a single chapter, and so is an EPUB in the `local` folder. Only the light novel source, reading from `localnovels`, divides an EPUB into chapters.

### Folder structure

Reikai requires a specific folder structure for local series to be correctly processed.
Local series will be read from the `local` folder.
Each series must have a `Series` folder and a `Chapter` folder.
Images will then go into the chapter folder.
See below for more information on archive files.
You can refer to the following example:

:::info Example
<div class="tree">
  <ul>
    <img src="/docs/img/folder.svg" alt="Folder" class="tree-icon icon-folder">
    <span class="folder root">[your storage location]/local</span>
    <li>
      <img src="/docs/img/folder.svg" alt="Folder" class="tree-icon icon-folder">
      <span class="folder main">[the series title]</span>
      <ul>
        <li>
          <img src="/docs/img/jpeg.svg" alt="File" class="tree-icon icon-jpeg">
          <span class="file jpg">cover<span class="file-extension">.jpg</span></span>
        </li>
        <li>
          <img src="/docs/img/folder.svg" alt="Folder" class="tree-icon icon-folder">
          <span class="folder">chapter_1</span>
          <ul>
            <li><span class="file">image_1<span class="file-extension">.ext</span></span></li>
            <li><span class="file">image_n<span class="file-extension">.ext</span></span></li>
          </ul>
        </li>
        <li>
          <img src="/docs/img/folder.svg" alt="Folder" class="tree-icon icon-folder">
          <span class="folder">chapter_2</span>
          <ul>
            <li><span class="file">image_1<span class="file-extension">.ext</span></span></li>
            <li><span class="file">image_n<span class="file-extension">.ext</span></span></li>
          </ul>
        </li>
        <li>
          <img src="/docs/img/folder.svg" alt="Folder" class="tree-icon icon-folder">
          <span class="folder">chapter_n</span>
          <ul>
            <li><span class="file">image_1<span class="file-extension">.ext</span></span></li>
            <li><span class="file">image_n<span class="file-extension">.ext</span></span></li>
          </ul>
        </li>
      </ul>
    </li>
  </ul>
</div>
:::

Reikai will see three chapters in a single series.
The path to the folder with images must contain both the series title and the chapter name (as seen above).

Note that this is the same folder structure used by downloads of non-local sources.
However, the filename format is not required to be the same; for example, Reikai can add a disambiguating hash based on the chapter URL when downloading chapters (the **Enable chapter name hash suffix** setting in <nav to="advanced">), but you do not need to do this.

### Archive files

Archive files such as `ZIP`/`CBZ` are supported but the folder structure inside is not.
Any folders inside the archive file are ignored.
You must place the archive inside the `Series` folder where the name will become the `Chapter` title.
All images inside the archive regardless of folder structure will become pages for that chapter.

#### Example {#example-archives}

:::tabs
== .ZIP
<div class="tree">
  <ul>
    <img src="/docs/img/folder.svg" alt="Folder" class="tree-icon icon-folder">
    <span class="folder root">[your storage location]/local</span>
    <li>
      <img src="/docs/img/folder.svg" alt="Folder" class="tree-icon icon-folder">
      <span class="folder main">[the series title]</span>
      <ul>
        <li>
          <img src="/docs/img/jpeg.svg" alt="File" class="tree-icon icon-jpeg">
          <span class="file jpg">cover<span class="file-extension">.jpg</span></span>
        </li>
        <li>
          <img src="/docs/img/zip.svg" alt="Compressed File" class="tree-icon icon-zip">
          <span class="file zip">chapter_1<span class="file-extension">.zip</span></span>
          <ul>
            <li>
              <img src="/docs/img/jpeg.svg" alt="File" class="tree-icon icon-jpeg">
              <span class="file jpg">image_1<span class="file-extension">.jpg</span></span>
            </li>
            <li>
              <img src="/docs/img/jpeg.svg" alt="File" class="tree-icon icon-jpeg">
              <span class="file jpg">image_n<span class="file-extension">.jpg</span></span>
            </li>
          </ul>
        </li>
        <li>
          <img src="/docs/img/zip.svg" alt="Compressed File" class="tree-icon icon-zip">
          <span class="file zip">chapter_2<span class="file-extension">.zip</span></span>
          <ul>
            <li>
              <img src="/docs/img/jpeg.svg" alt="File" class="tree-icon icon-jpeg">
              <span class="file jpg">image_1<span class="file-extension">.jpg</span></span>
            </li>
            <li>
              <img src="/docs/img/jpeg.svg" alt="File" class="tree-icon icon-jpeg">
              <span class="file jpg">image_n<span class="file-extension">.jpg</span></span>
            </li>
          </ul>
        </li>
        <li>
          <img src="/docs/img/zip.svg" alt="Compressed File" class="tree-icon icon-zip">
          <span class="file zip">chapter_n<span class="file-extension">.zip</span></span>
          <ul>
            <li>
              <img src="/docs/img/jpeg.svg" alt="File" class="tree-icon icon-jpeg">
              <span class="file jpg">image_1<span class="file-extension">.jpg</span></span>
            </li>
            <li>
              <img src="/docs/img/jpeg.svg" alt="File" class="tree-icon icon-jpeg">
              <span class="file jpg">image_n<span class="file-extension">.jpg</span></span>
            </li>
          </ul>
        </li>
      </ul>
    </li>
  </ul>
</div>
:::

### Volume folders

A series split into volumes can keep each volume in a folder of its own inside the `Series` folder, such as `Vol 01` and `Vol 02`, with the volume's chapter archives or chapter folders inside it. Reikai reads one level of volume folders and names each chapter with its volume in front, like `Vol 02 - Ch 011`. A folder that holds only pictures is still read as one chapter.

A picture in a volume folder named `cover` or `folder`, or the only picture there, is that volume's cover on its header. Any other pictures beside the chapters read as an Extras chapter at the start of the volume.

## Managing volumes

Everything here works the same for local manga and local novels. A manga volume is a volume folder, and a novel volume is one EPUB book. Files only move between series of the same type.

### Volume chips

A local series with two or more volumes lists its chapters under a header per volume, with a row of chips above them. **All volumes** shows every volume; a volume's chip shows only that one. Reikai remembers the chip you left each series on and opens it there next time.

A header shows the volume's cover and how many of its chapters you have read. Tap it to select the volume's chapters, or use its button to mark them all read or unread.

### Combining series into one

Two ways to turn separate local series into one series with volumes:

* **From the library.** Select two or more local series of one type and tap **Combine as volumes** in the bar at the bottom, where **Merge** usually sits. Under **Keep as the series**, pick the one that stays, then tap **Combine**.
* **From the series that receives them.** Open it and pick **Add volumes** in the <nav to="overflow"> menu. Tick the other local series in your library that should move in, then tap **Add**.

The other series move in as volumes and leave the library, and their read progress comes with them. A [merged series](/docs/multi-source) made only of local series offers **Combine as volumes** in **Manage sources** too: the merge is undone and the series you opened takes the others in.

### Splitting volumes out

**Split out volumes** in the <nav to="overflow"> menu does the reverse. Tick the volumes to take out and check the **Series name**, which starts as the series title plus the first ticked volume's label. Tap **Split out**, and those volumes become one new series in the library, with their read progress.

### Reordering and renaming volumes

**Reorder volumes** in the <nav to="overflow"> menu lists the volumes. Drag one by its handle to change the order; chapters sorted by source are read in the order you set. Tap the pencil to rename a volume's label, or **Reset** to go back to the name its file gives. These names and the order stay in the app; your files keep their names unless you also rename the files.

### Renaming files

**Rename files** in the <nav to="overflow"> menu gives the files on the device the names shown in the app. The series folder takes the series title. A manga volume folder takes its volume label, and a novel book takes the series title and its volume label. Every old name is listed with its new one before anything changes, and **Rename** goes ahead. Read progress stays.

If another series already uses the folder name, Reikai picks a free one, like `Title (2)`, and says so. **Choose another name** lets you type your own, which then becomes the series title as well.

**Edit info** and **Reorder volumes** offer **Also rename files** once you change the title or a volume's name. Leave it ticked to go straight to **Rename files** when you close them.

### Deleting volumes

**Delete volumes** in the <nav to="overflow"> menu lists each volume with its file name and read count. Tick the ones to remove and tap **Delete**. Reikai asks once more, because the files are deleted from the device and this cannot be undone.

When two volumes show the same label, for example after combining two series that both had a Vol. 1, a notice above the chips says so. **Keep all** keeps every copy and names the later ones `Vol. 1 (2)` and up. **Delete some** opens **Delete volumes** with just those volumes, so you can pick the copies you do not want.

### Undoing a move

Combining, adding volumes, splitting out and renaming files each show a message with **Undo**. Undo puts the files, chapters and library entries back where they were. Deleting volumes has no undo.

### Opening the folder

**Open folder** in the <nav to="overflow"> menu opens the series' folder in a file manager app. For a novel that is a single EPUB with no folder of its own, it opens the folder the book sits in.
