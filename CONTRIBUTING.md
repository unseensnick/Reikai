# Contributing to Reikai

Reikai is a **personal fork** of [Mihon](https://github.com/mihonapp/mihon) (Tachiyomi lineage), built mainly for my own use, so development is sporadic and not every issue or pull request will be picked up. That said, bug reports are genuinely useful and welcome.

## Bugs

Open a [bug report](https://github.com/unseensnick/Reikai/issues/new?template=2_report_issue.yml). Search existing issues first, and include your Reikai version (More → About), Android version, and device.

Reikai does **not** maintain or fix extensions/sources. Problems with a specific source or extension are out of scope.

## Feature ideas and questions

Open a [Discussion](https://github.com/unseensnick/Reikai/discussions) rather than an issue. Because Reikai is shaped around one person's use, a request might get built as asked, built as a variation that fits the app better, or not built at all, so talking it through first is the most useful path.

## Code contributions

Pull requests are welcome, but with honest expectations: this is a personal-time project, so a PR may sit for a while, may not be merged, or may inspire a different take on the same idea rather than being merged as-is. For anything beyond a small fix, open a [Discussion](https://github.com/unseensnick/Reikai/discussions) or an issue first so the approach can be agreed on before you put in the work.

Working knowledge of [Android development](https://developer.android.com/) and [Kotlin](https://kotlinlang.org/) is assumed; existing contributors won't actively teach them.

Tooling: [Android Studio](https://developer.android.com/studio) (JDK 21), and an emulator or device for testing. Build and format per the conventions in the repo (`CLAUDE.md` and `.claude/rules/`).

Commit messages use `type(scope): summary` (lower-case, imperative, no trailing period, 72 characters at most), with no em dashes, no AI co-author or "generated with" trailers, and no bare `#N`: link an issue as `unseensnick/Reikai#N`. CI checks every commit in a pull request against this. To catch it before pushing, install the hooks once per clone:

```bash
cp .githooks/commit-msg .githooks/pre-commit .git/hooks/ && chmod +x .git/hooks/commit-msg .git/hooks/pre-commit
```

The full standard is the "Commit message standard" section of [`.claude/rules/workflow.md`](.claude/rules/workflow.md).

## Upstream

Reikai tracks Mihon as its base; upstream changes are ported **by hand**: clone Mihon locally, diff, and apply the relevant changes, with edits to Mihon's own files fenced by `// RK` markers. Reikai's own pre-rebase features come from the `design/library-compose` branch. See [`docs/dev/development.md`](docs/dev/development.md) for the architecture.

## License

Contributions are under [the project's LICENSE](LICENSE) (Apache-2.0).
