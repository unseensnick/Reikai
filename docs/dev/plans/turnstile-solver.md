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
challenge markup is present, the response token, the page HTML, and the progress events Cloudflare
posts to the page, which are readable from that world too. It runs in a world the challenge's
own scripts cannot see, because Byparr measured that scripts in the page's own world against a live
challenge make Cloudflare reissue it. Kotlin decides from those reports and presses the checkbox by
dispatching Tab then Space at the WebView: real key events that carry a true `isTrusted`, and that
need no DOM patching and no coordinate.

Requests to one host dedupe onto a single solve. The challenge is over when Cloudflare posts
`complete` and a fresh `cf_clearance` is in the jar; failing that event, the older rule still
applies, that the page stopped looking like an interstitial across two readings. The normal retry
then runs, exactly as upstream's path does.

With no activity on screen there is no window to attach to, which is the case a background library
update hits on a process that started without one. The solver presses keys there too: the WebView is
laid out by hand and handed the visibility and focus callbacks a window would have delivered, and the
same Tab and Space follow. That path gets a budget, after which the request is failed rather than
left to sit out the caller's whole wait, because with no window nothing else bounds it.

**A borrowed in-frame click script used to serve that case and has been deleted.** It was there
because a headless press was measured as impossible, and that measurement was wrong: the press had
been scheduled with `View.postDelayed`, which on a view that was never attached queues into the
view's own run queue and runs on attach, so every event after the first Tab silently never fired.
With a main-looper `Handler` the same press solves a real managed challenge headless. Deleting the
script also retires its ungated click loop, its separate injection origin, and the extra detection
surface it carried.

Off by default, behind `Settings -> Advanced -> Networking`. With it off, the code path is upstream's
unchanged.

## Key files

- `core/common/.../network/interceptor/TurnstileSolver.kt`: attach/detach, the isolated-world probe,
  the press cadence, the interstitial test, per-host dedup, the headless layout, and the no-window
  budget (net-new). Also `forceHeadless`, a debug-only flag that makes the no-window path run with a
  window, since the real trigger is a process that starts with no activity and a person holding the
  phone cannot reach it.
- `core/common/.../util/system/ForegroundActivity.kt`: the activity tracker the solver needs to find
  a window, registered from `App.onCreate` (net-new). Note it holds the last *resumed* activity and
  clears only when that one is finishing or destroyed, so backgrounding the app does not reach the
  no-window path; the activity is still alive and the windowed path runs against an off-screen decor
  view.
- `CloudflareInterceptor`: arm, detach, do not trust a bare clearance while armed, and the
  cleared-without-a-report fallback. A `// RK` island on Mihon's file.
- `NetworkPreferences.enableTurnstileSolver` and `enableTurnstileBackgroundSolver`,
  `SettingsAdvancedScreen`, `strings.xml`: the two switches and their wording. The second is nested
  under the first, which in this settings DSL means it is absent from the screen rather than greyed
  out while the parent is off, since `StatusWrapper` wraps every row in `AnimatedVisibility`.
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

The no-window path, measured separately, all on the Fold over the same VPN. The first three rows are
the deleted in-frame script and are kept only as the record of what it did:

| condition | foreground service | cleared |
|---|---|---|
| script, app in the foreground, branch forced | n/a | 5 of 5 |
| script, app backgrounded, global search | no | **0 of 5**, `fail` then 403 |
| script, library update, activity destroyed | yes | 3 of 3, 9.4 to 12.2s |
| keys, app in the foreground, branch forced | n/a | arm to accept in 3.3s |
| keys, library update, no activity ever created | yes | solved, arm to accept in 6.3s |

A forced-branch row measures the mechanism, not the environment, so it is not on its own evidence
that the path works when the app is away. The last row is: a delayed one-time update was queued, the
app killed during the delay, and the job then started a process with no activity at all, which met a
real interactive challenge and pressed it headless.

**Reaching that trigger by hand needs a delay, and the reason is worth keeping.** An update holds a
foreground service, which makes the process unkillable, and force-stopping instead cancels every
scheduled job that could restart it, so there is nothing left to fire. Nothing runs during an initial
delay, so the app can be killed there. `LibraryUpdateJob.startDelayed` plus the Networking row that
calls it exist only for this. Also ruled out on the way: `cmd jobscheduler run -f` on the periodic
job, which WorkManager refuses with "executed before schedule"; a `BOOT_COMPLETED` broadcast, which
shell may not send; a provider query, which is not exported; and a reinstall, which never woke it.

**That run also caught the `complete` fast path missing, and it has since been fixed.** Cloudflare
posted `complete` at 34.762 and the solver accepted at 36.023 through the markup path, with no
"cloudflare reports the challenge complete" line: the probe collected the event and shipped nothing,
because it batched to a 500ms tick that never came, the page having navigated away once the challenge
passed. The two-clear-readings fallback caught it, which is the argument for keeping that fallback.

`interactiveBegin` and `complete` are now reported the moment they arrive; the per-second heartbeat
still waits for the tick. Measured over one global search on a fresh exit, four hosts challenged at
once, all four reporting `complete` from the isolated world **before** the page-world bridge saw it:

| host | `complete` reported | accepted | lag |
|---|---|---|---|
| `aquareader.org` | 53.698 | 53.885 | 187ms |
| `comick.live` | 55.345 | 55.759 | 414ms |
| `www.natomanga.com` | 56.232 | 56.537 | 305ms |
| `toonily.com` | 57.306 | 57.682 | cleared without a report, retried |

Against the 1684ms this started at. **`toonily.com` is the case the whole `complete` change was for**,
and it took the other branch: it reached `Verified` on the event, the clearance had not yet differed
when the wait ended, and the post-latch jar check accepted it on the phase. That is the gate working
as designed on the one host whose pages always read challenged, which before this could only ever
fail. A shape-selecting captcha on a fifth host was correctly not attempted.

**Accepting on `complete` measured, one before-and-after pair on `aquareader.org`**, same device,
same VPN exit, cookies and WebView data cleared between them:

| acceptance rule | Cloudflare posts `complete` | solver accepts | gap |
|---|---|---|---|
| two clear probe readings | 19:34:14.126 | 19:34:15.810 | 1684ms |
| `complete` | 19:38:29.610 | 19:38:29.899 | **289ms** |

The remaining 289ms is the probe's own 500ms tick, which is the floor until the rework makes the
machine event-driven. The second run logged no `page reads clear, confirming` at all, so the accept
came through the `complete` branch and never the probe branch: that is the part that matters, because
the probe branch is the one a site embedding Turnstile on its own pages can never reach.

Not verified: any host beyond those six, any device beyond the Fold, and behaviour over time as
Cloudflare changes. There is no automated test; the mechanism lives in a WebView and a live
challenge, neither of which is reachable from a unit test. The `toonily.com` case that motivated
`complete` has not been re-run against the fix, because it is not a source installed here; the branch
evidence above is what stands in for it.

Every number above predates the script hardening and the press jitter. The script changes were
measured equivalent in headless Blink, which is the same engine the device runs: same click count,
same spoofed `isTrusted`, same method calls on the patched event, prototype names still intact, and
listener removal now matching a control page.

On the Fold the hardened build cleared twenty-two challenges across nine search rounds with no
crash, the only console errors coming from a source's own ad script. The one host that turned
interactive was pressed and cleared in 2.2 seconds, inside the single-host band above. The new gate
was confirmed by forcing the no-window branch in a throwaway build: with the background switch off,
every challenged host logged `not armed` and the solver stayed out of the way.

**The in-frame click was then verified too**, on a fresh VPN exit with cookies and WebView data
cleared, which is what finally drew interactive challenges. Four hosts turned interactive, all four
injected, and three reported cleared and accepted in 12.2, 13.4 and 13.5 seconds, inside the 10.9 to
13.9 band the eleven pre-hardening solves set. The fourth, `toonily.com`, was resolved by the
cleared-without-a-report jar check at 10.4 seconds rather than by the solver's own test. No
`Uncaught`, no `TypeError` and no `Illegal invocation` came out of the challenge frame: its only
console output was Cloudflare's own opaque `Error` strings and a WOFF parse warning, both of which
also appear on runs where the script never injects.

That run also exposed a false give-up, since fixed: the budget is a bare main-looper `Handler` that
fires whether or not the request is still waiting, so `toonily.com` logged `in-frame solver gave up
after 20000 ms` ten seconds after being served from the jar. `onGiveUp` now returns whether it had a
wait to release, and the line is printed only when it did. Cancelling the timer instead was weighed
and dropped: `detach` would have to hold the `Handler` and `Runnable` per solve in a singleton,
because a second `Handler` cannot remove another's messages, and the token overload that would avoid
that map is API 28 against a minSdk of 26.

Both halves were then measured on the device. With the budget cut to 3 seconds so no solve could
finish, six give-ups fired at 3.00 seconds each and every source failed fast with "Failed to bypass
Cloudflare" instead of sitting out the 30 second wait, which is what proves the release still
happens. Back at 20 seconds, `toonily.com` was again served by the jar check, at 9.5 seconds, and its
timer stayed silent, while the other three cleared in 11.3 to 12.0 seconds.

Two notes for whoever repeats this. `always_finish_activities` does **not** destroy `MainActivity`
on One UI, so it cannot reach the no-window path on this device; force the branch in code instead.
And the challenge type is Cloudflare's call: clearing cookies and WebView data gets you challenged at
all, but interactive rounds only started arriving after the VPN exit changed.

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
- **The state machine landed narrower than this doc's design, deliberately.** The design called for
  nine states spanning the solver and the interceptor. Four of the five things it was meant to fix
  had already landed separately by the time it was built: `complete` as the acceptance signal, one
  press path instead of two, one press deadline instead of three unrelated terminators, and both
  solver triggers calling one predicate. What shipped is a four-phase `Solve` (`Watching`,
  `Interactive`, `Verified`, `Accepted`) replacing six locals, and one `AtomicReference<Solve?>`
  replacing `solverArmed` and `interstitialGone` at the seam. Two exclusions, both measured against
  where the known bugs actually lived: the eight `countDown` sites in the `WebViewClient` stay,
  because six are upstream's aborts for the solver-off path and rewriting them risks the default
  configuration for no gain to the armed one; and there is no gave-up phase, because a solve can run
  out its budget after reaching `Verified` and overwriting it would discard the one fact the caller
  needs to trust a late clearance.
- **`onWatch` runs on the main thread**, which is why the solve's own locals never needed atomics.
  Not a documentation claim: it calls `webView.pressKeys()` with no posting, and that has worked on
  device throughout, which it could not off the main thread. Only `Solve.phase` is `@Volatile`, since
  the OkHttp thread reads it after a wait that may have ended on a timeout rather than a `countDown`,
  leaving no happens-before to lean on.
- **A WebView with no isolated world gets a degraded solve rather than none.** `addJavaScriptOnEvent`
  and `JS_INJECTION_IN_FRAME_AND_WORLD` arrived in androidx.webkit 1.16.0-alpha03, and the feature is
  negotiated against the installed WebView, so requiring it greyed the switch out with no explanation
  on anything older than a few months. The reason recorded for that refusal covered the *probe*, not
  the events: a poll in the page's own world is what makes Cloudflare reissue, while the two events a
  solve turns on already reach the caller's bridge on every challenged page. So without a world the
  solve runs on those events alone, adding nothing to the page, and loses only the markup fallback.
  Taken from mihonapp/mihon#3858, which degrades where we declined. Measured against three hosts with
  the branch forced: `complete` to accept in 252, 259 and 261ms, against 165 to 802ms with the probe.
- **A solve with no probe has to poll for the clearance itself.** The clearance lands with the
  navigation after `complete`, not with the event, so the one acceptance attempt made on the event is
  always too early. With a probe the next tick asks again; without one nothing does, and the first
  build of the degraded path spent every solve's full 20 second budget before the caller's jar check
  rescued it. It now asks every 250ms for five seconds.
- **No timer on this feature may use `View.postDelayed`. It must be a `Handler` on the main looper.**
  A detached view parks its posts in a `RunQueue` that only drains on attach, so on the no-window
  path a `View.postDelayed` timer can never fire. This cost twice. First the budget timer, written
  that way, compiled and run, caught only by shrinking the budget below the known solve time and
  watching for a give-up that never came. Then the key press itself, which scheduled its Tab release
  and both Space events the same way: the first Tab went out synchronously and the rest were queued
  forever, so a press that was never fully sent read as a press Cloudflare had refused. That false
  negative is what put "keys need a real window" in the record for months and sent a whole bisect
  after four innocent suspects.
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
- **There is now one press path, and the background switch stays anyway.** The two paths used to be
  unequally exposed, which is why the second one carried its own switch: the key path dispatches real
  `KeyEvent`s and patches nothing, while the in-frame path rewrote `Error`, `EventTarget`,
  `window.event` and `attachShadow` inside the challenge frame and fired synthetic events with a
  spoofed `isTrusted`. With the script deleted that asymmetry is gone, and the switch was kept on a
  different ground: solving with no app screen open means reaching a challenged host with nothing on
  screen to show for it, which is a choice worth leaving to the user. Its wording changed to match.
  `attach` still returns false when there is no window and the switch is off, so nothing arms and the
  caller keeps its own aborts.
- **The borrowed script's `Object.definePropery` typo is load-bearing. Do not correct it.** Measured
  in Blink four ways. `createProxy` runs on every prototype member before the misspelled call throws,
  so the walk's real product is the `objectToProxy` registrations, and those are what make a method
  reached through the patched event unwrap its receiver instead of throwing `Illegal invocation`.
  Correcting the spelling changes nothing a listener can observe and anonymises 48 members of
  `MouseEvent` / `UIEvent` / `Event`, since a `Proxy` of a native stringifies without its name.
  Deleting the function instead breaks `preventDefault`, `getModifierState` and `defaultPrevented` on
  the patched event. The dead call is gone and the walk is commented, so the trap cannot be re-set.
- **`removeEventListener` never removed anything, and now does.** The script registers a wrapper in
  place of the caller's listener, but removal looked the listener up in the wrapper-keyed map, which
  never matched, so removed listeners kept firing and every wrapper leaked. Measured against a
  control page: two deliveries after removal where a plain browser gives one, for both function and
  `handleEvent` listeners. Inherited from the borrow at `e6de3a7a1`; upstream carries it too.
- **The Gecko and Safari branches stay, with the broken line corrected.** `Error.prepareStackTrace`
  yields a `CallSite` in Blink, so the V8 branch always wins and neither of the other two can run on
  Android. `const stacks = WeakMap()` was missing its `new` and would have thrown; that is fixed
  rather than deleted, because inherited dead code is not this change to remove and the frozen
  upstream blob stays the provenance reference.
- **Press gaps are drawn per event, not held at a flat 100ms.** Upstream's cadence is identical on
  every press and across solves running at once, which is the one behavioural tell left on a path
  that is otherwise just typing. Gaps are now 70 to 160ms, accumulated so ordering cannot invert.
  The benefit is unmeasurable from outside and the change is cheap; it rode along with work already
  in this file rather than being sought on its own.

## Dead ends

Each of these was built and run against a live challenge before being dropped.

**Treat the list as leads, not settled facts.** Every entry rests on a spike build that was reverted
and never committed, so none of them is reproducible and each is a single unrepeated run. The
headless-press entry was recorded exactly this way and it was wrong: the harness solved in four
seconds what the spike had called impossible across four hosts. The same failure mode is available to
every bullet below. Before designing around one, re-test it in the harness, which is cheap and, unlike
a spike build, still exists.

Two are already narrowed by the bisect runs:

- **Keys without attaching are not a dead end.** The touch half below was measured with
  `dispatchTouchEvent` and stands untested since; the keys half was an extension of it and is
  contradicted, five phases out of five.
- **A page-world *listener* does not trigger a reissue.** The `detached-pageworld` phase injected the
  interceptor's own `evaluateJavascript` listener into the page's own world and the challenge solved
  normally, no second round. The dead end below covers a script that *manipulates* the challenge, and
  the evidence never reached further than that. The broader wording, that a probe in the page's own
  world is what makes Cloudflare reissue, is repeated in `TurnstileSolver`'s `isSupported` and
  `injectFallback` comments and in three places in this doc, and it is not supported by that run. The
  isolated world is still right for a probe that polls twice a second; the reason stated for it is
  overstated.

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

## Planned rework

**Not built.** This is the design the next substantial change to this feature follows, written before
any code moved so the shape is argued once rather than discovered halfway through. Three pieces land
ahead of it and two of them decide its final form.

### Why the current shape needs replacing

Five problems, none of which is a bug on its own, and between them they account for every defect this
feature has produced.

**The authoritative signal is ignored and inferred instead.** Cloudflare posts `interactiveBegin`,
`complete` and `fail`. The listener `CloudflareInterceptor` injects on page finish forwards two of
them and drops `complete`, so success is inferred from a 500ms probe needing two consecutive clear
readings plus a cookie change. That costs up to a second of latency on every solve and serializes the
whole document twice a second to do it.

**The isolation is one-sided.** `TurnstileSolver`'s `WATCH` probe runs in an isolated world because a
page-world script is what makes Cloudflare reissue a challenge, but the event listener feeding it is
injected with `evaluateJavascript` into the page's own world, and the `mihon` bridge it calls is added
to every frame with no origin check.

**The press mechanism is chosen once and can be wrong for the rest of the solve.** `attach` reads the
container a single time. Backgrounding mid-solve leaves the key path pressing into a window that is
gone; foregrounding mid-solve leaves the in-frame script clicking when the safe path has just become
available.

**Four places decide whether it is solved, using three rules**: the two-clear-readings gate in
`onWatch`, the fresh-cookie lambda in `resolveWithWebView`, the post-latch jar check, and
`WebViewInterceptor.isBypassed`. The silent success, the sibling 403 and the false give-up all lived
in the gaps between them.

**Nothing owns the lifetime of a solve.** Three unrelated terminators exist and none knows about the
others: the in-frame budget, the caller's 30 second latch, and four `countDown` sites in the
`WebViewClient`. The key path has none at all, so a press that goes nowhere, whether refused outright
or simply ineffective, costs the caller the full 30 seconds.

### The shape

One event stream, one state machine, one terminator, all on the main looper. The OkHttp thread only
waits for a terminal state and reads the outcome, which removes the eight captured booleans currently
shared across two threads and coordinated by luck.

| State | Entered when | Leaves to |
|---|---|---|
| `Armed` | the probe and bridge are registered, before `loadUrl` | `Challenged`, `NotChallenged`, `TimedOut` |
| `Challenged` | the probe or the HTTP error reports an interstitial | `Interactive`, `Cleared`, `Failed` |
| `Interactive` | Cloudflare posts `interactiveBegin` | `Pressed`, `Failed` |
| `Pressed` | a press strategy has been dispatched | `Verifying`, `Failed` |
| `Verifying` | Cloudflare posts `complete`, or the probe reports the interstitial gone | `Cleared`, `Failed` |
| `Cleared` | terminal: verified, and the clearance differs from the pre-solve one | retry the request |
| `Failed` | terminal: `fail`, a dead renderer, no press path, or a deadline | give up, hand to FlareSolverr |
| `NotChallenged` | terminal: the original URL finished and no challenge was found | return what the page gave |
| `TimedOut` | terminal: the whole-solve budget expired | give up, hand to FlareSolverr |

Two timers, both owned by the machine and both cancelled on any terminal transition: a whole-solve
budget armed at `Armed`, and a shorter press deadline armed at `Pressed`. That single pair replaces
the in-frame budget, the absent key-path deadline and the caller's latch as a de facto timeout.

**The press strategy is chosen at the `Interactive` edge, not at arm time.** A window means keys; no
window with the background switch on means the in-frame script; no window with it off is a terminal
`Failed` carrying that reason. A refused key is then not a special case but an ordinary transition to
`Failed`, which is what makes the fix proper rather than a fourth timer.

**Acceptance is one predicate, read by everyone.** `Cleared` requires that Cloudflare said `complete`
or the probe saw the interstitial go, *and* that a `cf_clearance` exists differing from the one held
before the solve. Every other reader calls it or is deleted. On any non-`Cleared` terminal the jar is
checked once more, preserving the cleared-without-a-report case as an explicit branch rather than a
bolt-on, and then `cf_clearance` is deleted so a queued sibling cannot mistake a refused round for a
solve.

**What the rework deletes**: the page-world `evaluateJavascript` listener and the `mihon`
`@JavascriptInterface` with it, once all three events arrive over the isolated-world bridge, which is
strictly better isolation than today; and the document serialization in the probe, if the observation
pass shows `complete` reaches the managed-challenge interstitial.

### Behaviour inventory

The bar a takeover has to clear is that every behaviour of the replaced code is walked and marked
present, deliberately dropped with a reason, or missing. Device verification finds what you thought
to test; this is what catches the rest. Thirty-two behaviours, as the code stands.

*Arming.* 1 Runs only with the switch on and the WebView feature set present. 2 With no window and the
background switch off it does not arm, leaving the caller's aborts in charge. 3 With a window the
WebView is attached to the foreground decor view, sized to it, shifted off screen by its own width,
non-focusable, descendants blocked. 3a With none it is measured and laid out at a default size, given
the visibility and focus callbacks, and takes focus, which is safe only because there is no window to
take it from. 4 Arming precedes `loadUrl`. 5 An arming failure currently still
reports armed, which is the bug the first fix closes.

*Detection.* 6 A probe reports every 500ms. 7 Challenged means a challenge selector matches or the
response-token input exists. 8 The document is omitted while challenged, when there is no body, and
above 4MB. 9 Only main-frame reports are handled. 10 A page still counts as challenged if its title
says "just a moment" or one of six markers appears. 11 `interactiveBegin` sets the interactive flag.
12 `fail` ends the wait only while the solver is not armed.

*Pressing.* 13 No press before interactive. 14 No press while the token input already holds a value.
15 No press within four seconds of the last. 16 Keys are Tab down immediately, then three events at
cumulative random 70 to 160ms gaps. 17 Whether the first key was accepted is logged and nothing else.
18 The same keys are sent with or without a window, every event after the first Tab posted through a
main-looper Handler rather than the view. 19 Which path armed is logged. 20 With no window the first
press starts a 20 second budget, which gives up afterwards and logs only when it released a wait.

*Accepting.* 21 Two consecutive clear readings are required before asking. 22 The caller accepts only
a clearance that differs from the pre-solve one. 23 Once accepted the solver stops reacting. 24 After
the wait, a changed clearance is accepted even if nothing reported it. 25 A sibling queued on the same
host skips its own solve when the clearance changed while it waited.

*Ending.* 26 A dead renderer ends the wait. 27 A main-frame HTTP error that is not a challenge ends
it. 28 The original URL finishing with no challenge found ends it. 29 Otherwise the caller waits 30
seconds. 30 On failure FlareSolverr is tried when configured and the host is marked unsolvable by
WebView. 31 Without FlareSolverr the failure carries the blocked URL so the UI can offer Open in
WebView. 32 The WebView is detached, stopped and destroyed on the main thread when the wait ends.

**The post-latch jar check was the fourth decider, and it was wrong.** Caught on a fresh VPN exit
where three hosts issued a challenge that never turned interactive, never posted `fail` and never
cleared. All three waited the full 30 seconds, and then the jar check saw a changed `cf_clearance`
and declared the solve a success on that alone. Each retry took a 403, and because it reported
success no `CloudflareBypassIOException` was thrown, so the failure surfaced as a bare HTTP error and
the Open in WebView recovery was never offered. It also pre-empted the clearance deletion above,
since that lives in the branch this one skips. It is now gated on the solver having actually watched
the interstitial go, which is the same predicate the rework's single acceptance rule uses, arrived at
early. Verified on the next fresh exit: a host that went interactive, was pressed and never cleared
reported "Failed to bypass Cloudflare" rather than a 403, while three others cleared normally.

**A page that redirects is already unsupported, upstream included.** `onPageFinished` injects the
interactive listener only when the finished URL equals the one requested, so anything that redirects,
even from apex to www, never gets the listener, never reports `interactiveBegin`, and therefore never
gets pressed: it polls until the caller's wait runs out. Worth knowing before reading a redirecting
host's failure as something the solver did. The rework's state machine should key off the challenge
being seen rather than a URL match, which retires the gap rather than carrying it forward.

### What lands first, and why the order matters

**The arming fix and the clearance deletion**, together with scoping the probe's origin rules to the
request's own origin instead of every frame. All three are correct under any architecture here, and
the first is a stall-level defect on any device whose WebView lacks the isolated-world feature.
Landed: `isSupported` now also requires `JS_INJECTION_IN_FRAME_AND_WORLD`, since
`WebViewCompat.getExecutionWorld` throws without it and the old code called it before its own guard,
swallowed the throw and still reported armed; arming failure now returns false rather than
suppressing the caller's aborts with nothing left to release the wait; the dead page-world fallback
branch is gone rather than made reachable, because a probe in the page's own world is the measured
dead end above; a failed solve deletes `cf_clearance`, taken from mihonapp/mihon#3858; and the probe
is scoped to the page being solved.

**Then an observation pass, changing no behaviour**: forward every `cloudflare-challenge` event rather
than the two currently filtered for, and log each with a timestamp. That answers whether `complete`
reaches the managed-challenge interstitial at all, and whether the in-frame path's 10 to 14 seconds is
the script's ungated click loop fighting itself or the probe being slow to notice a solve that already
happened. The comparison point is the sub-second `interactiveBegin` to `complete` measured on the
Keiyoushi implementation linked from discussion 64.

### What the observation pass measured

Run of four hosts on a fresh exit, key path, every event Cloudflare posted logged alongside the
probe's own transitions.

**`complete` does arrive on the managed-challenge interstitial.** That was the pivotal unknown and the
answer is yes. The full vocabulary seen is `init`, `requestExtraParams`, `translationInit`, `food`
(a roughly per-second heartbeat), `interactiveBegin`, `interactiveEnd`, `complete`.

**Our accept lags Cloudflare's own `complete` by 1.25 to 1.79 seconds, every time.** Four for four:
1.27s, 1.79s, 1.25s, and the fourth never accepted at all. That is time spent after Cloudflare has
already said it is done, and it is the probe's two-readings-at-500ms design showing up as latency
rather than any part of the solve.

**One host received `complete` and was reported as a failure anyway.** `toonily.com` went interactive,
was pressed, and Cloudflare posted `complete`. The probe never once read the page as clear, so nothing
accepted, and the user saw "Failed to bypass Cloudflare" for a challenge that had actually been
solved. The likely mechanism is in the probe's own test: a page counts as challenged when
`input[name="cf-turnstile-response"]` is present or a marker matches, and `turnstile-wrapper` is one
of those markers, so **a site that embeds Turnstile on its own pages can never read as clear.** That
turns moving to `complete` from an optimisation into a correctness fix, and it is the strongest
argument yet for the single acceptance rule.

It also sharpens the gate added above. The post-latch jar check was catching two different cases with
one wrong rule: a clearance from a refused round, and a genuine solve the probe failed to notice.
Gating it on the probe fixed the first and left the second, which is what `toonily.com` hit here.
`complete` is the signal that separates them, and until it is wired in that host fails a solve it won.

**A headless press works, and the blocker is our own instrumentation.** The idea behind the spike was
that a headless solve could use the low-risk key path instead of the injected script. It can. A
WebView that is never attached to a window, laid out by hand and handed the focus callbacks a window
would normally deliver, solved a real managed challenge on a live host in four seconds:
`interactiveBegin`, press, `interactiveEnd|complete`, then the real page title. The same setup solves
the dummy sitekey in about 200 milliseconds. No window, no foreground service, no permission.

That reverses the earlier reading of this question.

### The bisect ran, and its premise was wrong

Four differences between the solver and the harness were named as suspects for why a headless press
failed through one and worked in the other: the `mihon` bridge object `addJavascriptInterface` puts on
every frame, the forced User-Agent claiming a Chrome version this WebView is not, the page-world
listener `evaluateJavascript` injects on page finish, and the probe serialising up to 4MB of
`documentElement.outerHTML` twice a second.

**All four are eliminated, and the question they were asked about does not exist.** The harness was
rebuilt to run a clean detached WebView and then the same one with each difference added, and with all
four at once. Against a real interactive managed challenge on `aquareader.org`, five phases ran and
every one of them solved headless: `interactiveBegin`, press, `interactiveEnd|complete`, the real page
title. Clean, all-four-together, bridge alone, User-Agent alone, page-world listener alone. None of
them suppresses a headless press.

**The solver has no headless key-press path, so it was never the failing side of the comparison.**
`attach` reads the window once, returns false when there is none and the background switch is off, and
chooses keys only when a container exists; otherwise it injects the in-frame script. `pressKeys()` has
exactly one call site, inside that window branch. So "the solver fails headless" has only ever meant
the in-frame script path is slow, and the premise that Cloudflare refuses something the solver does
was unfounded.

**The real contradiction is between the reverted spike and the harness, and it cannot be settled.**
The spike build those three commits describe was never committed, so the code that produced the
failing result does not exist in history. What its own commit body records it doing (hand layout,
`dispatchWindowVisibilityChanged`, `dispatchWindowFocusChanged`, `requestFocus`, `keydown` counted,
`activeElement` moving to the same `DIV`) is what the harness does, and the harness gets
`interactiveEnd|complete` where the spike got neither. One of the two runs is wrong and only one is
still reproducible. Do not spend more on reconciling them.

**The response token is never populated on a managed-challenge interstitial.** Measured across every
phase: `input[name="cf-turnstile-response"]` stayed empty through `complete` and through the
navigation to the real page, while the challenge was plainly solved. So on an interstitial the token
is not a slow acceptance signal, it is an absent one, and any rule built on it reports a solve that
happened as a failure. This is a second, independent route to the same conclusion the accept-lag and
the `toonily.com` false failure reached, and it is the stronger one.

**What it opens.** A headless key press works, which is what the dropped spike wanted. If that holds
up in the solver rather than the harness, the no-window path can press keys like the windowed one, so
the in-frame script, its separate switch and its ungated click loop are retired rather than gated. Not
built, and it is a behaviour change rather than a harness run.

**The harness is the instrument.** `TurnstileHarness`, debug builds only, two rows at the bottom of
Settings -> Advanced -> Networking. It runs a sequence of phases, each a clean WebView carrying one
named difference, reading everything from an isolated world including Cloudflare's own challenge
events, so it needs no page-world script. `Target.Dummy` uses sitekey `3x00000000000000000000FF`,
which forces an interactive widget on any domain and costs nothing to repeat. `Target.Live` takes
candidate hosts and tries them in order until one challenges interactively.

Four things it learned the hard way, all of which cost a run:

- **It must set a `WebViewClient`.** A WebView without one hands navigation to the system and the
  load leaves for whatever browser is installed.
- **Acceptance is `complete` plus the interstitial going, never the token**, for the reason measured
  above. Reading the token alone marked five successful solves as failures and left the harness
  pressing Tab and Space into the real site for the rest of its budget.
- **A phase that pressed nothing measured nothing**, so it aborts the sweep rather than reading as a
  result. A host that is not challenging, and one that clears without turning interactive, both look
  exactly like a failed press if only the end state is read.
- **Whether a host challenges at all changes with the exit and the hour.** One fixed host makes a
  sweep a coin flip: six hosts loaded straight through on one exit and `aquareader.org` challenged
  interactively on the next, with nothing changed but the IP.

**Risk, stated plainly.** This feature has no automated tests and cannot usefully get them: it lives
in a WebView against a live challenge. Its gates pass on broken code, repeatedly, and the record above
lists three separate cases. A state-machine rewrite is where that pattern bites hardest, so it lands
as one reviewed change against the inventory above rather than as an incremental refactor left half
done.

## Open

- **Breadth.** Six hosts, one device, one VPN. Forty-four solves say the mechanism is reliable on
  those; nothing says how it behaves on a host with a different challenge configuration, or on a
  second device. The switch stays off by default until both are answered.
- **Upstream declined the solver, and the script is permanently ours.** mihonapp/mihon#3858 is still
  open, but the solver has been stripped out of it (`0a1f07d`, `0885493`, `a80aaaa`, all titled
  "remove solver"). `AntsyLich` gave the reason in
  [comment 5463601310](https://github.com/mihonapp/mihon/pull/3858#issuecomment-5463601310): "i'm not
  sure about the auto solver. it might get us blocked permanently by cloudflare. the other changes
  seems to be useful." Asked whether that covered the key events or only the script, he answered
  "both" ([comment 5467217824](https://github.com/mihonapp/mihon/pull/3858#issuecomment-5467217824)).
  That is the whole exchange; no mechanism was given and none was asked for. The relay into
  unseensnick/Reikai discussion 64 hardened the hedged "might" into a settled risk, and its author
  confirmed there that it was his own guess.
- **The ban claim was checked against Cloudflare's own documentation and does not hold.** Their
  enforcement is per request and short lived: the bot score is a 1 to 99 likelihood per request on
  Enterprise Bot Management only, the mitigation is chosen by the site owner in a rule rather than by
  Cloudflare globally, the threat score that used to carry IP reputation now always reads 0, JS
  Detections expire after 15 minutes, and Turnstile ephemeral IDs are documented as deliberately not
  unique across customers. Reikai has no Cloudflare account, no declared ASN, no Verified Bots
  listing and no server-side infrastructure; every request comes from a user device and IP, so there
  is no identity to ban. The Perplexity de-listing is structurally inapplicable, since it turned on
  being on the Verified Bots list with declared ranges. The applicable precedent is FlareSolverr,
  advertised as a Cloudflare bypass since 2020 and never banned: detection updates degrade a
  technique until it is patched. The realistic worst case is failed solves and a raised per-request
  score for that user on that host. Confidence: about 97% that no mechanism exists to ban the app,
  95% that the key path is no more detectable than the user tapping the box in a WebView, 85% that
  the in-frame script is detectable in principle and degrades over time.
- **Four places Reikai is ahead of that PR. Do not regress them on a sync.** It ships no preference at
  all, so it would have been on for every user; ours is off by default behind two switches. Its
  `isBypassed` accepts a changed `cf_clearance` alone, which is wrong for the reason below. It
  presses on `interactiveBegin` where we gate on `interactive`, skip a widget that already holds a
  token and hold a 4 second cooldown. And it uses a fixed `__SOLVER__` token where we randomize it
  per injection.
- **Verify upstream comments by fetching the rendered PR page, not the REST API.** Unauthenticated
  `api.github.com` rate-limited from two egress IPs; the page gave exact comment text.
- **The rest of that PR is still expected to land.** With the solver stripped it touches four files:
  `CloudflareInterceptor`, `WebViewInterceptor`, `ForegroundActivity.kt` and `App.kt`; the script
  asset is gone from it. `ForegroundActivity.kt` arrives byte-identical to ours and `App.kt` adds the same
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
