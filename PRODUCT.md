# Product

## Register

product

## Users

Personal-first. Reikai is built primarily for its developer's daily use as a unified manga + light-novel reader on Android. Anyone who finds the repo on GitHub is a bonus user, not a primary consideration. Design decisions lean toward the developer's taste without needing to please a committee or a hypothetical broad audience.

Context: phone-first reading, mix of sessions throughout the day (commute, evening, lying in bed). Some tablet use. Library size in the dozens to low hundreds, not thousands.

## Product Purpose

A unified manga + light-novel reader for Android in the Tachiyomi / Mihon / Yokai lineage, with a distinct identity. Three things differentiate it from the lineage:

1. **Two content types as first-class citizens.** Manga and light novels share one library, one chrome, one interaction language. Content-type differentiation surfaces through metadata, not decoration.
2. **Power features the lineage doesn't have.** Multi-source merge / unmerge groups (a novel followed across royalroad + novelbin treated as one entry), per-category sort overrides, content-type filter chip on the unified library.
3. **A visual identity that doesn't read as "generic Android Material 3 utility."** The lineage's default look is functional but undistinctive; Reikai chooses every visual element deliberately to feel like a curated personal tool, not a stock template.

Success looks like: opening the app on any device and immediately recognizing it as Reikai. Reading sessions feel calm and uninterrupted. Finding a specific entry across a few hundred manga and novels takes one or two taps, not a navigation tree dive.

## Brand Personality

**Quiet, dense, deliberate.**

- **Quiet** is the dominant mode. Restrained palette, minimal chrome at rest, no decorative gradients, no SaaS-cream pastels, no anime-fandom loudness. The app fades into the background while reading.
- **Dense** is the information-architecture goal. Library grids show many entries at once without feeling crowded. Power-user affordances (filters, sort, merge groups, categories) are visible to anyone who reaches for them but never yelling at users who don't.
- **Deliberate** is the craft signature. Every spacing value, every color use, every icon choice is considered rather than reflexive. No decoration for decoration's sake. No "looks cool" that doesn't carry weight.

Reference register: the common ground between Things 3 (quiet hierarchy), Raycast (dense low-chrome), Cron (calm density, monospace numerals), and Tailscale admin (power surfaces presented cleanly). Friendly-but-quiet icons. Generous spacing. Considered hierarchy. Mindful color use.

## Anti-references

Reikai should explicitly NOT look like:

- **Tachiyomi / Mihon / Yokai default.** Generic Material 3 baseline, default Roboto everywhere, standard Material icons, plain card grids. This is the lineage default the redesign exists to escape.
- **Anime-fandom loud.** MyAnimeList, AniList default theme, anime fan-site aesthetics. Saturated gradients, decorative chrome, character-art accents, neon-on-black. The content is anime / manga / light novel; the chrome doesn't have to be.
- **E-reader skeuomorphism.** Kindle leather, Apple Books shelf metaphors, fake page-turn animations, paper-grain textures. The app is digital; the UI should be digital.
- **SaaS-cream / startup-marketing.** Pastel gradients, hero illustrations, friendly mascots, big rounded "Get Started" buttons, hero-metric template, identical card grids. The Linear marketing site, not the Linear product UI.

## Design Principles

1. **Quiet first.** Default to restraint. Power features earn their visual weight only when they're being used. Defaults are calm; specialized affordances reveal themselves when reached for.
2. **Density without noise.** Information-dense layouts that look calm at rest. Raycast/Tailscale energy at rest, not at peak. Many entries visible without feeling crowded.
3. **Friendly but quiet icons.** Iconography is recognizable and warm but never decorative. Never the loudest thing on screen. The Material Symbols default is unspecific; pick a single icon family that fits the register (Lucide, Phosphor, or a custom subset) and commit.
4. **Consider every color use.** Color is rare and load-bearing. When color appears it means something specific (state, content-type discriminator, alert). Neutrals do most of the work. No decoration-only chroma.
5. **Cross-format cohesion.** Manga and light-novel surfaces share the same chrome, hierarchy, and interaction language. Content-type differentiation happens through metadata (a quiet content-type badge, a filter chip), never through differently-styled UI.

## Accessibility & Inclusion

- **Respect `prefers-reduced-motion` strictly.** Any animation bails out cleanly under the OS setting. No essential information communicated by motion alone. Default motion is purposeful (state transitions, focus changes), never decorative.
- **WCAG AA contrast minimum.** All text passes AA against its background in both light and dark schemes. UI affordances meet 3:1 minimum. Verify with a contrast checker before locking colors.
- **Touch targets 48dp minimum.** Material 3 baseline. Important for one-handed phone use; the prototype honors this throughout. Don't shrink below the threshold in the name of density.
- **No color-only state communication.** Even though color-blindness wasn't explicitly flagged as a requirement, it falls naturally out of "consider every color use." State always pairs color with icon, shape, or label.
