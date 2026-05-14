---
alwaysApply: true
---

# Project architecture

## Presenter pattern (legacy) vs Compose + Voyager (new)

Each non-Compose screen has a `*Controller` (Conductor, view) + `*Presenter` (business logic). New screens use **Compose + Voyager**. When fixing legacy code, follow the existing pattern — don't half-migrate a screen mid-fix.

## Settings UI — two-screen pattern

Every settings section has two parallel implementations:

- **Legacy Conductor controller** in `app/.../ui/setting/controllers/legacy/` — **what users see by default**.
- **Compose screen** in `app/.../yokai/presentation/settings/screen/` — experimental, not yet the default.

**When adding or editing settings UI, changes go to the legacy controller.** The Compose screen is invisible to users until that section's migration is complete. Advanced settings specifically: normal tap → legacy; long-press → Compose (experimental).

## Preferences

Access through `PreferencesHelper` (wraps a custom datastore). Keys live in `PreferenceKeys`. **Never use raw `SharedPreferences` directly.**

## Coroutines

Launch with the `launchIO` / `launchUI` extension helpers — not raw `launch(Dispatchers.IO)`. Reactive state via `StateFlow` / `SharedFlow`; presenters collect flows and push state to controllers.

## Multiplatform (KMP)

`domain/` and `data/` use `commonMain` / `androidMain` source sets. Pure logic in `commonMain`; Android-only APIs in `androidMain`. Don't import Android types into `commonMain` code.

## Dependency injection

Koin 4 (modules under `yokai/core/di/`) is the active DI system. Older code uses Injekt — leave it; register new dependencies in Koin modules.
