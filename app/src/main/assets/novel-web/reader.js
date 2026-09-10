/*
 * Reikai's page-side engine for the WebView rendering mode, replacing the vendored core.js.
 *
 * Reports through the ReikaiWeb bridge (see NovelWebBridge.kt) and exposes window.rkReader for the
 * host to call into. The chapter-boundary and per-chapter progress half is ported from tsundoku's
 * scroll-tracking.js; the tap, swipe, auto-scroll and bionic halves replace what core.js did.
 *
 * Tokens substituted at build time by NovelWebAssets: __TAP_TO_SCROLL__, __SWIPE__, __BIONIC__,
 * __DONE_THRESHOLD__, __INITIAL_FRACTION__.
 */
(function () {
  var CHAPTER_SELECTOR = '.rk-chapter';
  var CHAPTER_ID_ATTR = 'data-rk-chapter-id';
  var CHAPTER_TITLE_ATTR = 'data-rk-chapter-title';
  var DONE_THRESHOLD = __DONE_THRESHOLD__;
  // A late reflow (images, fonts) fires scrollend against a still-settling height, so a persist
  // waits this out rather than saving a position the layout is about to move.
  var SETTLE_MS = 400;

  var settings = {
    tapToScroll: __TAP_TO_SCROLL__,
    swipe: __SWIPE__,
    bionic: __BIONIC__,
  };

  var boundaries = [];
  var lastChapterSeen = null;
  var lastReported = -1;
  var lastReportedAt = 0;
  var lastResizeAt = 0;
  var framePending = false;

  function bridge() {
    return window.ReikaiWeb;
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
      next.push({
        id: chapters[i].getAttribute(CHAPTER_ID_ATTR),
        start: start,
        height: rect.height,
      });
    }
    boundaries = next;
  }

  /*
   * Which chapter the reader is in and how far through it, rather than through the document.
   * The last chapter subtracts a viewport because its trailing screen is space nothing scrolls
   * into; a middle chapter ends at the next one's start and so needs no such correction. A last
   * chapter shorter than the viewport has no scroll room at all, so it falls back to document
   * progress, which can still reach the end.
   */
  function state() {
    var top = scrollTop();
    var viewport = viewportHeight();
    var scrollable = documentHeight() - viewport;
    var docProgress = scrollable > 0 ? top / scrollable : 1;
    if (scrollable > 0 && top >= scrollable - 2) docProgress = 1;
    if (docProgress >= DONE_THRESHOLD) docProgress = 1;
    if (docProgress < 0) docProgress = 0;

    if (boundaries.length === 0) {
      return { id: null, progress: docProgress, index: 0, isLast: true };
    }

    var index = 0;
    for (var i = 0; i < boundaries.length; i++) {
      if (top >= boundaries[i].start) index = i; else break;
    }
    var chapter = boundaries[index];
    var isLast = index === boundaries.length - 1;
    var progress;
    if (isLast && chapter.height <= viewport) {
      progress = docProgress;
    } else {
      var within = Math.max(top - chapter.start, 0);
      var usable = Math.max(chapter.height - (isLast ? viewport : 0), 1);
      progress = Math.min(within / usable, 1);
      if (progress >= DONE_THRESHOLD) progress = 1;
    }
    return { id: chapter.id, progress: progress, index: index, isLast: isLast };
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

    // Throttled, because this drives the rail and the percentage overlay on every scroll frame.
    // The completed case is exempt so a chapter finishing is never the report that got dropped.
    var now = Date.now();
    if (Math.abs(s.progress - lastReported) > 0.005 && now - lastReportedAt > 50) {
      lastReportedAt = now;
      lastReported = s.progress;
      bridge().onProgress(s.id, s.progress);
    } else if (s.progress >= 1 && lastReported !== 1) {
      lastReported = 1;
      bridge().onProgress(s.id, 1);
    }

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
   * Bolds the opening of each word. The length table is text-vide's own, mirrored from
   * NovelBionicSpans.boldLengthFor so the two renderers emphasise the same letters.
   */
  var FIXATION = [0, 4, 12, 17, 24, 29, 35, 42, 48];

  function boldLength(wordLength) {
    for (var i = 0; i < FIXATION.length; i++) {
      if (wordLength <= FIXATION[i]) return Math.max(wordLength - i, 0);
    }
    return Math.max(wordLength - FIXATION.length, 0);
  }

  function applyBionic(root) {
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var texts = [];
    while (walker.nextNode()) {
      if (walker.currentNode.nodeValue.trim()) texts.push(walker.currentNode);
    }
    texts.forEach(function (node) {
      if (node.parentNode && node.parentNode.classList &&
        node.parentNode.classList.contains('rk-bionic')) return;
      var replacement = document.createElement('span');
      replacement.className = 'rk-bionic';
      replacement.innerHTML = node.nodeValue.replace(/[\p{L}\p{Nd}]*\p{L}[\p{L}\p{Nd}]*/gu, function (word) {
        var n = boldLength(word.length);
        return n > 0 ? '<b>' + word.slice(0, n) + '</b>' + word.slice(n) : word;
      });
      node.parentNode.replaceChild(replacement, node);
    });
  }

  function tapZone(x, y) {
    // Thirds vertically, matching the native renderer's own tap rule.
    var third = viewportHeight() / 3;
    if (y < third) return 'up';
    if (y > third * 2) return 'down';
    return 'menu';
  }

  function installGestures() {
    var startX = 0, startY = 0, startAt = 0, moved = false;
    document.addEventListener('touchstart', function (e) {
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

      // A horizontal fling that stayed horizontal steps a chapter.
      if (settings.swipe && Math.abs(dx) > window.innerWidth * 0.25 && Math.abs(dx) > Math.abs(dy) * 2) {
        bridge().onStepChapter(dx < 0);
        return;
      }
      if (moved || elapsed > 400) return;
      // A tap on a link is the link's, not the reader's.
      if (e.target && e.target.closest && e.target.closest('a')) return;

      var zone = settings.tapToScroll ? tapZone(touch.clientX, touch.clientY) : 'menu';
      if (zone === 'menu') {
        bridge().onToggleMenu();
      } else {
        var by = viewportHeight() * 0.75;
        window.scrollBy({ top: zone === 'up' ? -by : by, behavior: 'smooth' });
      }
    }, { passive: true });
  }

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
    },
    autoScrollStart: function (perFrame) { autoScroll.start(perFrame); },
    autoScrollStop: function () { autoScroll.stop(); },
    /* Scrolls so a chapter's own fraction is the reading position, which is how a restore and the
       rail both land somewhere inside one chapter of a window rather than of the document. */
    seekWithin: function (chapterId, fraction) {
      rebuildBoundaries();
      for (var i = 0; i < boundaries.length; i++) {
        if (boundaries[i].id !== String(chapterId)) continue;
        var isLast = i === boundaries.length - 1;
        var usable = Math.max(boundaries[i].height - (isLast ? viewportHeight() : 0), 1);
        window.scrollTo({ top: boundaries[i].start + usable * fraction, behavior: 'instant' });
        return;
      }
    },
    appendChapter: function (id, title, html) {
      insertChapter(id, title, html, false);
    },
    prependChapter: function (id, title, html) {
      insertChapter(id, title, html, true);
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

  function buildChapter(id, title, html) {
    var el = document.createElement('div');
    el.className = 'rk-chapter';
    el.setAttribute(CHAPTER_ID_ATTR, String(id));
    el.setAttribute(CHAPTER_TITLE_ATTR, title);
    el.innerHTML = html;
    if (settings.bionic) applyBionic(el);
    return el;
  }

  /* A seam introduces the chapter below it, so it carries that chapter's title rather than the one
     above. Getting this backwards labels every boundary with the chapter the reader just left. */
  function buildSeam(title) {
    var seam = document.createElement('div');
    seam.className = 'rk-seam';
    seam.textContent = title;
    return seam;
  }

  /*
   * Chromium anchors the scroll position itself when content lands above the reader, so a prepend
   * needs no correction, with one exception it does not cover: at scroll offset exactly zero
   * anchoring is suppressed and the page shifts by the whole inserted height. That offset is where
   * a backward load lands, so the position is taken back by hand there. Measured both ways in
   * WebViewSeamPositionTest.
   */
  function insertChapter(id, title, html, atStart) {
    var container = document.getElementById('rk-chapters');
    if (!container || document.querySelector(CHAPTER_SELECTOR + '[' + CHAPTER_ID_ATTR + '="' + id + '"]')) {
      return;
    }
    var chapter = buildChapter(id, title, html);
    if (atStart) {
      var heightBefore = documentHeight();
      var topBefore = scrollTop();
      // The seam introduces what was the first chapter, since that is what now sits below it.
      var below = container.querySelector(CHAPTER_SELECTOR);
      var seam = buildSeam(below ? below.getAttribute(CHAPTER_TITLE_ATTR) : '');
      container.insertBefore(seam, container.firstChild);
      container.insertBefore(chapter, seam);
      var added = documentHeight() - heightBefore;
      if (topBefore === 0 && added > 0) window.scrollTo({ top: added, behavior: 'instant' });
    } else {
      container.appendChild(buildSeam(title));
      container.appendChild(chapter);
    }
    window.rkReader.refresh();
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

  if (typeof ResizeObserver === 'function' && document.body) {
    new ResizeObserver(function () {
      lastResizeAt = Date.now();
      rebuildBoundaries();
    }).observe(document.body);
  }

  installGestures();
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
    bridge().onReady();
  });
})();
