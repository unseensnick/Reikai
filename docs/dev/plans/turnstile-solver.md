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
  from mihonapp/mihon#3858 at `e6de3a7a1`. It names every function it defines with one token so it
  can filter itself out of stack traces, and `injectFallback` renames that token per injection. It
  diverges from the borrow in five places, each recorded under Decisions: three stripped
  `console.log` lines, `"use strict"` (which the borrow gained after we took it, and which closes a
  caller-chain read its own author flagged), the corrected `removeEventListener`, the corrected
  `WeakMap` construction, and the removal of a dead redefine whose failure is load-bearing.
- `core/common/.../util/system/ForegroundActivity.kt`: the activity tracker the solver needs to find
  a window, registered from `App.onCreate` (net-new).
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
- **The two press paths now have a switch each, and the in-frame one defaults off.** They are not
  equally exposed: the key path dispatches real `KeyEvent`s into an attached WebView and patches
  nothing, while the in-frame path rewrites `Error`, `EventTarget`, `window.event` and `attachShadow`
  inside the challenge frame and fires synthetic events with a spoofed `isTrusted`. A user who wants
  global search to work should not have to take the second to get the first. `attach` returns false
  when there is no window and the background switch is off, so nothing arms and the caller keeps its
  own aborts, which is the path that ran before the fallback existed.
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
- **Key events on a detached WebView laid out by hand.** The cheapest possible answer to the headless
  case, since it would need no window, no service and no permission, and the record already said a
  hand-laid-out view renders the checkbox. Measured across four hosts: `measure` and `layout` gave it
  1080x1920, `dispatchKeyEvent` returned true on every press, presses repeated on the cooldown for
  the full thirty seconds, and not one challenge cleared. The same hosts on the same exit cleared in
  about 1.4 seconds when the view was attached. So the earlier finding that layout is not attachment
  holds for keys exactly as it did for touch, and every remaining way to press headlessly needs a real
  window.
  - **`dispatchKeyEvent` returning true means nothing.** It only says the view accepted the event, not
    that the renderer did anything with it. Every one of those thirty seconds of dead presses reported
    `delivered true`. Any design that treats that boolean as evidence a press landed, including the
    parked idea of falling back when a key is refused, is reading a signal that is not there.
  - **Three further runs narrowed why, and it is none of the obvious answers.** With a `keydown`
    counter and a focus, visibility and viewport report inside the isolated-world probe, measured
    against an attached control that cleared four of four: keys **do** reach the renderer detached
    (`keys=1` after every press, and `activeElement` moves from `BODY` to the same `DIV` the working
    run moves to, so Tab is landing). `document.hasFocus()` was the only field that differed, and it
    is fakeable: `dispatchWindowVisibilityChanged`, `dispatchWindowFocusChanged(true)` and
    `requestFocus()` on the detached view turn it true. Page visibility was never the problem, since
    a detached WebView already reports `visible` with `hidden=false`, and the viewport becomes real
    once the view is laid out by hand. With every one of those matching the working control at press
    time, Cloudflare still sent no `interactiveEnd` and no `complete`, on four hosts.
  - **So Tab lands and Space does not activate, for a reason nothing in the document exposes.** The
    remaining candidate is below the DOM: a detached view has no surface, so the WebView never
    composites a frame, and the widget appears to want one before it will accept an activation. That
    is not reachable from the main document, and it is not something a View callback can fake, which
    is what makes the window load-bearing rather than the attachment, focus or visibility semantics
    that stand in for it.
  - **It does not contradict the PR author's finding that an unattached WebView runs the challenge
    fine.** That is true and this run reproduced it: `interactiveBegin` fired on all four hosts while
    detached. Loading and running is not pressing, and his own code draws the same line, injecting the
    script whenever no view group is available and dispatching keys only after `addView`.

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
non-focusable, descendants blocked. 4 Arming precedes `loadUrl`. 5 An arming failure currently still
reports armed, which is the bug the first fix closes.

*Detection.* 6 A probe reports every 500ms. 7 Challenged means a challenge selector matches or the
response-token input exists. 8 The document is omitted while challenged, when there is no body, and
above 4MB. 9 Only main-frame reports are handled. 10 A page still counts as challenged if its title
says "just a moment" or one of six markers appears. 11 `interactiveBegin` sets the interactive flag.
12 `fail` ends the wait only while the solver is not armed.

*Pressing.* 13 No press before interactive. 14 No press while the token input already holds a value.
15 No press within four seconds of the last. 16 Keys are Tab down immediately, then three events at
cumulative random 70 to 160ms gaps. 17 Whether the first key was accepted is logged and nothing else.
18 The in-frame script registers once per solve, scoped to the challenge origin, with its token
renamed per injection, then reloads. 19 A registration failure gives up at once. 20 A 20 second budget
gives up afterwards, and logs only when it released a wait.

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

**The floating-window spike ran, and it does not pay for itself.** The hope was that a headless press
could use the low-risk key path and let the in-frame script be deleted. Two findings close it.

Keys need a real window, not just a layout. That is the dead end recorded above: hand-laid-out but
detached, presses were accepted and did nothing across four hosts for thirty seconds each, where the
same hosts attached cleared in about 1.4 seconds. So a window is required, and with no activity alive
the only ways to get one are an overlay window, a `Presentation` on a virtual display, or an activity
launched from the background. All three route through the same place: Android's permissions guide
classes drawing over other apps as a **special permission**, the kind granted by a user toggle on the
Special app access settings screen rather than at install or by a runtime prompt.

That is the whole trade. Deleting the in-frame script would cost a "Display over other apps" grant, on
top of two switches that are already off by default, for a feature that only runs during headless
library updates. Cloudflare's own documented worst case for the script is a session-scoped clearance
reduction, not anything durable. Asking a manga reader for the permission that powers overlay malware,
to retire a bounded risk behind two opt-ins, is the worse deal.

**The cheaper answer to the same problem is to gate the script rather than replace it.** Its concrete
defect is that it clicks every 100ms for the life of the frame with none of the token check or
cooldown the key path has, which is the behaviour Cloudflare's Precursor is built to notice. Giving it
those guards cuts the detection surface with no permission and no new window, and it is a change to a
file we already own.

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
