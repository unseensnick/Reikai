# Contributing to Reikai

Reikai is a **personal fork** of [Mihon](https://github.com/mihonapp/mihon) (Tachiyomi lineage), built mainly for my own use, so development is sporadic and not every issue or pull request will be picked up. That said, bug reports are genuinely useful and welcome.

## Reporting bugs and requesting features

Open an issue using the templates:

- [Bug report](https://github.com/unseensnick/Reikai/issues/new?template=2_report_issue.yml)
- [Feature request](https://github.com/unseensnick/Reikai/issues/new?template=1_request_feature.yml)

Please search existing issues first, and include your Reikai version (More → About), Android version, and device.

Note: Reikai does **not** maintain or fix extensions/sources. Problems with a specific source or extension are out of scope.

## Code contributions

Pull requests are welcome, but reviews may take a while (this is a personal-time project). Working knowledge of [Android development](https://developer.android.com/) and [Kotlin](https://kotlinlang.org/) is assumed; existing contributors won't actively teach them.

Tooling: [Android Studio](https://developer.android.com/studio) (JDK 21), and an emulator or device for testing. Build and format per the conventions in the repo (`CLAUDE.md` and `.claude/rules/`).

## Upstream

Reikai tracks Mihon as its base, but Mihon is not a git remote of this repo, so upstream changes are ported **by hand**: clone Mihon locally, diff, and apply the relevant changes, with edits to Mihon's own files fenced by `// RK` markers. Reikai's own pre-rebase features come from the `design/library-compose` branch. See [`docs/dev/development.md`](docs/dev/development.md) for the architecture.

## License

Contributions are under [the project's LICENSE](LICENSE) (Apache-2.0).
