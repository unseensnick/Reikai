# Cloudflare

## Purpose

Gets a source's requests past a Cloudflare challenge without the user opening a WebView by hand. Three mechanisms sit behind one interceptor: an off-screen WebView that solves the challenge (optionally pressing the interactive checkbox), a WebView that answers the request itself where Cloudflare lets it in without a challenge, and an optional bypass server the user runs (FlareSolverr, Solverr or Byparr). When all of them fail, the error carries the blocked URL so the UI can offer Open in WebView on the page that is actually challenged.

## How it works

### Detection and the per-host lock

Every source request, manga extensions, novel APKs and LN plugins alike (`LnPluginHost` uses `NetworkHelper.client`), passes `CloudflareInterceptor`, an OkHttp application interceptor built in `NetworkHelper`. Nothing differs by content type; the WebView fetch's security rules exist because an LN plugin picks its own method and headers.

A response is a challenge when `isCloudflareChallenge` holds: `cf-mitigated: challenge` and a `Server` of `cloudflare` or `cloudflare-nginx`. That one rule is read by `shouldIntercept`, by the WebView fetch, and by the redirect capture.

`WebViewInterceptor.intercept` (the base class) holds one `ReentrantReadWriteLock` per host. Ordinary requests run under the read lock, so they do not queue behind each other but do wait out a solve. A challenged request takes the write lock, and first checks `isBypassed`: if the host's `cf_clearance` (the nonce, `getNonce`) changed while it queued, a sibling solved it, so it closes its challenge response and retries. Otherwise it calls the subclass. The subclass returns null for "solved, retry normally", and the base class runs that retry outside the lock.

### The order of attempts

`CloudflareInterceptor.intercept` takes `response.request.url` as the challenge URL (the page after any redirect, since `chain.request()` predates it), deletes `cf_clearance` on both hosts, then:

1. **Bypass server first, for a known host.** With the server in use (`FlareSolverrClient.isActive`: switched on and an address saved) and the host already marked (`shouldSkipWebView`), the request goes straight to the server.
2. **WebView fetch first, for a known origin.** With the server not in use and the origin remembered (`WebViewFetcher.serves`), the WebView fetch answers. Served returns; a fetch that comes back challenged forgets the origin and falls through to the solve.
3. **The WebView solve** (`resolveWithWebView`). Success returns null and the retry rides on the new clearance.
4. **After a failed solve**: with the server not in use, the WebView fetch is tried once and, if served, the origin is remembered (`markServed`); with it in use, the host is marked WebView-unsolvable (`markWebViewUnsolvable`) and the server answers.

A failed bypass throws `CloudflareBypassIOException`, whose `url` is the blocked page; `cloudflareBlockedUrl` walks the cause chain for it and `EntryBrowseCatalogue` hands it to Open in WebView. A bypass-server failure is a plain `IOException`, not this one.

### The WebView solve

`resolveWithWebView` builds a WebView with the request's User-Agent, loads the challenge URL and blocks the OkHttp thread on a 30-second latch. A page-world listener injected at page finish forwards Cloudflare's `cloudflare-challenge` messages to the `mihon` JavaScript bridge: `interactiveDetected` (upstream's abort), `challengeFailed`, and `challengeEvent`, which logs every event and feeds an armed solve that has no probe. Without the solver, the latch is released by a fresh `cf_clearance` at page finish, an `interactiveBegin`, a `fail`, a dead renderer, a main-frame HTTP error that is not a challenge, or the page finishing with no challenge seen.

A failed solve deletes `cf_clearance` on the requested and challenge hosts (`AndroidCookieJar.remove` expires it on every parent domain at `/`, where Cloudflare stores it), so a queued sibling does not trust a refused round. The WebView is torn down on the main thread in a `finally`, solve timers first. These Reikai additions run whether or not the solver is on.

### The Turnstile solver

Off by default (`enable_turnstile_solver`). When on, `TurnstileSolver.attach` arms before `loadUrl` and returns a `Solve`, or null when it did not arm.

- **Placement.** With an activity (`ForegroundActivity.current`, the last resumed one, still returned while backgrounded) the WebView joins its decor view, sized to the window, shifted left by its own width and non-focusable. With none (a library update in a process that never created an activity) it is laid out by hand at 1080x1920 and handed the window visibility and focus callbacks. That path arms only with `enable_turnstile_background_solver`, nested under the first switch.
- **Reading the page.** Where the installed WebView supports an isolated world (`DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER`, `JS_INJECTION_IN_FRAME_AND_WORLD`), the `WATCH` script runs at document start in its own world, scoped to the challenge origin, and reports every 500ms plus immediately on `interactiveBegin` or `complete`: whether the page still looks challenged (challenge selectors, the token input, no body yet, Solverr's markers or a "just a moment" title, all judged in the script), the response token, and the events. It accepts events only from `https://challenges.cloudflare.com` or the page's own origin. Without an isolated world there is no probe, and the solve runs on the events the `mihon` bridge forwards.
- **Pressing.** `SolveMachine` presses only in the `Interactive` phase, never while the token input holds a value, and at most once per `PRESS_COOLDOWN_MS` (4s). A press is Tab down, then Tab up, Space down, Space up at random 70 to 160ms gaps, posted through the solve's main-looper `Handler`.
- **Accepting.** Phases only move forward: `Watching`, `Interactive`, `Verified`, `Accepted`. `complete` from Cloudflare, or two consecutive clear probe readings, reach `Verified`; the solve then asks the caller for a clearance every 250ms for five seconds (and on each probe tick), and the caller accepts only a `cf_clearance` that differs from the pre-solve one. After the latch, a changed clearance is still accepted if the solve reached `Verified`.
- **Giving up.** `SOLVE_BUDGET_MS` (20s) starts at arming and is reset once, by the first press. Running out counts the latch down. While armed, `interactiveBegin` no longer aborts, and `fail` aborts only while the solve is still `Watching`, read on the main thread so an in-flight `interactiveBegin` wins.

### The WebView fetch

For a site that challenges OkHttp but lets the WebView in with no challenge, so no clearance exists to replay. `WebViewFetcher` keeps one detached WebView per origin (scheme, host, port) holding a blank page loaded with `loadDataWithBaseURL` on that origin, so a fetch is same-origin and carries the shared cookie store. The page runs one fixed script (`FETCH_PAGE`). A request reaches it only as a JSON message (`webViewFetchMessage`) over `addWebMessageListener` scoped to that origin and the main frame; the answer comes back as a head, base64 body chunks, then an end, each routed to the request's own queue by id.

Redirects: a GET or HEAD whose followed fetch fails is asked again with `redirect: 'manual'`; an opaque redirect sends a separate WebView (`redirectTarget`) to learn the target from `shouldOverrideUrlLoading`, stopping at the first hop. A hop to another origin is rebuilt from `chain.request()` (`webViewFetchFollowUp`) and sent through `chain.proceed`, so later interceptors run as on OkHttp's own redirect; every served answer then passes `served`, which solves a challenge on that hop's site (`webViewFetchChallengedHop`) and retries it once.

Limits: at most four pages (least recently used idle one evicted, each torn down after a minute idle), 64 remembered origins, a 32 MB body, a 90-second fetch cancelled with its call, five hops. Unsupported (falls back to failing): a method outside the allow-list, a one-shot or duplex body, a hand-set `Cookie` header, a cross-site `Referer` (sent as none). Interceptors after this one see only the request, so a progress listener or `Range` resume does not apply.

### The bypass server

`FlareSolverrClient` owns everything about it. `resolve` posts `request.get` or `request.post` to `<address>/v1` and builds the response from the solution: the page the server's browser fetched is served directly (`buildResponseFromFlareSolverr`), with a browser's JSON viewer unwrapped back to raw JSON (`unwrapBrowserJsonViewer`). A POST body is re-encoded as url-encoded form fields (`flareSolverrPostData`), multipart text fields included. The site's own cookies, minus Cloudflare's, travel with the command only over a private channel (`isPrivateChannel`).

One server session is shared and recreated once if the server says it is gone. The root banner is probed first; a server that does not say "FlareSolverr" (Byparr) is driven sessionless for the rest of the run, unless the login changes. After a solve, `cookiesToKeep` cookies are saved to the jar, the host is remembered so its next challenge skips the WebView, and the solver's User-Agent is pinned for the host. `pinFlareSolverrUserAgents`, a network interceptor, applies the pin to every request to that host, retries from inside `CloudflareInterceptor` included, but only while `isActive` holds.

Settings live at the end of Advanced's Network group (`bypassPreferenceItems`): the two solver switches, the server switch, its address, a Clear row, the sign-in dialog and a Test row that solves google.com sessionless and reports a typed `FlareSolverrTestFailure`. The sign-in is basic auth for a proxy in front of the server: `flareSolverrLoginFor` adds it only to requests for the saved address's scheme, host and port, and refuses (`FlareSolverrLoginRefusedException`) unless that channel is https or the user's own network, Tailscale's `.ts.net` counting only off its Funnel ports. The server client follows no redirects and reads for 90s, above the 60s `maxTimeout` each command asks for.

## Key files

- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt`: `intercept`, `resolveWithWebView`, `served`, `isCloudflareChallenge`, `CloudflareBypassIOException`, `cloudflareBlockedUrl`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/WebViewInterceptor.kt`: `locksByHost`, `getNonce`, `isBypassed`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareClearance.kt`: `clearanceFor`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/TurnstileSolver.kt`: `attach`, `Solve`, `pressKeys`, `WATCH`, `forceHeadless`, `forceNoWatch`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/SolveMachine.kt`: `SolveMachine`, `SOLVE_BUDGET_MS`, `PRESS_COOLDOWN_MS`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/WebViewFetcher.kt`: `fetch`, `FETCH_PAGE`, `collect`, `redirectTarget`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/WebViewFetch.kt`: `webViewFetchMessage`, `webViewFetchResponse`, `decodedBodyHeaders`, `webViewFetchFollowUp`, `webViewFetchChallengedHop`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/FlareSolverrClient.kt`: `resolve`, `test`, `isPrivateChannel`, `flareSolverrCommand`, `unwrapBrowserJsonViewer`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/FlareSolverrAuth.kt`: `flareSolverrLoginFor`, `carryFlareSolverrUserInfo`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/FlareSolverrUserAgentPin.kt`: `pinFlareSolverrUserAgents`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/AndroidCookieJar.kt`: `remove`, `scopesOf`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkPreferences.kt`: the bypass server and solver preferences.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/util/system/ForegroundActivity.kt`: `current`.
- `app/src/main/java/reikai/presentation/settings/BypassPreferences.kt`: `bypassPreferenceItems`.
- `app/src/main/java/mihon/core/migration/migrations/FlareSolverrCredentialsMigration.kt` and `app/src/main/java/reikai/data/backup/AppPreferenceCarry.kt`: credentials moved out of the address.
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateWorker.kt`: `startDelayed`, debug only.

## Invariants and traps

- **No solver timer may use `View.postDelayed`.** A view never attached to a window parks its posts until attach, so on the no-window path every key after the first Tab silently never fired; use the solve's main-looper `post`.
- **Everything keys off the challenge URL, not the requested one.** Scoping the probe or the page-finish check to the pre-redirect origin leaves the solve watching a document the page never has, and a working host stalls for 30 seconds.
- **A fresh `cf_clearance` alone is not proof while the solver is armed.** Cloudflare hands one out on a round it refused, and the retry gets a 403. Accept only with `Verified`.
- **A failed solve must really delete the clearance.** The sibling shortcut in `isBypassed` trusts a changed clearance, which is safe only because `AndroidCookieJar.remove` expires every parent-domain scope first.
- **The sibling shortcut closes the challenge response itself.** It returns before the subclass, which is what closes it elsewhere; left open, OkHttp refuses the retry on the same call.
- **The per-host lock is the only per-host dedup for the bypass server too.** Removing it means restoring a guard around `FlareSolverrClient.resolve`, or concurrent requests each start a server solve.
- **Only the first press extends the deadline.** The cooldown is shorter than the budget, so extending on every press lets a pressing solve outrun its give-up forever.
- **Nothing from a request is ever script text in the WebView fetch.** Method, headers and body travel as JSON data, the method is on a fixed list, and the page drops itself if a second page load ever starts.
- **The User-Agent pin answers only while the server is in use.** A pin left standing with the server off sends the WebView's retry under a User-Agent the clearance was not bound to. Never store the solver's User-Agent as the app default: every WebView then announces a desktop browser and Cloudflare re-challenges the mismatch.
- **A pinned app-wide User-Agent invalidates a solver test.** Check Settings, Advanced, Default user agent string before trusting a run.
- **A nested switch is absent, not greyed, while its parent is off**, since the settings DSL wraps each row in `AnimatedVisibility`.
- **A solve holds one of global search's five source threads for its duration.**

## Decisions

- **WebView first, the bypass server as fallback.** Sites the WebView can clear never pay a server round trip; an in-memory per-host mark skips the WebView on repeat hosts. Void if the server became the faster path for every host.
- **The server's page is served, not its cookies replayed.** On the strict tiers it was built for, Cloudflare binds `cf_clearance` to TLS and `__cf_bm` fingerprints OkHttp cannot reproduce. Void if OkHttp could replay those clearances.
- **A WebView solve is followed by an ordinary retry, not by serving the WebView's page.** A genuine interactive solve's clearance replays through OkHttp on every host measured. Void on a host where it does not.
- **The WebView fetch runs only where a request would otherwise fail, with no setting.** It needs no clearance, which is the case the solve cannot cover. It is skipped while the server is in use.
- **The solver is off by default, with a separate switch for solving with no app screen.** A user who never opens the setting gets upstream's behaviour, and reaching a challenged host with nothing on screen is the user's call.
- **The press is Tab then Space at the WebView.** Real key events carry a true `isTrusted` and need no coordinate or DOM patching, so a widget restyle cannot move the target. Void if Cloudflare starts refusing keyboard activation.
- **The probe runs in an isolated world, and an old WebView gets an events-only solve rather than none.** The events a solve turns on reach the page-world bridge anyway; only the markup fallback is lost.
- **`complete` is the primary acceptance signal.** A site that embeds Turnstile on its own pages never reads clear to the markup test, and the token is never filled on an interstitial.
- **Press cadence is ported from Byparr and Solverr**: a 4-second cooldown and no press while a token is present, which both measured as restarting verification.
- **`ForegroundActivity` still returns a backgrounded activity.** The windowed path cleared challenges with the app backgrounded where the no-window path cleared none without a foreground service.
- **The proxy login is basic auth in UTF-8, in two private preferences, added by an interceptor.** UTF-8 matches a proxy configured through a web interface; private keys keep it out of a default backup; the interceptor covers the banner probe, whose 401 would latch the client sessionless.
- **Credentials typed into the address are refused, and old ones are moved.** The migration and the backup restorer both run `carryFlareSolverrUserInfo`, since a fresh install marks migrations done without running them; a restored address on another origin clears the saved login rather than inheriting it.
- **The debug rows are permanent.** `forceHeadless`, `forceNoWatch` and `startDelayed` reach paths a current device cannot otherwise enter.
- Open: on a WebView with no isolated world the event listener is injected at page finish, so an `interactiveBegin` posted before that is lost and the solve runs to its deadline. Injecting at document start changes the solver-off path too.

## Upstream divergences

`// RK` islands in Mihon's files: `CloudflareInterceptor` (the whole bypass order, the solver hooks, the extra bridge methods, clearance deletion, the blocked URL), `WebViewInterceptor` (the per-host lock), `NetworkHelper` (the server client and the User-Agent pin), `NetworkPreferences`, `AndroidCookieJar` (`remove` scopes, `saveCookieString`), `WebViewUtil.setUserAgent` (a second call rewrites the Chrome brand too), `App` (`ForegroundActivity.register`), `LibraryUpdateWorker.startDelayed` and `SettingsAdvancedScreen` (the bypass rows). `ForegroundActivity.kt` is not in Mihon.

The lock, `getNonce`, `isBypassed`, the `fail` abort, the dead-renderer abort and the clearance deletion come from mihonapp/mihon#3858, which is unmerged and recorded under "Pending, needs planning" in [upstream-sync.md](../upstream-sync.md). When it lands, take its shape but keep ours where it differs: its `isBypassed` accepts a changed clearance as proof of a solve, and it carries no preference, so the solver would be on for everyone. It also presses on `interactiveBegin` with no token check or cooldown, and uses a fixed `__SOLVER__` token where ours is randomized per injection.

**Removal recipe for the per-host lock.** The lock is the one piece taken on an unmeasured benefit: solve times were indistinguishable with and without it, and its advantage is confined to a failed solve's siblings. If it is judged not worth the divergence (for instance if mihonapp/mihon#3858 is abandoned), remove it by hand rather than by revert: delete `locksByHost` and the read/write wrapping in `WebViewInterceptor.intercept`, drop `getNonce`, `isBypassed` and the `nonce` parameter, return `Response` instead of `Response?`, and restore a one-solve-per-host guard (a `ConcurrentHashMap` of `CompletableFuture`) around `resolveWithWebView`. In the same change restore the equivalent guard around `FlareSolverrClient.resolve`, whose own per-host dedup was deleted once the lock made it unreachable.

## Extending

- **A new bypass mechanism**: add a step to `CloudflareInterceptor.intercept`'s order inside the `// RK` island, behind `isActive` or its own predicate, and end a failure in `CloudflareBypassException` so Open in WebView still appears.
- **A new solver timing rule**: change `SolveMachine` and add a case to `SolveMachineTest`; keep the Android wiring in `TurnstileSolver.attach`.
- **A new request shape for the WebView fetch**: extend `webViewFetchMessage` and the fixed script together, as data, and pin it in `WebViewFetchTest`.
- **A new server failure case**: add it to `FlareSolverrTestFailure`, its string in `bypassPreferenceItems`, and `FlareSolverrTestFailureTest`.

## Tests

`CloudflareClearanceTest`, `AndroidCookieJarTest`, `SolveMachineTest`, `WebViewFetchTest`, `FlareSolverrAuthTest`, `FlareSolverrCommandTest`, `FlareSolverrCookieTest`, `FlareSolverrEndpointTest`, `FlareSolverrJsonViewerTest`, `FlareSolverrRedirectTest`, `FlareSolverrTestFailureTest`, `FlareSolverrUserAgentPinTest` (all under `:core:common`), plus `FlareSolverrCredentialsMigrationTest` and `PreferenceRestorerTest` in `:app`. The WebView halves (arming, the probe, key delivery, the fetch page) need a live challenge and are verified on device only.

Run one class with `./gradlew :core:common:testDebugUnitTest --tests "<FullyQualifiedClassName>"`, or `:app:testDebugUnitTest` for the app classes.

## Related

- User doc: [flaresolverr.md](../../flaresolverr.md).
