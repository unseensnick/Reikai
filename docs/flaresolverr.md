---
title: Cloudflare bypass
titleTemplate: Troubleshooting - Guides
description: Route Cloudflare challenges the in-app WebView cannot solve through a proxy you run yourself.
---

# Cloudflare bypass

_Dev record: [flaresolverr-integration.md](dev/plans/flaresolverr-integration.md). Doc map: [README.md](README.md)._

Some sources sit behind Cloudflare protection the in-app WebView cannot get through.
For those, **Reikai** can hand the request to a bypass proxy running on your own machine, which solves the challenge in a real browser and returns the page.

This is optional and off by default.
WebView stays the primary solver: most challenges never reach the proxy.

::: info Light novels too
Novel sources use the same network client, so a Cloudflare-protected novel site takes the same route.
:::

## Which proxy to run

Three tools speak the same API on the same port, so switching between them means changing only a URL.

::::tabs
== Solverr (recommended)
[Solverr](https://github.com/unseensnick/Solverr) carries both browser engines and picks per challenge, and it keeps sessions.

That means it clears the newer challenges the others struggle with, while follow-up requests still come back in a second or two.
== Byparr
[Byparr](https://github.com/ThePhaseless/Byparr) runs Camoufox, an anti-detect Firefox, and does clear the newer challenges.

It is sessionless, so every request pays a full solve of 15 to 20 seconds. There is no fast follow-up path.
== FlareSolverr
[FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) runs headless Chromium and keeps sessions, so it is the lightest of the three.

It can no longer solve Cloudflare's newer managed and Turnstile challenges.
::::

::: warning FlareSolverr fails quietly on newer challenges
It returns the unsolved "Just a moment..." page while reporting success, so an affected source shows no results or a parse error rather than an obvious failure.
If you see that, switch to Solverr or Byparr.
:::

Pick Byparr or FlareSolverr only if you already run one and it works for your sources.

## Setup

::: tip How to set it up
1. Run one of the proxies on a machine your device can reach, using the commands below.
1. Check it is reachable: open `http://<host>:8191` in a browser on the same network. Solverr and FlareSolverr answer with a welcome page, Byparr serves its API docs at `/docs`.
1. Go to <nav to="advanced"> and, under **Networking**, turn on **Enable FlareSolverr**.
1. Enter `http://<host>:8191` in **FlareSolverr URL**. That field and the test below it are not on the screen at all until the switch is on, so turn it on first.
1. Tap **Test FlareSolverr**. Optional: it fetches a page through the server and tells you whether that worked.
:::

All three listen on port `8191`.

```bash
docker run -d --name=solverr -p 8191:8191 --shm-size=512m --restart unless-stopped ghcr.io/unseensnick/solverr:latest
```

```bash
docker run -d --name=byparr -p 8191:8191 --restart unless-stopped ghcr.io/thephaseless/byparr:latest
```

```bash
docker run -d --name=flaresolverr -p 8191:8191 -e LOG_LEVEL=info --restart unless-stopped ghcr.io/flaresolverr/flaresolverr:latest
```

FlareSolverr also ships a Windows `.exe` in its [releases](https://github.com/FlareSolverr/FlareSolverr/releases).
Each project's own docs cover the rest: upstream proxies, captcha solvers and so on.

Both the switch and a non-blank URL are needed for the proxy to run, so turning the switch off disables it without losing the URL.

## Reaching it away from home

::::tabs
== Mesh VPN (recommended)
A mesh VPN such as [Tailscale](https://tailscale.com/), [Headscale](https://github.com/juanfont/headscale), [ZeroTier](https://www.zerotier.com/) or [NetBird](https://netbird.io/) keeps the proxy reachable from your own devices only.

1. Install the client on both the proxy's machine and your Android device, signed in to the same network.
1. Find the machine's VPN address. On Tailscale that is a `100.x.y.z` address or a name like `unraid.tail-scale.ts.net`.
1. Put `http://<vpn-address>:8191` in **FlareSolverr URL** instead of the LAN address.

Nothing is opened on your router, and the proxy answers only devices signed in to your mesh.
== Public domain
If you already run a reverse proxy (Caddy, nginx, Traefik), point a subdomain at port `8191` and set **FlareSolverr URL** to `https://flaresolverr.example.com`.

Put TLS and authentication in front of it. See the warning below.
::::

::: danger An exposed bypass proxy is an open proxy
Reaching it over a public domain puts an endpoint on the open internet, and anyone who finds it can use your machine to browse through.
Lock it down with basic auth, an IP allowlist or mTLS before pointing anything at it.
:::

## What to expect

| Situation | Typical wait |
|---|---|
| A source WebView can solve | 5 to 8 seconds, then follow-ups are instant. The proxy never runs. |
| First request to a hard source after opening the app | About 42 seconds: WebView tries for 30, then the proxy solves in around 12. |
| Anything after that, same app session | 1 to 3 seconds. The proxy holds the cleared session, and the app stops waiting on WebView for that source. |
| The proxy restarts | The next request quietly makes a new session, about 12 seconds, then back to 1 to 3. No error. |
| Reopening the app | Which sources needed the proxy is not remembered across restarts, so the first request pays the 42 seconds again. |

The fast rows need sessions, so they apply to Solverr and FlareSolverr.
**Byparr is sessionless**, so every request, including paging and tab switches, pays a full 15 to 20 second solve.

## Troubleshooting

**A connection error, or `FlareSolverr returned HTTP 5xx`.**
The proxy is unreachable or has crashed.
Check it is running, the URL is right, and your device can reach it.
The app then falls back to the same "failed to bypass Cloudflare" error you would have seen without it.

**`FlareSolverr error: Captcha detected.`**
The proxy hit a CAPTCHA it cannot solve.
Rare on manga sources, but Cloudflare does escalate.
There is no automatic fix: open the source in <nav to="webview">, solve it by hand, then come back.

**Every request is slow, follow-ups never get fast.**
Sessions are not being reused.
Either the app is being killed between requests, or the proxy is destroying its session each time.
Expected on Byparr, which has no sessions at all.

**A source returns nothing, but the proxy's log says `Challenge not detected!` with a 200.**
FlareSolverr loaded the page, failed to recognise a newer managed or Turnstile challenge, and returned the unsolved page as a success.
There is no fix on its side.
Switch to Solverr or Byparr.
