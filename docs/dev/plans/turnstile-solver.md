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
challenge markup is present, the response token, the widget's rect and the page HTML. It runs in a
world the challenge's own scripts cannot see, because Byparr measured that scripts in the page's own
world against a live challenge make Cloudflare reissue it. Kotlin decides from those reports and
presses the widget with a real `MotionEvent`, which carries a true `isTrusted` and needs no DOM
patching at all.

Requests to one host dedupe onto a single solve, and the challenge is only considered over once the
page has stopped looking like an interstitial across two readings and a fresh `cf_clearance` is in
the jar. The normal retry then runs, exactly as upstream's path does.

Off by default, behind `Settings -> Advanced -> Networking`. With it off, the code path is upstream's
unchanged.

## Key files

- `core/common/.../network/interceptor/TurnstileSolver.kt`: attach/detach, the isolated-world probe,
  the press cadence, the interstitial test, per-host dedup (net-new).
- `core/common/.../util/system/ForegroundActivity.kt`: the activity tracker the solver needs to find
  a window, registered from `App.onCreate` (net-new).
- `CloudflareInterceptor`: arm, detach, do not trust a bare clearance while armed, and the
  cleared-without-a-report fallback. A `// RK` island on Mihon's file.
- `NetworkPreferences.enableTurnstileSolver`, `SettingsAdvancedScreen`: the switch.

## Status

Prototype, device-verified on the Fold over a VPN across repeated cold starts. Six challenged hosts
in one global search clear in about 3.4 seconds with no failures: `aquareader.org`, `comix.to`,
`comick.live`, `mangafire.to`, `www.natomanga.com`, `toonily.com`.

## Decisions & tradeoffs

- **Attachment is the whole feature, measured three ways.** Detached, the widget never lays out and
  no checkbox renders; no synthesized touch reaches the renderer, measured as zero pointer events
  across a full solve; and the DOM patching that stands in for real input gets the challenge
  reissued rather than cleared. Attached, all three problems disappear and every patch drops out.
- **Focus turned out not to matter, and taking it was a bug.** An early design spoofed
  `document.hasFocus()` because the synthetic-event press needed it. With a real `MotionEvent` it is
  irrelevant: 28 consecutive presses solved with focus already false. Worse, holding focus made the
  soft keyboard reopen once per solve, so the view is now non-focusable.
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
  it has not accepted, which ended three test solves early with a 403 on the retry. While the solver
  is armed the cookie alone never counts as success; the page must also be past the interstitial, by
  Solverr's `looks_like_challenge_html` markers.
- **Serving the WebView's own page was built and reverted.** It was written for a problem that did
  not exist: a genuine solve's clearance replays through OkHttp fine on every host measured, which
  contradicts the strict-tier reasoning in the FlareSolverr record. That reasoning still holds for
  the hosts that record was written about; it does not generalize to interactive challenges.
- **No window, no solver.** A challenge raised with no activity on screen, such as a background
  library update, is not solved and behaves exactly as before.

## Open

- **A source can be left spinning forever in global search.** Its search returns 200 and the row
  never leaves `Loading`, because upstream updates the row only inside `if (isActive)` in both the
  success and error branches, with no fallback. Not caused by this feature, but a solve takes seconds
  where a challenged source used to fail in milliseconds, which widens the window. Unpinned: the
  cause of the cancellation is not yet known.
