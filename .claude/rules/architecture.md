---
alwaysApply: true
---

# Project architecture

## Presenter pattern (legacy) vs Compose + Voyager (new)

Each non-Compose screen has a `*Controller` (Conductor, view) + `*Presenter` (business logic). New screens use **Compose + Voyager**. When fixing legacy code, follow the existing pattern — don't half-migrate a screen mid-fix.

## Settings UI — two-screen pattern

A settings section under migration has two parallel implementations:

- **Compose screen** in `app/.../yokai/presentation/settings/screen/` — the target stack.
- **Legacy Conductor controller** in `app/.../ui/setting/controllers/legacy/` — kept as a long-press fallback during the soak period after the section flips to Compose-default.

**Flipped (Compose-default):** Security, Data and storage, Advanced. Tap reaches Compose; long-press toasts and falls back to legacy.

**Not flipped:** General, Appearance, Library, Reader, Downloads, Browse, Tracking, About — legacy only.

**When adding or editing UI for a flipped section**, changes go to the Compose screen. The legacy controller stays alive (still indexed by `SettingsSearchHelper`) — don't delete it without solving Compose-side search.

**When adding or editing UI for a not-yet-flipped section**, changes go to the legacy controller. The Compose screen, if any, is invisible to users until the section is migrated and flipped.

## Preferences

Access through `PreferencesHelper` (wraps a custom datastore). Keys live in `PreferenceKeys`. **Never use raw `SharedPreferences` directly.**

## Coroutines

Launch with the `launchIO` / `launchUI` extension helpers — not raw `launch(Dispatchers.IO)`. Reactive state via `StateFlow` / `SharedFlow`; presenters collect flows and push state to controllers.

## Multiplatform (KMP)

`domain/` and `data/` use `commonMain` / `androidMain` source sets. Pure logic in `commonMain`; Android-only APIs in `androidMain`. Don't import Android types into `commonMain` code.

## Dependency injection

Koin 4 (modules under `yokai/core/di/`) is the active DI system. Older code uses Injekt — leave it; register new dependencies in Koin modules.
