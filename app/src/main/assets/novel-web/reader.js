/*
 * Reikai's page-side engine for the WebView rendering mode, replacing the vendored core.js.
 *
 * Reports through the ReikaiWeb bridge (see NovelWebBridge.kt) and exposes window.rkReader for the
 * host to call into. The chapter-boundary and per-chapter progress half is ported from tsundoku's
 * scroll-tracking.js; the tap, swipe, auto-scroll and bionic halves replace what core.js did.
 *
 * Tokens substituted at build time by NovelWebAssets: __TAP_TO_SCROLL__, __SWIPE__, __BIONIC__,
 * __INITIAL_FRACTION__, __LABEL_FINISHED__, __LABEL_NEXT__, __DOCUMENT_TOKEN__.
 */
(function () {
  // The token is in this script's own text. Removed while the engine still runs ahead of the chapter,
  // so no script the chapter carries can read it out of the page.
  var self = document.currentScript;
  if (self && self.parentNode) self.parentNode.removeChild(self);

  // Names this document to the host, whose gate opens only for the document it built last. A ready
  // report from the page being replaced, or from a chapter's script, carries no such token.
  var DOCUMENT_TOKEN = '__DOCUMENT_TOKEN__';

  var CHAPTER_SELECTOR = '.rk-chapter';
  var CHAPTER_ID_ATTR = 'data-rk-chapter-id';
  var CHAPTER_TITLE_ATTR = 'data-rk-chapter-title';
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

  // Resolved by the host, since the page has no resources of its own.
  var labels = {
    finished: '__LABEL_FINISHED__',
    next: '__LABEL_NEXT__',
  };

  var settings = {
    tapToScroll: __TAP_TO_SCROLL__,
    swipe: __SWIPE__,
    bionic: __BIONIC__,
  };

  var boundaries = [];
  var lastChapterSeen = null;
  var lastReported = -1;
  var lastReportedId = null;
  var lastReportedAt = 0;
  var heldReport = null;
  var lastResizeAt = 0;
  var framePending = false;

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

  function documentHeight() {
    return Math.max(
      document.documentElement.scrollHeight,
      document.body ? document.body.scrollHeight : 0
    );
  }

  /*
   * Rebuilds where each chapter starts and how tall it is, in document coordinates.
   * getBoundingClientRect plus scrollY rather than offsetTop, so a positioned container cannot
   * offset every reading. Cheap enough to redo on any DOM or size change, coalesced to one a frame.
   */
  function rebuildBoundaries() {
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
  }

  /* The last answer sent per chapter, so a rebuild that changes nothing says nothing. */
  var fitsReported = {};

  /*
   * Whether each chapter fits on one screen, the test state() makes when it holds one at 0. The model
   * reads such a chapter when the reader steps forward from it. Re-sent on change, since a chapter can
   * measure short before its images land.
   */
  function reportFits() {
    var viewport = viewportHeight();
    boundaries.forEach(function (b) {
      var fits = b.height <= viewport;
      if (fitsReported[b.id] === fits) return;
      fitsReported[b.id] = fits;
      bridge().onChapterFits(b.id, fits);
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
    var bottom = scrollTop() + viewportHeight() + EDGE_TOLERANCE;
    boundaries.forEach(function (b) {
      if (endsSeen[b.id] || b.start + b.height > bottom || !imagesLanded(b.el)) return;
      endsSeen[b.id] = true;
      bridge().onChapterEndSeen(b.id);
    });
  }

  function imagesLanded(el) {
    var images = el.querySelectorAll('img');
    for (var i = 0; i < images.length; i++) {
      if (!images[i].complete) return false;
    }
    return true;
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

  // region reporting

  function onFrame() {
    framePending = false;
    var s = state();
    if (s.id === null) return;

    if (s.id !== lastChapterSeen) {
      lastChapterSeen = s.id;
      bridge().onVisibleChapter(s.id);
    }

    reportProgress(s);
    reportEnds();
  }

  /*
   * Throttled, because this drives the rail and the percentage overlay on every scroll frame. A report
   * the throttle holds back is sent once the interval has passed, or a scroll stopping inside it left
   * the rail on a place the reader had already left. A chapter finishing, or a different chapter, is
   * never held: the last value sent belongs to the chapter before, so it cannot stand for this one.
   */
  function reportProgress(s) {
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
    bridge().onProgress(s.id, s.progress);
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
    if (s.id !== null) bridge().onProgressSettled(s.id, s.progress);
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

  function tapZone(y) {
    // Thirds vertically, matching the native renderer's own tap rule.
    var third = viewportHeight() / 3;
    if (y < third) return 'up';
    if (y > third * 2) return 'down';
    return 'menu';
  }

  function installGestures() {
    var startX = 0, startY = 0, startAt = 0, moved = false;
    document.addEventListener('touchstart', function (e) {
      // A finger on the page takes the scroll over, as it stops a fling.
      glide.stop();
      if (e.touches.length !== 1) return;
      startX = e.touches[0].clientX;
      startY = e.touches[0].clientY;
      startAt = Date.now();
      moved = false;
    }, { passive: true });

    document.addEventListener('touchmove', function (e) {
      if (e.touches.length !== 1) return;
      if (Math.abs(e.touches[0].clientX - startX) > 10 ||
        Math.abs(e.touches[0].clientY - startY) > 10) moved = true;
    }, { passive: true });

    document.addEventListener('touchend', function (e) {
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
          bridge().onStepChapter(true);
          return;
        }
        if (dx > 0 && startX <= middle) {
          bridge().onStepChapter(false);
          return;
        }
      }
      if (moved || elapsed > 400) return;
      // A tap on a link or a button (the failure box's Retry) is that control's, not the reader's.
      if (e.target && e.target.closest && e.target.closest('a, button')) return;

      var zone = settings.tapToScroll ? tapZone(touch.clientY) : 'menu';
      if (zone === 'menu') {
        bridge().onToggleMenu();
      } else {
        var by = viewportHeight() * 0.75;
        glide.by(zone === 'up' ? -by : by);
      }
    }, { passive: true });
  }

  /*
   * A smooth scroll made of relative steps, one a frame. The browser's own heads for a position
   * fixed when it starts, and crossing a boundary adds or drops a chapter above the reader
   * mid-animation, so it finished a whole chapter away. Relative steps ride on the scroll anchoring
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
      stop: function () { if (raf) { cancelAnimationFrame(raf); raf = null; } },
    };
  })();

  // endregion

  // region the host's handle

  window.rkReader = {
    /* Called after any insert, so the next frame measures the shape the reader is actually in. */
    refresh: function () {
      rebuildBoundaries();
      onScroll();
    },
    setSettings: function (next) {
      var wasBionic = settings.bionic;
      Object.keys(next).forEach(function (k) { settings[k] = next[k]; });
      if (settings.bionic && !wasBionic) {
        document.querySelectorAll(CHAPTER_SELECTOR).forEach(applyBionic);
      }
      syncBionic();
    },
    /* The face for a font picked while this page is open; the family itself arrives as a variable. */
    setFontFace: function (css) {
      document.getElementById('rk-font-face').textContent = css;
    },
    /* The volume keys' page step, through the same relative animation as a tap. */
    scrollSmoothlyBy: function (dy) { glide.by(dy); },
    autoScrollStart: function (perFrame) { autoScroll.start(perFrame); },
    autoScrollStop: function () { autoScroll.stop(); },
    /* Scrolls so a chapter's own fraction is the reading position, which is how a restore and the
       rail both land somewhere inside one chapter of a window rather than of the document. */
    seekWithin: function (chapterId, fraction) {
      rebuildBoundaries();
      for (var i = 0; i < boundaries.length; i++) {
        if (boundaries[i].id !== String(chapterId)) continue;
        // The inverse of state()'s measure, so a seek and a report cannot drift apart.
        var usable = Math.max(boundaries[i].height - viewportHeight(), 1);
        // Rounded, so a seek to a chapter's own start lands on it rather than a fraction below.
        window.scrollTo({ top: Math.round(boundaries[i].start + usable * fraction), behavior: 'instant' });
        // The reader is now in this chapter by construction, so the next frame must not report the
        // one above and hand the rest of a rail drag a target the reader has already left.
        lastChapterSeen = String(chapterId);
        return;
      }
    },
    /* baseUrl is the chapter's own, or absent for one with none (a download). */
    appendChapter: function (id, title, html, baseUrl) {
      insertChapter(id, title, html, baseUrl, false);
    },
    prependChapter: function (id, title, html, baseUrl) {
      insertChapter(id, title, html, baseUrl, true);
    },
    /*
     * Why the window stops at an edge. Drawn outside the chapter container, so it can never be
     * counted as chapter height and skew the progress of the chapter it sits against. Passing null
     * clears that edge. The strings come from the host, since the page has no resources.
     */
    setBoundaryFailure: function (atStart, message, retryLabel) {
      var id = atStart ? 'rk-failure-start' : 'rk-failure-end';
      var existing = document.getElementById(id);
      if (existing) existing.parentNode.removeChild(existing);
      if (message === null) return;
      var box = document.createElement('div');
      box.id = id;
      box.className = 'rk-failure';
      var text = document.createElement('div');
      text.className = 'rk-failure-message';
      text.textContent = message;
      var button = document.createElement('button');
      button.className = 'rk-failure-retry';
      button.textContent = retryLabel;
      button.addEventListener('click', function () {
        button.disabled = true;
        bridge().onRetryBoundary(!atStart);
      });
      box.appendChild(text);
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

  function buildChapter(id, title, html, baseUrl) {
    var el = document.createElement('div');
    el.className = 'rk-chapter';
    el.setAttribute(CHAPTER_ID_ATTR, String(id));
    el.setAttribute(CHAPTER_TITLE_ATTR, title);
    el.appendChild(chapterContent(html, baseUrl));
    if (settings.bionic) applyBionic(el);
    return el;
  }

  // A value with a scheme, or a fragment, does not depend on a base and is left as written.
  var HAS_SCHEME = /^[a-z][a-z0-9+.-]*:/i;

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
    template.content.querySelectorAll('[src], [href]').forEach(function (node) {
      ['src', 'href'].forEach(function (name) {
        var value = (node.getAttribute(name) || '').trim();
        if (!value || value.charAt(0) === '#' || HAS_SCHEME.test(value)) return;
        try {
          node.setAttribute(name, new URL(value, baseUrl).href);
        } catch (e) {
          // Not a URL either base can make sense of, so it stays as the source wrote it.
        }
      });
    });
    return template.content;
  }

  /*
   * The marker between two chapters, in the shape Mihon's TransitionText draws: the finished
   * chapter over the next one, each under its own label. Mirrored rather than invented so a seam
   * reads the same in this renderer, the text renderer and the manga reader.
   *
   * It names both chapters, so it needs the one above as well as the one below; getting that pair
   * backwards labels every boundary with the chapter the reader just left.
   */
  function buildSeam(finishedTitle, nextTitle) {
    var seam = document.createElement('div');
    seam.className = 'rk-seam';
    seam.appendChild(seamPart(labels.finished, finishedTitle));
    seam.appendChild(seamPart(labels.next, nextTitle));
    return seam;
  }

  function seamPart(label, title) {
    var part = document.createElement('div');
    part.className = 'rk-seam-part';
    var header = document.createElement('div');
    header.className = 'rk-seam-label';
    header.textContent = label;
    var name = document.createElement('div');
    name.className = 'rk-seam-title';
    name.textContent = title;
    part.appendChild(header);
    part.appendChild(name);
    return part;
  }

  /*
   * Chromium anchors the scroll position itself when content lands above the reader, so a prepend
   * needs no correction, with one exception it does not cover: at scroll offset exactly zero
   * anchoring is suppressed and the page shifts by the whole inserted height. That offset is where
   * a backward load lands, so the position is taken back by hand there. Measured both ways in
   * WebViewSeamPositionTest.
   */
  function insertChapter(id, title, html, baseUrl, atStart) {
    var container = document.getElementById('rk-chapters');
    if (!container || document.querySelector(CHAPTER_SELECTOR + '[' + CHAPTER_ID_ATTR + '="' + id + '"]')) {
      return;
    }
    var chapter = buildChapter(id, title, html, baseUrl);
    if (atStart) {
      var heightBefore = documentHeight();
      var topBefore = scrollTop();
      // The arriving chapter is the one that finished; what was first is what comes next.
      var below = container.querySelector(CHAPTER_SELECTOR);
      var seam = buildSeam(title, below ? below.getAttribute(CHAPTER_TITLE_ATTR) : '');
      container.insertBefore(seam, container.firstChild);
      container.insertBefore(chapter, seam);
      var added = documentHeight() - heightBefore;
      if (topBefore === 0 && added > 0) window.scrollTo({ top: added, behavior: 'instant' });
    } else {
      // What was last is what finished; the arriving chapter is what comes next.
      var chapters = container.querySelectorAll(CHAPTER_SELECTOR);
      var above = chapters[chapters.length - 1];
      container.appendChild(buildSeam(above ? above.getAttribute(CHAPTER_TITLE_ATTR) : '', title));
      container.appendChild(chapter);
    }
    // After the prepend compensation, so a script that adds height lands under anchoring rather than
    // inside the measurement.
    runScripts(chapter);
    window.rkReader.refresh();
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

  /* Whether the browser fetches this script, and so reports a load or an error to wait on. */
  function loadsFromSource(script) {
    var type = (script.getAttribute('type') || '').trim().toLowerCase();
    return script.hasAttribute('src') && !script.hasAttribute('nomodule') &&
      (type === '' || type === 'module' || /(java|ecma)script/.test(type));
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
    window.addEventListener('scrollend', function () { persist(); }, { passive: true });
  } else {
    var settleTimer = null;
    window.addEventListener('scroll', function () {
      clearTimeout(settleTimer);
      settleTimer = setTimeout(function () { persist(); }, 250);
    }, { passive: true });
  }

  // An image that lands without changing the layout escapes the resize observer, and it is what
  // releases a chapter reportEnds is holding. A broken one releases it the same way.
  ['load', 'error'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (e.target.tagName === 'IMG') onScroll();
    }, true);
  });

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
    if (settings.bionic) {
      document.querySelectorAll(CHAPTER_SELECTOR).forEach(applyBionic);
    }
    requestAnimationFrame(function () {
      rebuildBoundaries();
      // Where the chapter was left. Applied here rather than by the host, because a scroll issued
      // against a document that has not laid out yet lands at zero and looks like a lost position.
      var initial = __INITIAL_FRACTION__;
      if (initial > 0 && boundaries.length > 0) {
        window.rkReader.seekWithin(boundaries[0].id, initial);
      }
      bridge().onReady(DOCUMENT_TOKEN);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
