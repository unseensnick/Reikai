---
alwaysApply: true
---

# Testing

- Verify behavior, not implementation. Don't assert mock call counts when output values would do.
- **A behavior test written for one content type gets its twin.** The manga and novel engines are deliberately separate (`docs/dev/plans/content-layer-architecture.md`), so a test that pins one side's behavior and has no counterpart is where the two silently drift. If the other type genuinely cannot do the thing, say so in a one-line comment instead of leaving the gap unexplained.
- Run the specific test class after changes (`./gradlew :domain:test --tests "FullyQualifiedClassName"`), not the full suite. Faster feedback, fewer tokens.
- Flaky test? Fix it or delete it. Never retry to make it pass.
- Mock only at system boundaries (network, filesystem, clock, randomness). Prefer real implementations everywhere else. MockK for Kotlin types (use `every {}` + `verify {}`); avoid mocking final classes you own — extract an interface.
- Kotest assertions (`shouldBe`, `shouldHaveSize`, etc.) read more naturally than JUnit's `assertEquals`. One assertion per test. Test names describe behavior, not implementation.
- Arrange-Act-Assert. No `if` or loops in tests — parameterize with `@ParameterizedTest` or Kotest data-driven tests instead.
- Coroutines: use `runTest` with `TestDispatcher`; never call `runBlocking` in tests. Advance virtual time with `advanceTimeBy` / `advanceUntilIdle`.
- Multiplatform: put pure logic tests in `commonTest`, platform-specific tests in `androidUnitTest` / `androidInstrumentedTest`.
- Never `assertTrue(true)` or check a mock was called without verifying arguments.
