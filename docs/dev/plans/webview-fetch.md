# WebView fetch for Cloudflare without a clearance

## Goal

A site behind Cloudflare that challenges the app but lets its WebView straight in loads without a FlareSolverr server: the WebView answers the request itself.

## Why

Some sites (NovelUpdates is the measured one) challenge every request from OkHttp and let the Android WebView load the same page with no challenge at all. With no challenge there is no `cf_clearance`, so the in-app solve has nothing to hand back: the Turnstile solver reads the page clear, the accept check finds no fresh clearance, and the request fails. Before this, only a FlareSolverr server got those sites to load.

What was ruled out, on the emulator:

- **Replaying a clearance** does not apply: the jar holds none after the WebView visit, checked by cookie name for 25 seconds.
- **Cronet** (Chromium's network stack, bundled) is challenged exactly like OkHttp, over HTTP/2 and HTTP/3, with the WebView's user agent or the app's, with or without Chrome's navigation headers. The fingerprint is not what Cloudflare decides on here.
- **A fetch from a blank page** on the site's origin, in a detached WebView that never loads a site page, gets 200s with the user's sign-in cookies, in the foreground and with the app in the background. That is what this builds on, and one shipping extension relies on the same shape.

The turnstile solver record declined serving the WebView's page because a genuine solve's clearance replays through OkHttp. That premise holds only when a clearance exists, so the decline does not cover this case.

## Approach

When the WebView solve fails and FlareSolverr is not configured, the interceptor tries the request once through a WebView. If the answer is not itself a Cloudflare challenge, the origin is remembered, and later challenges on it go straight to the WebView fetch without the solve. If the WebView gets challenged too, the origin is forgotten and the usual solve runs, so "Open in WebView" still appears when nothing works. With FlareSolverr configured nothing changes: it stays the fallback, as before.

Each origin (scheme, host and port) gets one detached WebView holding a blank page loaded with `loadDataWithBaseURL` on that origin, so a fetch from it is same-origin and carries the shared cookie store, HttpOnly cookies included. The page runs one fixed script. A request reaches it only as a JSON message over `WebViewCompat.addWebMessageListener`, scoped to exactly that origin and the main frame, and the answer comes back the same way: a head with status, headers and final URL, then the body in base64 chunks, then an end. Nothing from a request is ever part of script text.

A redirect to another site cannot be read from a fetch, since the target does not allow it. A GET or HEAD whose fetch fails is asked again with `redirect: 'manual'`; an opaque redirect sends a separate WebView to the URL to learn the target from `shouldOverrideUrlLoading`, stopping at the first hop so the target never loads. OkHttp then requests the target itself, rebuilt from the request this interceptor was handed so the interceptors after it add their own headers and decode their own encodings, as they do on OkHttp's own redirect.

## Key files

- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/WebViewFetcher.kt`: the pages, the fixed script (`FETCH_PAGE`), the message loop (`collect`), the redirect capture (`redirectTarget`).
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/WebViewFetch.kt`: the rules that need no WebView (`webViewFetchMessage`, `webViewFetchResponse`, `webViewFetchFollowUp`, `isWebViewFetchChallenged`), pinned by `WebViewFetchTest`.
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt`: the wiring inside the `// RK` island of `intercept`.

## Status

Shipped on `feat/0.4.0`. Verified on the emulator against NovelUpdates with the challenge forced on: browse, search, the series page, the chapter-list POST (multipart and url-encoded), and a chapter behind its external redirect.

## Decisions & tradeoffs

- **Security, from reviews before and after the build.** An LN plugin chooses its method and headers, so they travel as data and the method must be on a fixed list; splicing them into script would let a plugin run code in a site the user is signed in to. The fetch page accepts no navigation and is dropped if a second page ever starts, so no site document or service worker can sit behind the channel. A redirect is followed only for GET and HEAD, http(s) only, with credentials dropped across origins and the client's HTTPS-to-HTTP rule honoured; a request carrying credentials, or on a client that follows no redirects, never lets the browser follow one itself. Each request has its own reply queue, so two fetches sharing a page cannot take each other's messages. Logs name the host only.
- **The redirect-capture WebView runs JavaScript.** With it off, Cloudflare served that navigation a challenge page instead of the redirect. The page it runs is the site's own, as in the existing solve, which also loads it with JavaScript on; this WebView carries no bridge, allows no navigation but the challenge reloading the page asked for, and stops before the target loads.
- **Caps.** At most four live pages, the least recently used idle one evicted first, each torn down after a minute idle; 64 remembered origins, oldest dropped first; a 32 MB body, checked against `Content-Length` first; a 90-second fetch, cancelled with its call.
- **What the fetch cannot carry.** A body OkHttp can only send once, and a method outside the list, fall back to failing as before. Interceptors after this one see only the request, not the WebView's answer, so a response rewrite or a progress listener downstream does not apply to it; a resumed download restarts, because `Range` is added at the network layer. Requests to a remembered host run one at a time, under the base class's per-host lock. A `Cookie` header the source set by hand is not sent: a page cannot set that header, and writing it into the WebView's cookie store would keep a cookie OkHttp never stored. A `Referer` on another site than the request is left out, since a page can name only its own origin and would otherwise send its own address instead; a request with none sends none. A fetch the browser followed to another site that allowed the answer is taken as a redirect there, so its body is never served under the original address.
- **No setting.** It only runs where the request would otherwise fail.
