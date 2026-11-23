# Testing

A test earns its place by catching a bug a user would otherwise hit, or by holding an invariant the code cannot express (data loss, ordering, concurrency, parsing, an upgrade, a past regression). Count is not the goal: a test nobody would miss still costs CI time, a flake now and then, and an edit on every refactor.

## What to test

- **Behavior, not implementation.** Assert what came out and what was stored, not which mock was called. A call count is allowed only when the count is the behavior (no disk read per row, one query per batch), and the test name says so.
- **One behavior per test.** A test may check several facts about one outcome, such as what was saved and what was shown after one action. Inputs that differ only in data form a decision table: one `@ParameterizedTest` (or a Kotest data-driven test), never one method per row. Don't pack facts into one `listOf(...) shouldBe listOf(...)` to keep a single assertion; a failure should name the fact that broke.
- **Don't test what cannot break on its own:** a constant, a default value, an enum's members, a data class copy, a one-line delegation, or that a call was forwarded to a mock. The compiler or the caller's own test covers them.
- **A rule is tested once, where it lives.** A kernel's cases go in the kernel's test. Each caller gets at most one wiring test, the one that catches it calling the wrong kernel, never a re-run of the kernel's cases.
- **Both content types only where the code differs.** A rule both types must hold is pinned once: through the shared kernel, or, where the two engines are genuinely separate, one conformance test parameterized over both adapters (`@ParameterizedTest`). When both types run the same class, one unparameterized test covers both. No hand-written manga and novel twin tests: they drift (a 2026-08-05 audit found 8 tests on `MigrateMangaUseCase` against 24 on `MigrateNovelUseCase`). A type that genuinely cannot do the thing declares it unsupported in the case. Full rule: `.claude/rules/content-layer.md`.
- **A render bug gets a UI test.** A button wired to the wrong verb, a dialog that stopped rendering, a gesture swallowed: each leaves the build and the unit suite green, so the fix comes with a Compose UI test. One test per screen behavior, parameterized over its cases: a Robolectric test pays seconds of start-up, so never one per row that should exist.
- **Verify by mutation.** A new test is not done until the production clause it names has been deleted, the test seen red, and the clause restored. A test that cannot be made red is deleted, not kept.

## Time and coroutines

- **No real time in JVM tests.** Code that launches on `Dispatchers.IO` or `Default` lets the test supply the dispatcher, by a constructor parameter or `mockIoDispatcher` (`reikai/util/MockIoDispatcher.kt`), and the test drives virtual time with `runTest`, `advanceTimeBy` and `advanceUntilIdle`. Never wait for work with `withTimeout` on a real dispatcher, a `delay` polling loop, a latch or `Thread.sleep`.
- **`virtualDispatchers()` (`reikai/util/MockIoDispatcher.kt`) puts Main, IO and Default on one test scheduler** by stubbing `Dispatchers` with mockk. A `coEvery` or `coVerify` made while it is on leaves `Dispatchers.Default` answering a bare mock, so call `restubDefaultDispatcher()` after your own stubbing, and count calls through a hook in the fake rather than with `coVerify`. Work launched in `runTest`'s `backgroundScope` is never advanced by `advanceUntilIdle()`, so code the test must drive gets the `TestScope` itself.
- **"Nothing happens" is checked in virtual time.** Advance past the window and assert. A real-time wait both slows every run and passes a bug that is merely slower than the wait.
- A real-time test already in the tree is moved to virtual time when its file is next touched, or when it flakes, whichever comes first.
- Never call `runBlocking` in tests. **Exemption: instrumented tests (`app/src/androidTest`) may use `runBlocking`** (owner ruling). They drive real views, layout passes and a real WebView, which wait on the device's own frame clock and renderer process, so virtual time cannot advance them. Bound each wait with a real timeout instead.

## Shape and running

- Mock only at system boundaries (network, filesystem, clock, randomness). Prefer real implementations everywhere else. MockK for Kotlin types (use `every {}` + `verify {}`); avoid mocking final classes you own, extract an interface instead.
- Kotest assertions (`shouldBe`, `shouldHaveSize`, etc.) read more naturally than JUnit's `assertEquals`. Test names describe behavior, not implementation.
- Arrange-Act-Assert. No `if` or loops in tests; parameterize instead.
- **Expensive setup is built once when the cases only read it**: a migrated database or a composed screen goes in a per-class fixture, not in each case's setup.
- Run the specific test class after changes (`./gradlew :domain:test --tests "FullyQualifiedClassName"`), not the full suite. Faster feedback, fewer tokens.
- Flaky test? Fix it or delete it. Never retry to make it pass.
- Never `assertTrue(true)` or check a mock was called without verifying arguments.

## Placement and tooling

- JVM unit tests go in the module's `src/test` (`domain/src/test`, `app/src/test`, ...), tests that need a device in its `src/androidTest` (`app`, `data`). Compose UI tests are JVM tests too, under Robolectric in the module's `src/test`; see docs/dev/testing.md "Compose UI tests". No code module is multiplatform (only `i18n` is, and it holds no Kotlin), so there is no `commonTest`.
- **Compose UI tests are JUnit 4 classes** (owner ruling), since Robolectric and `createComposeRule` only run there; the vintage engine runs them beside the JUnit 6 suite. Parameterize them with `ParameterizedRobolectricTestRunner`. Robolectric also gives a JUnit 4 class the real `org.json` and `android.graphics`, which a JUnit 6 test only sees as "not mocked" stubs, so a rule over those needs no instrumented test.
- **Screenshots are compared by CI.** `ScreenshotTest` keeps reference images in `app/src/test/screenshots/`. A change that alters how a covered screen looks re-records them (`./gradlew :app:recordRoborazziDebug --tests "*ScreenshotTest"`), and the new images are looked at and committed in the same change; any OS records the same pixels.
