---
paths:
  - "app/src/**"
  - "data/src/**"
  - "domain/src/**"
  - "source-api/src/**"
  - "core/common/src/**"
---

# Security

- Validate untrusted input at the system boundary (network responses, intent extras, file imports, deep links, restored backups). Never trust raw bytes from an extension or a URL.
- SQLDelight queries are parameterized by default, so never build SQL by string concatenation. Don't reach for raw cursor APIs to bypass type safety.
- Never log secrets, auth tokens, cookies, or full user-identifying URLs. `logcat { }` output goes to the system log, and the crash-log dump users share in bug reports (`CrashLogUtil.dumpLogs`) copies it, at error level or verbose when verbose logging is on. Crashlytics receives uncaught exceptions with their messages.
- Crashlytics: never call `recordException` / `setCustomKey` with raw network responses, file contents, or anything that could contain PII. Strip query strings and auth headers first.
- OkHttp interceptors: never log full request/response bodies in release builds. Gate verbose logging behind `BuildConfig.DEBUG`.
- Tracker OAuth tokens, source preferences, and extension credentials live in `PreferenceStore` / the typed `*Preferences` classes. Treat them as secrets, don't expose via `toString()`, debug overlays, or copy-to-clipboard helpers.
- WebView (used in some sources / Flaresolverr): never enable `setAllowFileAccessFromFileURLs` / `setAllowUniversalAccessFromFileURLs`. JavaScript bridge methods must validate caller origin.
- File I/O on `Uri` inputs: resolve via `ContentResolver` and check MIME types. Never trust path components from external apps.
- Manifest: keep `android:allowBackup` and `android:debuggable` correct per build type. Don't relax for debugging convenience and forget to revert.
- `source-api` is a plugin contract loaded by 3rd-party extensions. Never break its public surface or expose internal repository APIs through it.
