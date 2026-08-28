# In-app interactive Cloudflare (Turnstile) solver

The developer-facing record for the optional in-WebView solver that ticks the "Verify you are human"
box an interactive Cloudflare challenge waits on. The server-side fallback for challenges this cannot
clear is [flaresolverr-integration.md](flaresolverr-integration.md).

## Goal

Let a source behind an *interactive* Cloudflare challenge load without the user opening a WebView and
tapping the checkbox by hand, and without running a bypass server.

## Why

Upstream's bypass WebView solves a non-interactive challenge and gives up the moment one turns
interactive (mihonapp/mihon#3842, contributed by the same person who proposed this solver). Until now
the only ways past that were Open in WebView and tapping it yourself, or running FlareSolverr /
Solverr / Byparr. Every host measured issuing an interactive challenge is a mainstream manga source,
so the gap is not exotic.

## Approach

The bypass WebView is attached to the foreground activity's window, sized to it, translated fully off
screen and made non-focusable. It stays there for the solve and is removed in the existing cleanup.
That is the Android form of what Solverr does on the desktop: a headed browser whose window is
cloaked, never a headless one, for the reason its own dependency states, that headless flips the
browser onto a code path with no widget tree that anti-bot systems can spot.

While attached, an isolated-world script reports twice a second what the page shows: whether the
challenge markup is present, the response token and the page HTML. It runs in a world the challenge's
own scripts cannot see, because Byparr measured that scripts in the page's own world against a live
challenge make Cloudflare reissue it. Kotlin decides from those reports and presses the checkbox by
dispatching Tab then Space at the WebView: real key events that carry a true `isTrusted`, and that
need no DOM patching and no coordinate.

Requests to one host dedupe onto a single solve, and the challenge is only considered over once the
page has stopped looking like an interstitial across two readings and a fresh `cf_clearance` is in
the jar. The normal retry then runs, exactly as upstream's path does.

With no activity on screen there is no window to attach to and no input reaches the widget, which is
the case a background library update hits. There the solver instead registers a borrowed script into
the challenge frame and reloads, and the script clicks the checkbox for itself. That script is scoped
to `https://challenges.cloudflare.com` so it never runs in the source's own page, where a script is
what makes Cloudflare reissue the challenge. It gets a budget, after which the request is failed
rather than left to sit out the caller's whole wait.

Off by default, behind `Settings -> Advanced -> Networking`. With it off, the code path is upstream's
unchanged.

## Key files

- `core/common/.../network/interceptor/TurnstileSolver.kt`: attach/detach, the isolated-world probe,
  the press cadence, the interstitial test, per-host dedup, and the no-window fallback with its
  budget (net-new).
- `core/common/src/main/assets/CloudflareSolverIframeScript.js`: the borrowed in-frame clicker, taken
  from mihonapp/mihon#3858 at `e6de3a7a1`, verbatim apart from three `console.log` debug lines that
  were stripped. It names every function it defines with one token so it can filter itself out of
  stack traces, and `injectFallback` renames that token per injection.
- `core/common/.../util/system/ForegroundActivity.kt`: the activity tracker the solver needs to find
  a window, registered from `App.onCreate` (net-new).
- `CloudflareInterceptor`: arm, detach, do not trust a bare clearance while armed, and the
  cleared-without-a-report fallback. A `// RK` island on Mihon's file.
- `NetworkPreferences.enableTurnstileSolver`, `SettingsAdvancedScreen`, `strings.xml`
  (`pref_enable_turnstile_solver`): the switch and its wording.
- `SearchViewModel.updateItem`: not part of this feature, but the global search defect below
  was found through it and fixed alongside.

## Status

Prototype, shipped off by default in `14d3d54c1`. Device-verified on the Fold over a VPN across
repeated cold starts with cookies and WebView data cleared between runs.

Four or five challenged hosts in one global search clear in **2.9 to 3.9 seconds**, in parallel,
with no failures and no keyboard disruption: `aquareader.org`, `comix.to`, `comick.live`,
`mangafire.to`, `www.natomanga.com`, `toonily.com`. A single host takes 2.2 to 3.1 seconds from the
challenge turning interactive to the page being past it.

The shape of the numbers, run over the same test as the design changed:

| build | solved | burst | failures |
|---|---|---|---|
| concurrent, pressing before the challenge turned interactive | 0 of 6 | n/a | all |
| serialized, one solve at a time | 3 of 6 | ~35s | 3 |
| serialized, press gated on interactive, per-host dedup | 5 of 6 | ~33s | 1 |
| parallel, dedup, undetected-solve fallback | 6 of 6 | 3.4s | 0 |
| pressing with Tab and Space instead of a tap | **44 of 44** | **2.9-3.9s** | **0** |

The no-window fallback, measured separately, all on the Fold over the same VPN:

| condition | foreground service | cleared |
|---|---|---|
| app in the foreground, branch forced | n/a | 5 of 5 |
| app backgrounded, global search | no | **0 of 5**, `fail` then 403 |
| library update, activity destroyed | yes | 3 of 3, 9.4 to 12.2s |

The first row measures the mechanism, not the environment, so it is not evidence the fallback works
when the app is away; the third row is. Do not quote the first on its own.

Not verified: any host beyond those six, any device beyond the Fold, and behaviour over time as
Cloudflare changes. There is no automated test; the mechanism lives in a WebView and a live
challenge, neither of which is reachable from a unit test.

## Decisions & tradeoffs

- **Attachment is what real input needs, not what the widget needs.** Detached, the widget's shadow
  root only ever held a spinner, some text and a link, with no `input` in it, so there was nothing to
  press however the events were dressed up; fourteen real taps over a full thirty seconds changed
  nothing. Attached, the widget reports `300x65`, the checkbox appears, and an ordinary tap clears it
  in about two seconds. That observation was of the shadow root in the *main* document, and the
  checkbox is not there in either case: it lives in the challenge frame, which the fallback reaches
  and a script in the page cannot. So attachment is required for the key path and irrelevant to the
  in-frame one.
- **The challenge frame reports `https://challenges.cloudflare.com`.** Measured on a live managed
  challenge by injecting an isolated-world probe into every frame and having each report its own
  origin. This is what lets the fallback be scoped to that origin instead of every frame, which
  matters because a script in the source's own page is what makes Cloudflare reissue. A nested
  `srcdoc` frame with an opaque origin sits inside it and is not covered by that rule; the solves
  land anyway, so the checkbox is in the outer frame. The interstitial's own CSP permits `'self'`,
  `blob:` and this origin, so the markup alone does not answer the question.
- **The old note that the widget frame "reports an empty URL" meant cross-origin, not origin-less.**
  It was recorded from a probe reading the frame from the parent, where a cross-origin frame's URL is
  simply unreadable. Read as a claim about the frame's own origin it points the wrong way, and it
  cost a round of planning here before the measurement above settled it.
- **The fallback budget is 20 seconds, and the timer must not be `View.postDelayed`.** A detached
  view parks its posts in a `RunQueue` that only drains on attach, so a `View.postDelayed` timer on
  this path can never fire. It was written that way first, compiled, ran, and was only caught by
  shrinking the budget below the known solve time and watching for the give-up that never came. Use a
  `Handler` on the main looper.
- **`ForegroundActivity` deliberately still returns a backgrounded activity.** It records on resume
  and never clears on pause, so `current` goes null only once no activity is alive. Clearing it on
  pause was considered and rejected on measurement: that would route the app-backgrounded case from
  the key path, which cleared 3 of 5 there, to the fallback, which cleared 0 of 5. The file stays
  byte-identical to upstream's copy of it.
- **The press is Tab then Space, not a tap at a computed point.** Suggested by the person who
  proposed the solver, and used by their upstream port (mihonapp/mihon#3858). Forty-four interactive
  challenges over nine VPN rounds cleared on keys alone, at 2.2 to 3.1 seconds each, the range
  the tap measured. The four rounds run before the tap came out carried it as a fallback and never
  reached it.
- **A failed challenge and a dead renderer end the wait early**, both taken from
  mihonapp/mihon#3858. Cloudflare posts a `fail` event on a round it has abandoned, and a renderer
  that dies never finishes the page, so either one used to cost the whole thirty-second latch. The
  `fail` abort is trusted only while the solver is off: armed, it keeps pressing, because Cloudflare
  reissues after a failed round often enough to matter. Neither path fired in testing.
  Keys need no coordinate, so the checkbox inset, the widget-rect walk in the probe and the viewport
  scaling are gone: that arithmetic was the part most exposed to Cloudflare restyling its widget.
- **Focus turned out not to matter, and taking it was a bug.** An early design spoofed
  `document.hasFocus()` because the synthetic-event press needed it. Neither real input needs it, and
  holding focus reopened the soft keyboard over whatever the user was typing in, once per solve, so
  the view is non-focusable. Every key press measured reported `hasFocus()` true, including with the
  keyboard up and the app's search field focused, so the IME does not take focus from an attached
  WebView; the `false` readings the tap runs recorded have not been reproduced since.
- **Press only once the challenge turns interactive.** The response token exists from the first
  paint, so a rect is measurable before there is any checkbox behind it. Pressing then does nothing
  except start the cooldown that delays the press which counts, costing about two seconds a solve.
- **Cadence rules are ported, not invented.** A landed press waits four seconds, and a box that
  already carries a token is never pressed: both measured by Byparr and Solverr, which agree.
- **One solve at a time was tried and removed.** A burst was serialized after a concurrent run
  produced thirty presses and no solve, read at the time as focus contention. It was not: it was the
  wasted-press bug above. With that fixed, six WebViews solve in parallel in the time one used to
  take, so the semaphore, its queue and its timeouts are gone.
- **A clearance is only trusted with the page.** Cloudflare hands out a `cf_clearance` on a challenge
  it has not accepted, which repeatedly ended a solve early with a 403 on the retry. While the solver
  is armed the cookie alone never counts as success; the page must also be past the interstitial, by
  Solverr's `looks_like_challenge_html` markers.
- **Serving the WebView's own page was built and reverted.** It was written for a problem that did
  not exist: a genuine solve's clearance replays through OkHttp fine on every host measured, which
  contradicts the strict-tier reasoning in the FlareSolverr record. That reasoning still holds for
  the hosts that record was written about; it does not generalize to interactive challenges.
- **The no-window path works in a library update and nowhere else measured.** Three solves in real
  update runs with the activity destroyed, 9.4 to 12.2 seconds. The same code with the app merely
  backgrounded and no foreground service holding the process up cleared **none** of five hosts:
  Cloudflare posted `fail`, and the retry took a 403 on every one. The variable that tracks the
  outcome across those runs is whether a foreground service is keeping the renderer at normal
  priority, which the update worker has and a backgrounded search does not. Timer throttling in a
  demoted renderer is the likely mechanism, and it is not proven.

## Dead ends

Each of these was built and run against a live challenge before being dropped. Do not re-tread them.

- **Porting the Tampermonkey script into the source's own page.** Synthetic DOM events plus an
  `isTrusted` proxy, an `attachShadow` capture and a `hasFocus` spoof. It genuinely pressed the
  checkbox and the challenge markup tore down, but Cloudflare then issued another round instead of
  the content, every time. That is the behaviour Byparr measured for page-world scripts, and it is
  why the reads moved to an isolated world and the press moved to real input. **The no-window
  fallback is not a reversal of this**: it is the same family of script, scoped to the challenge
  frame rather than the source's page, which is the distinction that makes it work.
- **Real input without attaching.** `dispatchTouchEvent` on a detached WebView does nothing at all.
  Laying the view out by hand with `measure` and `layout` gives it a viewport, and the checkbox
  renders, but layout is not attachment and input still goes nowhere.
- **Serving the WebView's own page as the response**, the way the FlareSolverr path does. Built,
  including the JSON-viewer unwrap, then reverted: the clearance from a real solve replays through
  OkHttp on every host measured, so the ordinary retry is correct and simpler.
- **One solve at a time.** See the decision above; the concurrency failure it was built for was
  really the wasted-press bug.
- **Waiting for `document.readyState === 'complete'`** before judging the page. Left over from the
  body-serving design. An image-heavy source cleared its challenge and then kept loading, so the
  solve was never reported and the request failed despite having succeeded.

## Open

- **Breadth.** Six hosts, one device, one VPN. Forty-four solves say the mechanism is reliable on
  those; nothing says how it behaves on a host with a different challenge configuration, or on a
  second device. The switch stays off by default until both are answered.
- **Upstream is carrying its own version of this** (mihonapp/mihon#3858, open, credited to
  `14d3d54c1`), and Reikai now carries its solver script ahead of that merge. It touches five files:
  `CloudflareInterceptor`, `WebViewInterceptor`, `ForegroundActivity.kt`, `App.kt` and the script
  asset. `ForegroundActivity.kt` arrives byte-identical to ours and `App.kt` adds the same
  registration our `// RK` island holds, so both reconcile trivially. The real collisions are the
  `CloudflareInterceptor` island and `WebViewInterceptor`, which that PR rewrites at the abstract
  class: `intercept` gains a nonce parameter and returns `Response?`, plus a new `getNonce` and
  `isBypassed` and a per-host read/write lock. Take theirs and fold ours in.
- **That PR decides a solve differently, and its rule is wrong.** Its `isBypassed` accepts a changed
  `cf_clearance` as proof. Cloudflare hands one out on a challenge it has not accepted, which is not
  a corner case: in the backgrounded run above all five hosts emitted `fail`, carried a clearance
  anyway, and took a 403 on the retry. Keep the two-clear-readings test when folding.
- **His per-host lock is taken, and it is the one piece here on an unmeasured benefit.** A sibling of
  an in-flight solve now blocks on the write lock and re-checks, where `onlyOncePerHost` waited on a
  future and then retried blind. Measured against the old path back to back on the same hosts, the
  two are indistinguishable: every challenged request resolved either way, and solve times were 2.4
  to 2.9 seconds before against 2.5 to 3.2 after. The advantage is confined to the failure path, when
  a solve fails and the sibling would otherwise retry into a 403, and that case was never induced.
  It cost one regression on the way in, below.
  - **How to remove it, if it is ever judged not worth the divergence.** It landed in `fde007a24`,
    whose message carries these same steps. Not with `git revert`, since work will have landed on
    top: delete `locksByHost` and the read/write wrapping in `WebViewInterceptor.intercept`, drop
    `getNonce` / `isBypassed` and the `nonce` parameter, return `Response` instead of `Response?`,
    and restore `TurnstileSolver.onlyOncePerHost` (a `ConcurrentHashMap` of `CompletableFuture`
    guarding one solve per host) around the `resolveWithWebView` call.
  - **The regression it caused, since it will recur if this is re-derived.** The sibling shortcut
    returns from the base class without reaching the subclass, and the subclass is what closed the
    challenge response. Left unclosed, OkHttp refuses the retry on the same call with
    `cannot make a new request because the previous response is still open`. It compiled, passed the
    whole suite and solved every host; only looking at the screen showed the two hosts with
    concurrent requests were rendering an exception. The base class closes on that path now.
- **Global search is capped at five concurrent sources** (`SearchViewModel`'s fixed thread pool), and
  a solve holds one of those threads for its duration. At three seconds a solve this is not painful;
  it was when a solve took thirty.

## Resolved during this work

- **A source left spinning forever in global search**, its results already fetched. Traced to
  `updateItem` reading the item map out of state, adding one entry and writing it back wholesale, so
  two sources completing in the same millisecond erased each other and the loser kept `Loading`.
  Upstream carries the same code. Fixed in `70f6b5626` by building the new map inside `state.update`.
  Not caused by this feature, but it surfaced constantly once solved sources started completing in a
  cluster instead of failing one by one.
