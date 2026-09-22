/*
 * Reikai's page-side engine for the WebView rendering mode, replacing the vendored core.js.
 *
 * Reports through the ReikaiWeb bridge (see NovelWebBridge.kt) and exposes window.rkReader for the
 * host to call into. The chapter-boundary and per-chapter progress half is ported from tsundoku's
 * scroll-tracking.js; the tap, swipe, auto-scroll and bionic halves replace what core.js did.
 *
 * NovelWebDocument fills in the build tokens below, each named in double underscores. Never name one
 * in a comment: every occurrence is replaced, and a replacement can close the comment it lands in.
 */
(function () {
  // The token is in this script's own text. Removed while the engine still runs ahead of the chapter,
  // so no script the chapter carries can read it out of the page.
  var self = document.currentScript;
  if (self && self.parentNode) self.parentNode.removeChild(self);

  // Names this document to the host, which hears only the document it built last, so every bridge call
  // passes it first. The page being replaced, a chapter's script, or a frame one creates (the bridge is
  // in every frame) can reach the bridge too, and none of them has this.
  var DOCUMENT_TOKEN = '__DOCUMENT_TOKEN__';

  var CHAPTER_SELECTOR = '.rk-chapter';
  var CHAPTER_ID_ATTR = 'data-rk-chapter-id';
  // Text in a chapter that is not the chapter's words: ruby readings, code, and a failed picture's box.
  // Read aloud and the top line both skip it, so a line is counted as the native renderer counts it.
  var UNCOUNTED_SELECTOR = 'rt, rp, script, style, .rk-failure';
  // core.js's swipe distance, in CSS pixels. The document is initial-scale=1, so this is the same
  // unit the native renderer's SWIPE_MIN_DP resolves to and the gesture matches in all three.
  var SWIPE_MIN_PX = 180;
  // The live report's interval: fine enough for the rail, far coarser than a scroll frame.
  var REPORT_INTERVAL_MS = 50;
  // A late reflow (images, fonts) fires scrollend against a still-settling height, so a persist
  // waits this out rather than saving a position the layout is about to move.
  var SETTLE_MS = 400;
  // Sub-pixel slack when deciding which chapter a scroll position is in. See state().
  var EDGE_TOLERANCE = 2;
  // How long a restore waits for the opening chapter's images before seeking anyway, the native renderer's
  // wait too (CHAPTER_IMAGE_WAIT_MS). See start().
  var IMAGE_WAIT_MS = __IMAGE_WAIT_MS__;
  // The keys a browser scrolls a page with, so pressing one is the reader moving it.
  var SCROLL_KEYS = ['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '];
  // How far the read-aloud outline stands off its paragraph's text, in CSS pixels.
  var OUTLINE_PAD_PX = 4;

  // Resolved by the host, since the page has no resources of its own.
  var labels = {
    finished: '__LABEL_FINISHED__',
    next: '__LABEL_NEXT__',
    noNext: '__LABEL_NO_NEXT__',
    downloaded: '__LABEL_DOWNLOADED__',
    imageError: '__LABEL_IMAGE_ERROR__',
    retry: '__LABEL_RETRY__',
  };

  // The user's stylesheet, set as text so nothing in it is parsed as markup. It follows the reader's own
  // styles in the head, so it wins a tie with them.
  function setSnippetCss(css) {
    var style = document.getElementById('rk-snippets');
    if (style) style.textContent = css;
  }
  setSnippetCss(__CSS_SNIPPETS__);

  var settings = {
    swipe: __SWIPE__,
    bionic: __BIONIC__,
    // { highlight, style, color, textColor, keepInView, scrollToTop }, from NovelWebDocument.readAloudJson.
    readAloud: __READ_ALOUD__,
  };

  var boundaries = [];
  var lastChapterSeen = null;
  var lastReported = -1;
  var lastReportedId = null;
  var lastReportedAt = 0;
  var heldReport = null;
  var lastResizeAt = 0;
  var framePending = false;
  // A picture settled since the last frame, whose layout the cached boundaries have not measured.
  var imageSettled = false;
  // The host drops everything this page says before its ready report, and a fit or an end is said only
  // once, so neither is sent before then.
  var ready = false;
  // Whether the reader has moved the page, and whether the opening seek is still waiting on images.
  var readerMoved = false;
  var seekWaiting = false;

  // Held from before the chapter's scripts run, so one of them replacing the global cannot stand in
  // for the bridge and read what the engine sends.
  var nativeBridge = window.ReikaiWeb;

  function bridge() {
    return nativeBridge;
  }

  // region geometry

  function viewportHeight() {
    return window.innerHeight || document.documentElement.clientHeight;
  }

  function scrollTop() {
    return window.scrollY || document.documentElement.scrollTop || 0;
  }

  /* The status-bar inset the chapter's own padding clears, which the screen's top is taken to be. */
  function insetTop() {
    return parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--rk-inset-top')) || 0;
  }

  /*
   * Rebuilds where each chapter starts and how tall it is, in document coordinates.
   * getBoundingClientRect plus scrollY rather than offsetTop, so a positioned container cannot
   * offset every reading. Cheap enough to redo on any DOM or size change, coalesced to one a frame.
   */
  function rebuildBoundaries() {
    // First, so the ends and fits below are measured where the reader is rather than where the change
    // left the page for a moment.
    place.sync();
    var chapters = document.querySelectorAll(CHAPTER_SELECTOR);
    var top = scrollTop();
    var next = [];
    for (var i = 0; i < chapters.length; i++) {
      var rect = chapters[i].getBoundingClientRect();
      var start = rect.top + top;
      // A chapter is read from the top of the seam that introduces it, while its progress still
      // measures the text alone. The native renderer draws the same two lines, by putting the seam
      // inside the chapter's own item: without this, a reader stopped anywhere in a seam is reported
      // in the chapter above while the screen below the seam is entirely the chapter it names.
      var above = chapters[i].previousElementSibling;
      var claimFrom = above && above.classList.contains('rk-seam')
        ? above.getBoundingClientRect().top + top
        : start;
      next.push({
        id: chapters[i].getAttribute(CHAPTER_ID_ATTR),
        el: chapters[i],
        start: start,
        claimFrom: claimFrom,
        height: rect.height,
      });
    }
    boundaries = next;
    reportFits();
    reportEnds();
    readAloud.reposition();
  }

  /* The last answer sent per chapter, so a rebuild that changes nothing says nothing. */
  var fitsReported = {};

  /*
   * Whether each chapter fits on one screen, the test state() makes when it holds one at 0. The model
   * reads such a chapter when the reader steps forward from it. Held while any of its images is still
   * loading, as reportEnds is, since until they land a long illustrated chapter measures short.
   */
  function reportFits() {
    if (!ready) return;
    var viewport = viewportHeight();
    boundaries.forEach(function (b) {
      var fits = b.height <= viewport;
      if (fitsReported[b.id] === fits || !imagesLanded(b.el)) return;
      fitsReported[b.id] = fits;
      bridge().onChapterFits(DOCUMENT_TOKEN, b.id, fits);
    });
  }

  /* Chapters whose last line has been on screen, each told once. */
  var endsSeen = {};

  /*
   * Tells the host a chapter's last line reached the screen, which reads the novel's last chapter:
   * nothing follows it to be left into. Held while any of its images is still loading, since until
   * they land it measures short and would be read the moment it opened.
   */
  function reportEnds() {
    if (!ready) return;
    var bottom = scrollTop() + viewportHeight() + EDGE_TOLERANCE;
    boundaries.forEach(function (b) {
      if (endsSeen[b.id] || b.start + b.height > bottom || !imagesLanded(b.el)) return;
      endsSeen[b.id] = true;
      bridge().onChapterEndSeen(DOCUMENT_TOKEN, b.id);
    });
  }

  function imagesLanded(el) {
    var images = el.querySelectorAll('img');
    for (var i = 0; i < images.length; i++) {
      if (!images[i].complete) return false;
    }
    return true;
  }

  /*
   * Runs once el's images have landed, or once IMAGE_WAIT_MS has passed, plus a frame so the layout
   * they changed has been through it. The cap is there because a request that never answers must not
   * strand the reader at the top of the chapter.
   */
  function whenImagesLanded(el, run) {
    if (imagesLanded(el)) {
      run();
      return;
    }
    var done = false;
    function finish() {
      if (done) return;
      done = true;
      clearTimeout(timer);
      document.removeEventListener('load', check, true);
      document.removeEventListener('error', check, true);
      run();
    }
    function check(e) {
      if (e.target.tagName === 'IMG' && imagesLanded(el)) requestAnimationFrame(finish);
    }
    var timer = setTimeout(finish, IMAGE_WAIT_MS);
    document.addEventListener('load', check, true);
    document.addEventListener('error', check, true);
  }

  /* Which chapter the reader is in and how far through it, rather than through the document. */
  function state() {
    var top = scrollTop();
    var viewport = viewportHeight();

    if (boundaries.length === 0) {
      return { id: null, progress: 0 };
    }

    /*
     * A boundary position is both the end of one chapter and the start of the next, so the tie
     * breaks toward the later one: arriving counts. The tolerance matters because a seek to a
     * chapter's own start can land fractionally short, which would report the reader as still in
     * the chapter they just left and retarget the next seek of the same drag to that chapter.
     */
    var index = 0;
    for (var i = 0; i < boundaries.length; i++) {
      if (top >= boundaries[i].claimFrom - EDGE_TOLERANCE) index = i; else break;
    }
    var chapter = boundaries[index];
    var progress = 0;
    // A chapter that fits on one screen stays at 0, as in the native renderer: it has no scroll room
    // and is read when the reader leaves it forward (NovelLeaveRule), never on sight.
    if (chapter.height > viewport) {
      var within = Math.max(top - chapter.start, 0);
      // Every chapter ends when its last line reaches the bottom of the screen, as in the native
      // renderer's ChapterScrollProgress. Tsundoku measures a middle chapter against its full height
      // instead, which gives one stored percent two positions: saved with the next chapter below,
      // restored with the chapter opened alone, it landed a fraction of a screen early.
      progress = Math.min(within / (chapter.height - viewport), 1);
    }
    return { id: chapter.id, progress: progress };
  }

  // endregion

  // region holding the reader's place

  /*
   * The line at the top of the screen, put back at the same height whenever the layout moves it, as the
   * native renderer's holdingReader does. Chromium's own scroll anchoring is off (reader.css): a new text
   * size changes every paragraph's em spacing, which suspends it, where it acts it holds a paragraph
   * rather than the line inside it, and at offset zero it does nothing. Left on, it would correct twice,
   * since its scroll reads here as the reader's own. Every scroll is theirs or made for them (a seek, a
   * glide, a follow), so it moves the held line with the page, and the line is taken afresh after it.
   */
  var place = (function () {
    // { node, offset, y, scrollY }: a character or element, its top on screen, and the offset it was read at.
    var held = null;

    /* The first character of the line at the top of the screen, or the element there when it holds none. */
    function pick() {
      var caret = document.caretRangeFromPoint(0, Math.min(insetTop() + 1, viewportHeight() - 1));
      if (!caret) return null;
      var node = caret.startContainer;
      var offset = caret.startOffset;
      if (node.nodeType !== Node.TEXT_NODE) {
        node = node.childNodes[offset] || node;
        offset = 0;
      }
      if (node === document.body || node === document.documentElement) return null;
      var top = topOf(node, offset);
      return top === null ? null : { node: node, offset: offset, y: top, scrollY: scrollTop() };
    }

    function topOf(node, offset) {
      if (!node.isConnected) return null;
      if (node.nodeType === Node.TEXT_NODE) {
        if (node.length > 0) {
          var range = document.createRange();
          var at = Math.min(offset, node.length - 1);
          range.setStart(node, at);
          range.setEnd(node, at + 1);
          var rect = range.getClientRects()[0];
          if (rect) return rect.top;
        }
        node = node.parentElement;
      }
      return node ? node.getBoundingClientRect().top : null;
    }

    /* Scrolls by whatever moved the held line other than the page's own scrolling since it was read, and
       says whether it did. */
    function restore() {
      if (!held) return false;
      var top = topOf(held.node, held.offset);
      if (top === null) {
        held = null;
        return false;
      }
      var scrolled = scrollTop() - held.scrollY;
      // Height leaving can end the document above where the screen did, and the browser pulls the page
      // back to its bottom. That is not the reader, who cannot scroll up onto the bottom edge; counted
      // as theirs, it moved them up a second time.
      var maxTop = document.documentElement.scrollHeight - viewportHeight();
      if (scrolled < 0 && scrollTop() >= maxTop - 1) scrolled = 0;
      var shift = top - (held.y - scrolled);
      if (Math.abs(shift) <= 0.5) return false;
      window.scrollBy({ top: shift, behavior: 'instant' });
      return true;
    }

    function textWalker(chapter) {
      return document.createTreeWalker(chapter, NodeFilter.SHOW_TEXT);
    }

    /* How many characters of chapter's text come before node, and back: the one way to name a place in
       text whose nodes are about to be replaced. */
    function offsetOf(chapter, node) {
      var walker = textWalker(chapter);
      var count = 0;
      while (walker.nextNode() && walker.currentNode !== node) count += walker.currentNode.length;
      return count;
    }

    function nodeAt(chapter, count) {
      var walker = textWalker(chapter);
      while (walker.nextNode()) {
        if (count < walker.currentNode.length) return { node: walker.currentNode, offset: count };
        count -= walker.currentNode.length;
      }
      return null;
    }

    return {
      /* Puts the held line back, then holds the line now at the top, saying whether the page moved. Safe
         at any point: nothing moved reads as a shift of zero. */
      sync: function () {
        var moved = restore();
        held = pick();
        return moved;
      },
      /* Around a change that replaces the text nodes a held line is found in: bionic emphasis. */
      across: function (change) {
        this.sync();
        var inText = held && held.node.nodeType === Node.TEXT_NODE;
        var chapter = inText ? held.node.parentElement.closest(CHAPTER_SELECTOR) : null;
        var at = chapter ? offsetOf(chapter, held.node) + held.offset : -1;
        change();
        var found = at >= 0 && !held.node.isConnected ? nodeAt(chapter, at) : null;
        if (found) {
          held.node = found.node;
          held.offset = found.offset;
        }
        this.sync();
      },
    };
  })();

  /*
   * The line at the top of the screen, as the characters of its chapter before it that read-aloud keeps,
   * less spaces: the native renderer's shownCharCount, so a rebuilt page lands on the line the reader had
   * whichever renderer rebuilds it. Sent when it changes, -1 while no chapter text is at the top.
   */
  var topLine = (function () {
    var UNCOUNTED = /[\s\uFFFC]/g;
    // Per chapter id, a Map of each counted text node to the characters before it, dropped on any DOM change.
    var cache = {};
    var sent = null;
    // A line seek the document ended too soon below to reach: { chapter, line, at }, at being the line it
    // stopped on. A chapter joining below lands it again, unless the reader has moved off that line.
    var shortSeek = null;

    function count(text) {
      return text.replace(UNCOUNTED, '').length;
    }

    function countsOf(chapter) {
      var id = chapter.getAttribute(CHAPTER_ID_ATTR);
      if (!cache[id]) {
        var before = new Map();
        var total = 0;
        var walker = document.createTreeWalker(chapter, NodeFilter.SHOW_TEXT, {
          acceptNode: function (node) {
            var parent = node.parentElement;
            return parent && parent.closest(UNCOUNTED_SELECTOR) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
          },
        });
        while (walker.nextNode()) {
          before.set(walker.currentNode, total);
          total += count(walker.currentNode.nodeValue);
        }
        cache[id] = before;
      }
      return cache[id];
    }

    function measure(id) {
      // The screen's own top rather than below the inset, as the native renderer measures: the inset reaches a
      // rebuilt page after it lands, and measured below it the next report named the line under the landed one.
      var caret = document.caretRangeFromPoint(0, Math.min(1, viewportHeight() - 1));
      var node = caret && caret.startContainer;
      if (!node || node.nodeType !== Node.TEXT_NODE) return -1;
      var chapter = node.parentElement && node.parentElement.closest(CHAPTER_SELECTOR);
      if (!chapter || chapter.getAttribute(CHAPTER_ID_ATTR) !== id) return -1;
      var before = countsOf(chapter).get(node);
      return before === undefined ? -1 : before + count(node.nodeValue.slice(0, caret.startOffset));
    }

    return {
      report: function (id) {
        if (!ready) return;
        var line = measure(id);
        var key = id + ':' + line;
        if (key === sent) return;
        sent = key;
        bridge().onTopLine(DOCUMENT_TOKEN, id, line);
      },
      /* Scrolls counted character line of chapter to the top, saying whether the chapter holds it. */
      seek: function (chapter, line) {
        shortSeek = null;
        var found = null;
        countsOf(chapter).forEach(function (before, node) {
          if (found || line >= before + count(node.nodeValue)) return;
          var left = line - before;
          for (var i = 0; i < node.nodeValue.length; i++) {
            if (count(node.nodeValue[i]) === 0) continue;
            if (left === 0) {
              found = { node: node, offset: i };
              return;
            }
            left--;
          }
        });
        if (!found) return false;
        var range = document.createRange();
        range.setStart(found.node, found.offset);
        range.setEnd(found.node, found.offset + 1);
        var rect = range.getClientRects()[0];
        if (!rect) return false;
        window.scrollTo({ top: Math.round(scrollTop() + rect.top), behavior: 'instant' });
        place.sync();
        var id = chapter.getAttribute(CHAPTER_ID_ATTR);
        lastChapterSeen = id;
        var landed = range.getClientRects()[0];
        if (landed && landed.top > EDGE_TOLERANCE) shortSeek = { chapter: chapter, line: line, at: measure(id) };
        return true;
      },
      /* A chapter joined below: a line that landed short lands again while the reader is still where it stopped. */
      reland: function () {
        var pending = shortSeek;
        shortSeek = null;
        if (!pending || !pending.chapter.isConnected) return;
        if (measure(pending.chapter.getAttribute(CHAPTER_ID_ATTR)) !== pending.at) return;
        this.seek(pending.chapter, pending.line);
      },
      /* The reader moved, or a seek named its own place, which a line landing again would take them from. */
      forget: function () {
        shortSeek = null;
      },
      invalidate: function () {
        cache = {};
      },
    };
  })();

  // endregion

  // region reporting

  function onFrame() {
    framePending = false;
    // A layout change this frame has not rebuilt the boundaries for yet, and measured against the old
    // ones the corrected offset read as a place far down the chapter.
    if (place.sync() || imageSettled) {
      imageSettled = false;
      rebuildBoundaries();
    }
    var s = state();
    if (s.id === null) return;

    if (s.id !== lastChapterSeen) {
      lastChapterSeen = s.id;
      bridge().onVisibleChapter(DOCUMENT_TOKEN, s.id);
    }

    reportProgress(s);
    topLine.report(s.id);
    // Here as well as on a rebuild, since an image landing without moving the layout reaches only
    // this frame, and it is what releases a chapter either report is holding.
    reportFits();
    reportEnds();
  }

  /*
   * Throttled, because this drives the rail and the percentage overlay on every scroll frame. A report
   * the throttle holds back is sent once the interval has passed, or a scroll stopping inside it left
   * the rail on a place the reader had already left. A chapter finishing, or a different chapter, is
   * never held: the last value sent belongs to the chapter before, so it cannot stand for this one.
   */
  function reportProgress(s) {
    // The host drops a report sent before ready, and one remembered as sent is never sent again.
    if (!ready) return;
    var sameChapter = s.id === lastReportedId;
    var finishing = s.progress >= 1 && lastReported !== 1;
    if (sameChapter && !finishing && Math.abs(s.progress - lastReported) <= 0.005) return;
    var wait = REPORT_INTERVAL_MS - (Date.now() - lastReportedAt);
    if (sameChapter && !finishing && wait > 0) {
      if (!heldReport) heldReport = setTimeout(function () { heldReport = null; onScroll(); }, wait);
      return;
    }
    lastReportedAt = Date.now();
    lastReported = s.progress;
    lastReportedId = s.id;
    bridge().onProgress(DOCUMENT_TOKEN, s.id, s.progress);
  }

  function onScroll() {
    if (framePending) return;
    framePending = true;
    requestAnimationFrame(onFrame);
  }

  /* Re-reads rather than reusing the last frame's value, so a chapter switch mid-scroll cannot
     persist a position against the chapter the reader has already left. */
  function persist(retries) {
    if (retries === undefined) retries = 3;
    if (retries > 0 && Date.now() - lastResizeAt < SETTLE_MS) {
      setTimeout(function () { persist(retries - 1); }, SETTLE_MS);
      return;
    }
    var s = state();
    if (s.id !== null) bridge().onProgressSettled(DOCUMENT_TOKEN, s.id, s.progress);
  }

  // endregion

  // region behaviours core.js used to own

  /*
   * Bolds the opening of each word. The length table and the word rule are text-vide's own, mirrored
   * from NovelBionicSpans so the two renderers emphasise the same letters; the androidTest
   * bionicBoldsTheLettersTheNativeRendererDoes runs both.
   */
  var FIXATION = [0, 4, 12, 17, 24, 29, 35, 42, 48];
  var WORD = /[\p{L}\p{Nd}]*\p{L}[\p{L}\p{Nd}]*/gu;

  function boldLength(wordLength) {
    for (var i = 0; i < FIXATION.length; i++) {
      if (wordLength <= FIXATION[i]) return Math.max(wordLength - i, 0);
    }
    return Math.max(wordLength - FIXATION.length, 0);
  }

  function applyBionic(root) {
    // A chapter's own script and style blocks are text nodes too, and wrapping them in spans empties
    // them: an element child is not script or CSS, so the chapter's styling and code were lost. Text
    // already inside a span this made is skipped, or switching bionic off and on nests the emphasis.
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        var parent = node.parentNode;
        if (!parent || parent.nodeName === 'SCRIPT' || parent.nodeName === 'STYLE') return NodeFilter.FILTER_REJECT;
        return parent.closest('.rk-bionic') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      },
    });
    var texts = [];
    while (walker.nextNode()) {
      if (walker.currentNode.nodeValue.trim()) texts.push(walker.currentNode);
    }
    texts.forEach(function (node) {
      node.parentNode.replaceChild(emphasised(node.nodeValue), node);
    });
  }

  // Built from nodes, never markup: the text is already decoded, so a chapter's escaped `&lt;i&gt;`
  // arrives here as `<i>` and would become a tag.
  function emphasised(text) {
    var span = document.createElement('span');
    span.className = 'rk-bionic';
    var from = 0;
    var match;
    WORD.lastIndex = 0;
    while ((match = WORD.exec(text)) !== null) {
      var n = boldLength(match[0].length);
      if (n === 0) continue;
      if (match.index > from) span.appendChild(document.createTextNode(text.slice(from, match.index)));
      var bold = document.createElement('b');
      bold.textContent = match[0].slice(0, n);
      span.appendChild(bold);
      from = match.index + n;
    }
    if (from < text.length) span.appendChild(document.createTextNode(text.slice(from)));
    return span;
  }

  /*
   * Bionic emphasis is switched by a class on the root, not by undoing applyBionic: that replaces
   * text nodes with spans and cannot be unwound, so switching the setting off used to leave the
   * emphasis in place until the chapter was reopened.
   */
  function syncBionic() {
    document.documentElement.classList.toggle('rk-bionic-on', !!settings.bionic);
  }

  function installGestures() {
    var startX = 0, startY = 0, startAt = 0, moved = false;
    // Only a finger counts. A chapter's own script can build touch events, and the token it cannot
    // read would not stop it: these listeners would send the call for it, stepping the chapter.
    document.addEventListener('touchstart', function (e) {
      if (!e.isTrusted) return;
      // A finger on the page takes the scroll over, as it stops a fling.
      glide.stop();
      if (e.touches.length !== 1) return;
      startX = e.touches[0].clientX;
      startY = e.touches[0].clientY;
      startAt = Date.now();
      moved = false;
    }, { passive: true });

    document.addEventListener('touchmove', function (e) {
      if (!e.isTrusted || e.touches.length !== 1) return;
      if (Math.abs(e.touches[0].clientX - startX) > 10 ||
        Math.abs(e.touches[0].clientY - startY) > 10) moved = true;
      if (moved) onReaderMove();
    }, { passive: true });

    document.addEventListener('wheel', function (e) {
      if (e.isTrusted) onReaderMove();
    }, { passive: true });

    // Sources number footnotes per chapter, so the chapters one page holds repeat ids and the browser's
    // own jump lands on the first, often in the chapter above. The target is looked up in the link's own.
    document.addEventListener('click', function (e) {
      var link = e.target && e.target.closest && e.target.closest('a[href^="#"]');
      var chapter = link && link.closest(CHAPTER_SELECTOR);
      if (!chapter) return;
      e.preventDefault();
      var target = anchorTarget(chapter, link.getAttribute('href').substring(1));
      if (!target) return;
      onReaderMove();
      window.scrollTo({ top: window.scrollY + target.getBoundingClientRect().top, behavior: 'instant' });
      place.sync();
    }, true);

    document.addEventListener('keydown', function (e) {
      if (e.isTrusted && SCROLL_KEYS.indexOf(e.key) >= 0) onReaderMove();
    });

    document.addEventListener('touchend', function (e) {
      if (!e.isTrusted) return;
      var touch = e.changedTouches && e.changedTouches[0];
      if (!touch) return;
      var dx = touch.clientX - startX;
      var dy = touch.clientY - startY;
      var elapsed = Date.now() - startAt;

      // core.js's rule, which the native renderer also implements: mostly sideways, far enough not
      // to be a stray, and started on the half it moves away from, so it crosses the middle rather
      // than flicking in a corner. A swipe that clears the first two but starts on the wrong half
      // falls through to the tap check below, where `moved` discards it.
      if (settings.swipe && Math.abs(dx) > SWIPE_MIN_PX && Math.abs(dx) > Math.abs(dy) * 2) {
        var middle = window.innerWidth / 2;
        if (dx < 0 && startX >= middle) {
          bridge().onStepChapter(DOCUMENT_TOKEN, true);
          return;
        }
        if (dx > 0 && startX <= middle) {
          bridge().onStepChapter(DOCUMENT_TOKEN, false);
          return;
        }
      }
      if (moved || elapsed > 400) return;
      // A tap on a link, or anywhere on the failure box, is that control's, not the reader's. The
      // whole box rather than just its Retry, as the native renderer draws the same line.
      if (e.target && e.target.closest && e.target.closest('a, button, .rk-failure')) return;

      // The host reads the tap zones, the same rule the native renderer asks, and scrolls back through
      // scrollSmoothlyBy when a zone says to.
      bridge().onTap(DOCUMENT_TOKEN, touch.clientX / window.innerWidth, touch.clientY / window.innerHeight);
    }, { passive: true });
  }

  /*
   * A smooth scroll made of relative steps, one a frame. The browser's own heads for a position
   * fixed when it starts, and crossing a boundary adds or drops a chapter above the reader
   * mid-animation, so it finished a whole chapter away. Relative steps ride on the place-holding
   * that keeps the text still, as the native renderer's smoothScrollBy does. Tsundoku avoids the
   * question by jumping without animating.
   */
  var glide = (function () {
    var DURATION_MS = 300;
    var raf = null, start = 0, total = 0, done = 0;
    function frame(now) {
      if (!start) start = now;
      var t = Math.min((now - start) / DURATION_MS, 1);
      var step = Math.round(total * (1 - Math.pow(1 - t, 3)) - done);
      if (step !== 0) {
        window.scrollBy({ top: step, behavior: 'instant' });
        done += step;
      }
      raf = t < 1 ? requestAnimationFrame(frame) : null;
    }
    return {
      running: function () { return raf !== null; },
      // A second press while one is running carries on from where that one had got to.
      by: function (dy) {
        total = total - done + dy;
        done = 0;
        start = 0;
        if (!raf) raf = requestAnimationFrame(frame);
      },
      stop: function () {
        if (raf) cancelAnimationFrame(raf);
        raf = null;
        total = 0;
        done = 0;
      },
    };
  })();

  /*
   * Auto-scroll as a rate rather than a step per frame, because a fixed step ran at double speed on
   * a 120Hz display. The instant behaviour is load-bearing: the stylesheet sets smooth scrolling, so
   * a plain scrollBy starts an animation the next frame interrupts, which crawls and stutters.
   */
  var autoScroll = (function () {
    var raf = null, rate = 0, last = 0;
    function step(now) {
      if (last) window.scrollBy({ top: rate * (now - last) / 1000, behavior: 'instant' });
      last = now;
      raf = requestAnimationFrame(step);
    }
    return {
      start: function (perFrame) {
        rate = perFrame * 60;
        if (!raf) { last = 0; raf = requestAnimationFrame(step); }
      },
      stop: function () {
        if (!raf) return;
        cancelAnimationFrame(raf);
        raf = null;
      },
      running: function () { return raf !== null; },
    };
  })();

  // endregion

  // region read-aloud

  /*
   * The chapter as read-aloud counts it, by the rule the native renderer applies (ReadAloudText.kt): a
   * paragraph is a non-blank line of what the page shows, ruby readings left out. innerText is what the
   * page shows, so it is read with the readings hidden for that one synchronous read, which never
   * paints. Each paragraph also gets a Range over its text nodes, found by matching its non-space
   * characters in order, which the mark is drawn over and the follow measures.
   */
  var readAloud = (function () {
    var OBJECT_REPLACEMENT = String.fromCharCode(0xFFFC);
    var SPACE = /\s/;
    var MARKS = ['rk-tts-background', 'rk-tts-underline'];
    // Per chapter id, dropped whenever the chapters' DOM changes, since a Range over a replaced text
    // node collapses: bionic emphasis, an insert and a chapter's own script all replace some.
    var cache = {};
    var spoken = null;
    var overlay = null;
    // What the reader's chrome covers from each edge, in CSS pixels, from the host's setObscured.
    var obscured = { top: 0, bottom: 0 };

    /* The part of the screen the reader can see text in: below the inset and clear of the chrome. */
    function uncovered() {
      return { top: Math.max(insetTop(), obscured.top), bottom: viewportHeight() - obscured.bottom };
    }

    function chapterElement(id) {
      return document.querySelector(CHAPTER_SELECTOR + '[' + CHAPTER_ID_ATTR + '="' + id + '"]');
    }

    function normalise(line) {
      return line.split(OBJECT_REPLACEMENT).join('').replace(/\s+/g, ' ').trim();
    }

    function build(el) {
      var root = document.documentElement;
      root.classList.add('rk-readings-hidden');
      var shown;
      try {
        shown = el.innerText;
      } finally {
        root.classList.remove('rk-readings-hidden');
      }
      var texts = shown.split('\n').map(normalise).filter(function (line) { return line.length > 0; });
      var chars = [], nodes = [], offsets = [];
      var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, {
        acceptNode: function (node) {
          var parent = node.parentElement;
          return parent && parent.closest(UNCOUNTED_SELECTOR) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
        },
      });
      while (walker.nextNode()) {
        var node = walker.currentNode;
        var value = node.nodeValue;
        for (var i = 0; i < value.length; i++) {
          if (SPACE.test(value[i])) continue;
          chars.push(value[i]);
          nodes.push(node);
          offsets.push(i);
        }
      }
      var flat = chars.join('');
      var cursor = 0;
      function rangeOver(first, last) {
        var range = document.createRange();
        range.setStart(nodes[first], offsets[first]);
        range.setEnd(nodes[last], offsets[last] + 1);
        return range;
      }
      // Searched rather than walked in step, so text the page hides (and innerText leaves out) is skipped.
      var starts = texts.map(function (text) {
        var key = text.replace(/\s/g, '');
        var at = flat.indexOf(key, cursor);
        if (at < 0) return -1;
        cursor = at + key.length;
        return at;
      });
      var ranges = texts.map(function (text, i) {
        return starts[i] < 0 ? null : rangeOver(starts[i], starts[i] + text.replace(/\s/g, '').length - 1);
      });
      /*
       * Part of paragraph i, from and to being offsets in its text. Located by counting the non-space
       * characters before each end, the rule the paragraph itself was found by; null when the part holds none.
       */
      function part(i, from, to) {
        if (starts[i] < 0) return null;
        var text = texts[i];
        var before = text.slice(0, from).replace(/\s/g, '').length;
        var inside = text.slice(from, to).replace(/\s/g, '').length;
        if (inside === 0) return null;
        return rangeOver(starts[i] + before, starts[i] + before + inside - 1);
      }
      return { texts: texts, ranges: ranges, part: part };
    }

    function entry(id) {
      if (!cache[id]) {
        var el = chapterElement(id);
        if (!el) return null;
        cache[id] = build(el);
      }
      return cache[id];
    }

    /*
     * The spoken sentence's range, or the paragraph's when no sentence is named or it cannot be found,
     * dropping the position once its chapter has left the page.
     */
    function spokenRange() {
      if (!spoken) return null;
      var found = entry(spoken.id);
      if (!found) {
        spoken = null;
        return null;
      }
      var sentence = spoken.from >= 0 && found.texts[spoken.index] ? found.part(spoken.index, spoken.from, spoken.to) : null;
      return sentence || found.ranges[spoken.index] || null;
    }

    function clear() {
      if (window.CSS && CSS.highlights) MARKS.forEach(function (name) { CSS.highlights.delete(name); });
      if (overlay) overlay.textContent = '';
    }

    /*
     * The Custom Highlight API draws background and underline in the text's own layout, so they move
     * with it. It cannot draw a box, and an old WebView has no such API, so both of those are boxes
     * laid over the page from the range's rectangles and laid again whenever the layout changes.
     */
    function draw() {
      clear();
      var range = spokenRange();
      var options = settings.readAloud;
      if (!range || !options.highlight) return;
      if (options.style !== 'OUTLINE' && window.CSS && CSS.highlights && typeof Highlight === 'function') {
        markStyle(options);
        CSS.highlights.set('rk-tts-' + options.style.toLowerCase(), new Highlight(range));
        return;
      }
      drawBoxes(range, options);
    }

    // Literal colours rather than custom properties, which a highlight pseudo-element does not reliably
    // inherit from the page.
    function markStyle(options) {
      var el = document.getElementById('rk-tts-style');
      if (!el) {
        el = document.createElement('style');
        el.id = 'rk-tts-style';
        document.head.appendChild(el);
      }
      el.textContent =
        '::highlight(rk-tts-background) { background-color: ' + options.color + '; color: ' + options.textColor + '; }' +
        '::highlight(rk-tts-underline) { text-decoration: underline; }';
    }

    function drawBoxes(range, options) {
      if (!overlay) {
        overlay = document.createElement('div');
        overlay.id = 'rk-tts-overlay';
        document.body.appendChild(overlay);
      }
      var outline = options.style === 'OUTLINE';
      var rects = outline ? [range.getBoundingClientRect()] : range.getClientRects();
      var pad = outline ? OUTLINE_PAD_PX : 0;
      for (var i = 0; i < rects.length; i++) {
        var box = document.createElement('div');
        box.className = 'rk-tts-box rk-tts-' + options.style.toLowerCase();
        box.style.left = (rects[i].left + window.scrollX - pad) + 'px';
        box.style.top = (rects[i].top + window.scrollY - pad) + 'px';
        box.style.width = (rects[i].width + pad * 2) + 'px';
        box.style.height = (rects[i].height + pad * 2) + 'px';
        box.style.borderColor = options.color;
        if (options.style === 'BACKGROUND') box.style.backgroundColor = options.color;
        overlay.appendChild(box);
      }
    }

    /*
     * The native renderer's rule: nothing moves while the paragraph is fully on screen, and otherwise
     * it goes to the top or the middle of the uncovered screen. Through the page's own relative glide,
     * which the place-holding keeps on target while a chapter arrives or leaves above it.
     */
    function follow() {
      var options = settings.readAloud;
      var range = spokenRange();
      if (!range || !options.keepInView) return;
      var rect = range.getBoundingClientRect();
      var visible = uncovered();
      if (rect.top >= visible.top - EDGE_TOLERANCE && rect.bottom <= visible.bottom + EDGE_TOLERANCE) return;
      var available = visible.bottom - visible.top;
      var target = options.scrollToTop || rect.height >= available
        ? visible.top
        : visible.top + (available - rect.height) / 2;
      glide.stop();
      glide.by(Math.round(rect.top - target));
    }

    return {
      paragraphs: function (id) {
        var found = entry(String(id));
        return found ? found.texts : null;
      },
      firstVisible: function () {
        var visible = uncovered();
        var chapters = document.querySelectorAll(CHAPTER_SELECTOR);
        for (var c = 0; c < chapters.length; c++) {
          var bounds = chapters[c].getBoundingClientRect();
          if (bounds.bottom <= visible.top || bounds.top >= visible.bottom) continue;
          var id = chapters[c].getAttribute(CHAPTER_ID_ATTR);
          var ranges = entry(id).ranges;
          for (var i = 0; i < ranges.length; i++) {
            if (!ranges[i]) continue;
            var rect = ranges[i].getBoundingClientRect();
            if (rect.bottom > visible.top && rect.top < visible.bottom) return { id: id, paragraph: i };
          }
        }
        return null;
      },
      /* from and to name the sentence being spoken inside the paragraph, and -1 names the whole paragraph. */
      highlight: function (id, index, from, to) {
        spoken = id === null ? null : { id: String(id), index: index, from: from, to: to };
        draw();
        follow();
      },
      /* Read by the next choice only: following here would move the text as the chrome comes up. */
      setObscured: function (top, bottom) {
        obscured = { top: top, bottom: bottom };
      },
      redraw: draw,
      /* Boxes sit at fixed page coordinates, so a layout change lays them again; a highlight moves itself. */
      reposition: function () {
        if (overlay && overlay.firstChild) draw();
      },
      invalidate: function () {
        cache = {};
        if (spoken) draw();
      },
    };
  })();

  // endregion

  // region the host's handle

  window.rkReader = {
    readAloud: readAloud,
    /* Called after any insert, so the next frame measures the shape the reader is actually in. */
    refresh: function () {
      rebuildBoundaries();
      onScroll();
    },
    setSettings: function (next) {
      var wasBionic = settings.bionic;
      Object.keys(next).forEach(function (k) { settings[k] = next[k]; });
      if (settings.bionic && !wasBionic) {
        place.across(function () { document.querySelectorAll(CHAPTER_SELECTOR).forEach(applyBionic); });
      }
      syncBionic();
      if (next.readAloud) readAloud.redraw();
    },
    setSnippetCss: setSnippetCss,
    /* The face for a font picked while this page is open; the family itself arrives as a variable. */
    setFontFace: function (css) {
      document.getElementById('rk-font-face').textContent = css;
    },
    /* The volume keys' page step, through the same relative animation as a tap. */
    scrollSmoothlyBy: function (dy) {
      onReaderMove();
      glide.by(dy);
    },
    autoScrollStart: function (perFrame) { autoScroll.start(perFrame); },
    autoScrollStop: function () { autoScroll.stop(); },
    /* Scrolls so a chapter's own fraction is the reading position, which is how a restore and the
       rail both land somewhere inside one chapter of a window rather than of the document. */
    seekWithin: function (chapterId, fraction) {
      topLine.forget();
      rebuildBoundaries();
      for (var i = 0; i < boundaries.length; i++) {
        if (boundaries[i].id !== String(chapterId)) continue;
        // The inverse of state()'s measure, so a seek and a report cannot drift apart.
        var usable = Math.max(boundaries[i].height - viewportHeight(), 1);
        // Rounded, so a seek to a chapter's own start lands on it rather than a fraction below.
        window.scrollTo({ top: Math.round(boundaries[i].start + usable * fraction), behavior: 'instant' });
        // Holds the line landed on at once, so a change arriving before the next frame is not measured
        // against the line the seek left.
        place.sync();
        // The reader is now in this chapter by construction, so the next frame must not report the
        // one above and hand the rest of a rail drag a target the reader has already left.
        lastChapterSeen = String(chapterId);
        return;
      }
    },
    /*
     * baseUrl is the chapter's own, or absent for one with none (a download). seam is the marker
     * between it and the chapter it joins, as the host worked it out (NovelSeam), or null for none.
     */
    appendChapter: function (id, html, baseUrl, seam) {
      insertChapter(id, html, baseUrl, false, seam);
    },
    prependChapter: function (id, html, baseUrl, seam) {
      insertChapter(id, html, baseUrl, true, seam);
    },
    /*
     * Replaces the marker that introduces chapter id with seam, or removes it for null: the host
     * re-decides every seam when the setting that hides them changes. The place-holding keeps the
     * reader still, as it does for a chapter arriving above.
     */
    setSeam: function (id, seam) {
      var el = document.querySelector(CHAPTER_SELECTOR + '[' + CHAPTER_ID_ATTR + '="' + id + '"]');
      if (!el) return;
      var above = el.previousElementSibling;
      if (above && above.classList.contains('rk-seam')) above.parentNode.removeChild(above);
      if (seam) el.parentNode.insertBefore(buildSeam(seam), el);
      window.rkReader.refresh();
    },
    /*
     * The marker below the novel's last chapter, or null to clear it. Outside the chapter container
     * like a failure, so it is never counted as chapter height nor taken for a chapter's seam.
     */
    setEnd: function (end) {
      var existing = document.getElementById('rk-end');
      if (existing) existing.parentNode.removeChild(existing);
      if (end) {
        var marker = buildSeam(end);
        marker.id = 'rk-end';
        var container = document.getElementById('rk-chapters');
        container.parentNode.insertBefore(marker, container.nextSibling);
      }
      window.rkReader.refresh();
    },
    /*
     * Why the window stops at an edge, drawn as the text renderer draws it (NovelBoundaryFailureView):
     * the heading, the source's own message under it when there is one, and Retry, which turns into
     * progress once tapped. Outside the chapter container, so it can never be counted as chapter
     * height. failure is { heading, message, retry } from the host, or null to clear that edge.
     */
    setBoundaryFailure: function (atStart, failure) {
      var id = atStart ? 'rk-failure-start' : 'rk-failure-end';
      var existing = document.getElementById(id);
      if (existing) existing.parentNode.removeChild(existing);
      if (!failure) return;
      var box = document.createElement('div');
      box.id = id;
      box.className = 'rk-failure';
      box.appendChild(textBlock('rk-failure-heading', failure.heading));
      if (failure.message) box.appendChild(textBlock('rk-failure-message', failure.message));
      var button = document.createElement('button');
      button.className = 'rk-failure-retry';
      button.textContent = failure.retry;
      // A chapter's script clicking it would refetch the failed chapter past the host's cooldown.
      button.addEventListener('click', function (e) {
        if (!e.isTrusted) return;
        var progress = document.createElement('div');
        progress.className = 'rk-failure-progress';
        button.parentNode.replaceChild(progress, button);
        bridge().onRetryBoundary(DOCUMENT_TOKEN, !atStart);
      });
      box.appendChild(button);
      var container = document.getElementById('rk-chapters');
      if (atStart) {
        container.parentNode.insertBefore(box, container);
      } else {
        container.parentNode.appendChild(box);
      }
    },
    evictChapter: function (id) {
      var el = document.querySelector(CHAPTER_SELECTOR + '[' + CHAPTER_ID_ATTR + '="' + id + '"]');
      if (!el) return;
      // The seam belonging to a chapter is the one above it, except for the first chapter, whose
      // seam is below because a seam introduces what follows it. Taking the wrong side strands one.
      var seam = el.previousElementSibling;
      if (!seam || !seam.classList.contains('rk-seam')) seam = el.nextElementSibling;
      el.parentNode.removeChild(el);
      if (seam && seam.classList.contains('rk-seam')) seam.parentNode.removeChild(seam);
      rebuildBoundaries();
    },
  };

  function buildChapter(id, html, baseUrl) {
    var el = document.createElement('div');
    el.className = 'rk-chapter';
    el.setAttribute(CHAPTER_ID_ATTR, String(id));
    el.appendChild(chapterContent(html, baseUrl));
    if (settings.bionic) applyBionic(el);
    return el;
  }

  // A value with a scheme, or a fragment, does not depend on a base and is left as written.
  var HAS_SCHEME = /^[a-z][a-z0-9+.-]*:/i;
  // One srcset candidate as the browser splits it: a URL runs to whitespace and can hold commas (a
  // data: URI), then either the commas that end it or its descriptors up to the next comma.
  var SRCSET_CANDIDATE = /([\s,]*)(\S+?)(,+(?=\s|$)|(?=\s|$)[^,]*)/g;

  /*
   * The document has one base, the chapter it opened on, so a chapter added by scrolling would resolve
   * its relative images and links against that chapter's site. They are made absolute against the
   * chapter's own base first, parsed in a template so nothing is fetched before the rewrite. A chapter
   * with no base keeps its values as written.
   */
  function chapterContent(html, baseUrl) {
    var template = document.createElement('template');
    template.innerHTML = html;
    if (!baseUrl) return template.content;
    template.content.querySelectorAll('[src], [href], [srcset]').forEach(function (node) {
      ['src', 'href'].forEach(function (name) {
        if (node.hasAttribute(name)) node.setAttribute(name, absolute(node.getAttribute(name), baseUrl));
      });
      if (!node.hasAttribute('srcset')) return;
      var srcset = node.getAttribute('srcset').replace(SRCSET_CANDIDATE, function (_, lead, url, tail) {
        return lead + absolute(url, baseUrl) + tail;
      });
      node.setAttribute('srcset', srcset);
    });
    return template.content;
  }

  /*
   * The element in chapter a link fragment names: as written first, then percent-decoded, the order a
   * browser tries and NovelChapterTags mirrors. A malformed escape names nothing decoded.
   */
  function anchorTarget(chapter, fragment) {
    var names = [fragment];
    try {
      names.push(decodeURIComponent(fragment));
    } catch (e) {
      // The name as written is the only one left.
    }
    for (var i = 0; i < names.length; i++) {
      if (!names[i]) continue;
      var name = CSS.escape(names[i]);
      var target = chapter.querySelector('[id="' + name + '"], a[name="' + name + '"]');
      if (target) return target;
    }
    return null;
  }

  /* value against baseUrl, or as written when it needs no base or is not a URL either base can read. */
  function absolute(value, baseUrl) {
    var trimmed = value.trim();
    if (!trimmed || trimmed.charAt(0) === '#' || HAS_SCHEME.test(trimmed)) return value;
    try {
      return new URL(trimmed, baseUrl).href;
    } catch (e) {
      return value;
    }
  }

  /*
   * The marker between two chapters, in the shape Mihon's TransitionText draws: the finished chapter
   * over the next one, each under its own label and marked when it is on disk, with a warning between
   * them when the numbering skips chapters. Mirrored rather than invented so a seam reads the same in
   * this renderer, the text renderer and the manga reader. What it says comes from the host. With no
   * next chapter it is the end marker, which says so in TransitionText's fallback notice.
   */
  function buildSeam(seam) {
    var el = document.createElement('div');
    el.className = 'rk-seam';
    el.appendChild(seamPart(labels.finished, seam.finished));
    if (!seam.next) {
      var notice = textBlock('rk-seam-notice', labels.noNext);
      notice.insertBefore(icon('rk-icon-info'), notice.firstChild);
      el.appendChild(notice);
      return el;
    }
    if (seam.missing) {
      var warning = textBlock('rk-seam-warning', seam.missing);
      warning.insertBefore(icon('rk-icon-warning'), warning.firstChild);
      el.appendChild(warning);
    }
    el.appendChild(seamPart(labels.next, seam.next));
    return el;
  }

  function seamPart(label, chapter) {
    var part = document.createElement('div');
    part.className = 'rk-seam-part';
    part.appendChild(textBlock('rk-seam-label', label));
    var name = textBlock('rk-seam-title', chapter.title);
    if (chapter.downloaded) {
      var mark = icon('rk-icon-downloaded');
      mark.setAttribute('role', 'img');
      mark.setAttribute('aria-label', labels.downloaded);
      name.insertBefore(mark, name.firstChild);
    }
    part.appendChild(name);
    return part;
  }

  function textBlock(className, text) {
    var el = document.createElement('div');
    el.className = className;
    el.textContent = text;
    return el;
  }

  function icon(className) {
    var el = document.createElement('span');
    el.className = 'rk-icon ' + className;
    return el;
  }

  /* A chapter landing above the reader, and anything its pictures or scripts add later, is taken back
     by the place-holding, at scroll offset zero as anywhere else. */
  function insertChapter(id, html, baseUrl, atStart, seam) {
    var container = document.getElementById('rk-chapters');
    if (!container || document.querySelector(CHAPTER_SELECTOR + '[' + CHAPTER_ID_ATTR + '="' + id + '"]')) {
      return;
    }
    var chapter = buildChapter(id, html, baseUrl);
    // Between the two chapters either way: below the arriving one on a prepend, above it on an append.
    var marker = seam ? buildSeam(seam) : null;
    if (atStart) {
      if (marker) container.insertBefore(marker, container.firstChild);
      container.insertBefore(chapter, container.firstChild);
    } else {
      if (marker) container.appendChild(marker);
      container.appendChild(chapter);
    }
    runScripts(chapter);
    window.rkReader.refresh();
    // Room below a line that landed short, measured now the chapter is in.
    if (!atStart) topLine.reland();
  }

  /*
   * A chapter's own scripts, which a parsed-in chapter never runs. The chapter the page opened on ran
   * its scripts as the document loaded, so a chapter arriving while scrolling gets the same: fresh
   * script elements, in order, each one with a source loaded before the next runs, as the parser does.
   * A created script is otherwise async, so an inline one ran ahead of the library it needs. The
   * pipeline strips scripts unless "Run scripts a chapter embeds" is on.
   */
  function runScripts(root) {
    var pending = Array.prototype.slice.call(root.querySelectorAll('script'));
    (function next() {
      var old = pending.shift();
      if (!old) return;
      // The chapter can be evicted while a script ahead of this one loads.
      if (!old.isConnected) {
        next();
        return;
      }
      var fresh = document.createElement('script');
      for (var i = 0; i < old.attributes.length; i++) {
        fresh.setAttribute(old.attributes[i].name, old.attributes[i].value);
      }
      fresh.text = old.text;
      if (loadsFromSource(old)) {
        fresh.addEventListener('load', next);
        fresh.addEventListener('error', next);
        old.parentNode.replaceChild(fresh, old);
        return;
      }
      writingInPlace(fresh, function () { old.parentNode.replaceChild(fresh, old); });
      next();
    })();
  }

  // The HTML standard's JavaScript MIME types, the only classic scripts a browser fetches.
  var JAVASCRIPT_TYPE =
    /^(?:(?:application|text)\/(?:x-)?(?:ecma|java)script|text\/(?:javascript1\.[0-5]|jscript|livescript))$/i;

  /*
   * Whether the browser fetches this script, and so reports a load or an error to wait on. A type it
   * never runs fires neither, and waiting on one stopped every script after it. The type is resolved
   * as the standard does: an empty one is JavaScript, and with none, the language attribute names it.
   */
  function loadsFromSource(script) {
    if (!script.hasAttribute('src')) return false;
    var type = script.getAttribute('type');
    var language = script.getAttribute('language');
    if (type === '' || (type === null && !language)) {
      type = 'text/javascript';
    } else {
      type = type === null ? 'text/' + language : type.trim();
    }
    // nomodule stops a classic script only; a module ignores it.
    if (type.toLowerCase() === 'module') return true;
    return JAVASCRIPT_TYPE.test(type) && !script.hasAttribute('nomodule');
  }

  /*
   * document.write from a script the parser is not running opens a new document, which wiped the
   * reader. While an inserted chapter's inline script runs, what it writes lands where the script
   * sits, as it would have at parse time. One with a source cannot write at all once parsing is over.
   */
  function writingInPlace(script, run) {
    document.write = document.writeln = function () {
      script.insertAdjacentHTML('beforebegin', Array.prototype.join.call(arguments, ''));
    };
    try {
      run();
    } finally {
      delete document.write;
      delete document.writeln;
    }
  }

  // endregion

  window.addEventListener('scroll', onScroll, { passive: true });
  if ('onscrollend' in window) {
    // Every frame of a glide or an auto-scroll is an instant scroll that ends in its own scrollend, so
    // those are skipped while one runs. The last scroll's scrollend arrives the frame after it stops,
    // which settles the position once.
    window.addEventListener('scrollend', function () {
      if (!glide.running() && !autoScroll.running()) persist();
    }, { passive: true });
  } else {
    var settleTimer = null;
    window.addEventListener('scroll', function () {
      clearTimeout(settleTimer);
      settleTimer = setTimeout(function () { persist(); }, 250);
    }, { passive: true });
  }

  // An image that lands without changing the layout escapes the resize observer, and it is what
  // releases a chapter reportFits or reportEnds is holding. A broken one releases it the same way.
  // Measured again in the frame rather than read from the boundaries: a frame runs before the resize
  // observer, so a tall picture below the reader still measured short, and its chapter's end was said.
  ['load', 'error'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (e.target.tagName !== 'IMG') return;
      imageSettled = true;
      onScroll();
    }, true);
  });

  // Drops the loading box reader.css draws in a picture's place. A failed one gets its failure box instead.
  document.addEventListener('load', function (e) {
    if (e.target.tagName === 'IMG') e.target.classList.add('rk-loaded');
  }, true);

  /*
   * A chapter picture that fails is shown as the manga reader shows a failed page: a box saying so, with
   * Retry when its address can be asked again, which an inline one cannot. The text renderer draws the
   * same (NovelImageGetter). The box is a failure, so a tap on it is not the reader's (installGestures).
   * The picture stays in the chapter, hidden, since a link may name it and its attributes are the chapter's.
   */
  document.addEventListener('error', function (e) {
    var img = e.target;
    if (img.tagName !== 'IMG' || !img.closest(CHAPTER_SELECTOR)) return;
    var box = document.createElement('div');
    box.className = 'rk-failure rk-image-failure';
    box.appendChild(textBlock('rk-failure-heading', labels.imageError));
    if (/^(https?:)?\/\//i.test(img.getAttribute('src') || '')) {
      var button = document.createElement('button');
      button.className = 'rk-failure-retry';
      button.textContent = labels.retry;
      button.addEventListener('click', function (click) {
        if (!click.isTrusted) return;
        var fresh = img.cloneNode(false);
        fresh.style.display = '';
        box.parentNode.removeChild(box);
        img.parentNode.replaceChild(fresh, img);
      });
      box.appendChild(button);
    }
    img.style.display = 'none';
    img.parentNode.insertBefore(box, img);
  }, true);

  installGestures();
  syncBionic();

  /* What needs the chapter parsed, which this script, running in the head, is ahead of. */
  function start() {
    if (typeof ResizeObserver === 'function') {
      new ResizeObserver(function () {
        lastResizeAt = Date.now();
        rebuildBoundaries();
      }).observe(document.body);
    }
    new MutationObserver(function () {
      readAloud.invalidate();
      topLine.invalidate();
    })
      .observe(document.getElementById('rk-chapters'), { childList: true, subtree: true, characterData: true });
    if (settings.bionic) {
      document.querySelectorAll(CHAPTER_SELECTOR).forEach(applyBionic);
    }
    requestAnimationFrame(function () {
      whenViewportHasHeight(landAndReportReady);
    });
  }

  /*
   * A WebView can start the page before it has been laid out, and a seek against no height scrolls by
   * the whole chapter, which the height arriving then clamps to its end. Ready waits too, since every
   * report and queued seek after it measures against the viewport.
   */
  function whenViewportHasHeight(run) {
    if (viewportHeight() > 0) {
      run();
      return;
    }
    window.addEventListener('resize', function onResize() {
      if (viewportHeight() <= 0) return;
      window.removeEventListener('resize', onResize);
      run();
    });
  }

  function landAndReportReady() {
    rebuildBoundaries();
    // Where the chapter was left. Applied here rather than by the host, because a scroll issued
    // against a document that has not laid out yet lands at zero and looks like a lost position.
    var initial = __INITIAL_FRACTION__;
    // The line the reader had at the top, which a rebuilt page lands on in place of the fraction.
    var initialLine = __INITIAL_LINE__;
    // A line needs no pictures: place holds it as they land above it. Only a fraction waits for them.
    if (initialLine >= 0 && boundaries.length > 0 && !readerMoved && topLine.seek(boundaries[0].el, initialLine)) {
      reportReady();
      return;
    }
    if (initial > 0 && boundaries.length > 0 && !readerMoved) {
      // The saved fraction is of the chapter's height with its images in it, and reader.css gives
      // every image `height: auto`, so before they land the chapter measures short by the whole
      // image block and the seek drops the reader past unread text. Anchoring then holds them
      // there and the next report saves that place over the one they left.
      seekWaiting = true;
      whenImagesLanded(boundaries[0].el, function () {
        if (!seekWaiting) return;
        seekWaiting = false;
        rebuildBoundaries();
        window.rkReader.seekWithin(boundaries[0].id, initial);
        reportReady();
      });
      return;
    }
    reportReady();
  }

  /*
   * The reader moved the page themselves, which ends the opening seek: made later, it would take them
   * back from what they scrolled to, and the host hears nothing of their reading before ready. Only what
   * they ask for counts; auto-scroll, a read-aloud follow and a host seek move the page on their behalf.
   */
  function onReaderMove() {
    readerMoved = true;
    topLine.forget();
    if (!seekWaiting) return;
    seekWaiting = false;
    reportReady();
  }

  /* The host drops everything the page says before this, so it is the last thing a start does. */
  function reportReady() {
    bridge().onReady(DOCUMENT_TOKEN);
    ready = true;
    reportFits();
    reportEnds();
    // A page that never scrolls again would otherwise never say where it is.
    onScroll();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
