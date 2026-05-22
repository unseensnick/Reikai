<!-- SEED: re-run /impeccable document once there's code to capture the actual tokens and components. -->

---
name: Reikai
description: A unified manga + light-novel reader for Android with a distinct, quiet identity.
---

# Design System: Reikai

## 1. Overview

**Creative North Star: "The Reader's Terminal"**

Reikai is a personal Android reader for both manga and light novels. The visual system lives in the overlap between three references: Raycast's dense low-chrome command palette, Tailscale's power-user admin presented cleanly, and Cron's calm calendar grid with monospace numerals. Information-dense at rest, terminal-adjacent in feel, but never aggressive. Chrome serves the content and stays out of the way during reading sessions.

This system explicitly rejects four category reflexes pulled directly from PRODUCT.md: the generic Material 3 default look (Tachiyomi / Mihon / Yokai out of the box), anime-fandom loudness (saturated gradients, character-art accents, MyAnimeList default theme), e-reader skeuomorphism (Kindle leather, shelf metaphors, page-turn animations), and SaaS-cream startup-marketing (pastel gradients, hero illustrations, friendly mascots, identical card grids). Every visual choice should be the deliberate opposite of those.

**Key Characteristics:**
- Quiet at rest; density reveals itself when reached for.
- Cool slate / graphite neutrals tinted toward the accent hue, with a single cool accent.
- Humanist sans for prose, monospace for numerals.
- Motion only in response to state change; never decoration.
- Friendly but quiet icons; no Material-default reflex.
- Cross-format cohesion: manga and novels share identical chrome and hierarchy.

## 2. Colors

The palette is restrained: tinted neutrals plus one cool accent used on no more than 10% of any given surface. Cool slate / graphite neutrals (tinted toward the accent hue with chroma ~0.005-0.01) do the heavy lifting; the accent appears only for state (selected, focused, in-progress), content-type signals, and important affordances.

### Primary
- **[Accent Cool]** ([to be resolved during implementation]): Selected filter chips, primary CTA, focus indicators, unread badge, refresh-in-progress indicator. Cool cyan / teal direction. The single chromatic voice of the system.

### Neutral
- **[Slate Tinted Range]** ([to be resolved during implementation]): Surface, surface-container, surface-container-low, surface-container-high, surface-variant, outline, on-surface-variant. Tinted toward the accent hue at chroma ~0.005-0.01 so the neutrals belong to the same color family rather than reading as pure greys.
- **[Near-Black Tinted]** / **[Near-White Tinted]** ([to be resolved during implementation]): Body text and background. Never pure `#000` or `#fff`; both tinted toward the accent hue. Reading content (chapter text) uses a slightly softer near-black to reduce strain in long sessions.

### Named Rules

**The One Accent Rule.** Color above neutral chroma appears on ≤10% of any visible surface. If a screen has filter chips, status badges, and an FAB all using accent color, two of them lose their hue. Forbidden: accent color used as a decorative tint on cards, list rows, or headers.

**The Tinted-Neutral Rule.** Every neutral carries chroma ~0.005-0.01 toward the accent hue. Pure-grey neutrals are prohibited; they make the palette feel disconnected from the accent. OKLCH is the canonical color space; hex appears in the frontmatter for tooling compatibility.

## 3. Typography

**Body Font:** [Humanist sans to be chosen at implementation; candidates: Inter, IBM Plex Sans, Söhne]
**Mono Font:** [Monospace to be chosen at implementation; candidates: JetBrains Mono, Berkeley Mono, Commit Mono, IBM Plex Mono]

**Character:** A humanist sans for prose, monospace reserved for numerals (chapter counts, unread badges, dates, source identifiers, progress percentages). The mono numerals give the app a power-user / tooling feel without leaning fully into terminal aesthetics; prose stays warm and readable.

### Hierarchy

[Full hierarchy values to be resolved at implementation. Direction: tight scale ratio (~1.2 between steps) supporting the dense register; no oversized hero type since the app has no marketing surface. Reader prose caps at 65-75ch line length.]

- **Title** — page titles ("Library", "Browse"), large enough to anchor a screen but not booming.
- **Subtitle** — section headers within a screen (category names, source names).
- **Body** — descriptions, novel chapter prose, list-row primary text.
- **Label** — list-row secondary text, filter chip labels, button labels.
- **Numeric** — mono family. Chapter counts, unread badges, dates, source IDs, percentages.

### Named Rules

**The Mono-for-Numerals Rule.** Every number on screen uses the monospace family. Counts ("47 unread"), totals ("1100 chapters"), dates ("Mar 14"), chapter numbers in the reader's progress indicator ("Ch. 312 / 1100"), source IDs in debug surfaces. Numbers must not shift width as they tick.

**The No-Hero-Type Rule.** No type larger than the Title role exists in the app. There is no marketing surface, no hero, no above-the-fold dramatic statement. The Title role is the largest. Display-scale type belongs to brand surfaces; Reikai has none.

## 4. Elevation

Flat by default. The system uses tonal layering (surface-container-low / surface-container / surface-container-high) for visual hierarchy rather than shadows. Shadows appear only as a response to a transient state (a sheet rising, a dialog appearing), never as a structural device. On dismissal the shadow goes with the element.

### Named Rules

**The Flat-By-Default Rule.** Surfaces are flat at rest. Cards do not float. List rows do not lift on hover. Bottom sheets and modal dialogs may carry a single subtle scrim shadow while they're open; otherwise the system has no ambient shadow vocabulary.

**The Tonal-Hierarchy Rule.** Depth is signaled by tonal step (surface-container-low → surface-container → surface-container-high), not by shadow. A sheet rising above the screen is one tonal step lighter (or darker, in dark mode) than the screen behind it.

## 5. Components

[Component patterns to be documented after the prototype iterations lock specific affordances. Re-run `/impeccable document` in scan mode once there's real Compose code to extract from. Expected canonical primitives: cover grid item, filter chip, list row, sheet, bottom nav tab, top app bar, search field.]

## 6. Do's and Don'ts

### Do
- **Do** keep accent color on ≤10% of any visible surface.
- **Do** tint every neutral toward the accent hue at chroma ~0.005-0.01.
- **Do** use the mono family for every count, total, date, percentage, and chapter number.
- **Do** respect `prefers-reduced-motion` strictly; every transition bails out cleanly under it.
- **Do** meet WCAG AA contrast in both light and dark schemes; verify before locking colors.
- **Do** keep touch targets at 48dp minimum for every interactive element.
- **Do** pair color with icon, shape, or label for state communication (never color-only).
- **Do** signal depth with tonal step, not shadow.

### Don't
- **Don't** ship the Tachiyomi / Mihon / Yokai default look. No stock Material 3 baseline, no default Roboto, no stock Material icons, no plain card grids. This is the lineage default the redesign exists to escape.
- **Don't** drift toward anime-fandom loud. No saturated gradients, no character-art accents, no MyAnimeList-style decorative chrome, no neon-on-black.
- **Don't** introduce e-reader skeuomorphism. No leather, no shelf metaphors, no fake page-turn animations, no paper-grain textures.
- **Don't** drift toward SaaS-cream startup-marketing. No pastel gradients, no friendly mascots, no hero illustrations, no big rounded "Get Started" buttons, no hero-metric template, no identical card grids.
- **Don't** use side-stripe borders (`border-left` greater than 1px as a colored accent on cards, list items, callouts, or alerts).
- **Don't** use gradient text (`background-clip: text` combined with a gradient background).
- **Don't** rely on glassmorphism as a default. Blurs and glass cards are rare and purposeful, or absent.
- **Don't** use shadows as a structural device. Hierarchy is tonal; shadows are for transient states only.
- **Don't** decorate with motion. Every animation responds to an explicit state change; no ambient orbiters, no autoplay, no scroll-driven choreography.
- **Don't** use `#000` or `#fff` literally. Tint every neutral toward the accent hue.
- **Don't** introduce a Display type scale. The Title role is the largest type in the app.
