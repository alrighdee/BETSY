# Contributing

## The most useful thing you can contribute

A capture from a car with a stored hybrid fault. Reading the transmitted INF value is confirmed on
a Gen2 Prius, and known DTC/sub-code pairs can be explained without inferring page ownership. A
different fault, a multi-fault car or another supported vehicle layout is still valuable evidence.
If you have one, [`tools/PROBE-PLAN.md`](tools/PROBE-PLAN.md) says what to capture. Code is easier
to come by.

## Commit messages

Commits follow [Conventional Commits](https://www.conventionalcommits.org/), because the changelog
and the version number are generated from them:

```
feat: read Toyota INF diagnostic sub-codes
fix: handle ELM327 connection timeout
docs: add supported adapter list
```

The subject line is quoted verbatim in `CHANGELOG.md` and in the GitHub release. Write it as the
line you want a stranger to read six months from now, not as a note to yourself. `CHANGELOG.md`
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); it is written entirely by
release-please and appears with the first release, so never edit it by hand, the next release
will overwrite you.

| Prefix | Lands in the changelog as | Bumps the version |
|---|---|---|
| `feat:` | Added | yes |
| `fix:` | Fixed | yes |
| `perf:`, `refactor:` | Changed | yes |
| `revert:` | Removed | yes |
| `deprecate:` | Deprecated | yes |
| `security:` | Security | yes |
| `docs:` | Documentation | yes |
| `build:`, `ci:`, `chore:`, `style:`, `test:` | not shown | no |

A `!` after the prefix (`feat!:`) or a `BREAKING CHANGE:` footer marks a breaking change. While the
major version is `0`, `feat:` and `fix:` both bump the patch number, nothing here has been proven
against a faulty car yet, and the version should stay honest about that.

A commit that does not parse is not rejected, it is simply invisible to the changelog. That is the
failure mode to watch for: a release that mysteriously contains nothing.

## Releasing

You do not tag by hand. release-please keeps a release PR open against `main` with the accumulated
changelog and version bump; merging it tags the release and triggers the signed APK build, which
attaches the APK to it. If a release ends up without an APK, run the `release` workflow manually
with the tag name.

## Before you open a PR

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # or wherever your JDK 17 lives
cd app
./gradlew check
```

That is ktlint, the unit tests and Android lint, the same gate CI runs.

## Scope

This app is read-only and stays that way. It does not clear codes, run actuator tests, or write to
an ECU. A patch that writes to a vehicle will not be merged, however well it works: the entire
safety argument for pointing a cheap clone adapter at a hybrid is that it can only ever read.

Everything the app knows about the wire protocol is written down in
[`docs/PROTOCOL.md`](docs/PROTOCOL.md), and the decoders cite its section numbers. If you change
decoding behaviour, change the spec in the same PR, and say plainly whether the change is
confirmed on a car or inferred. The distinction between *measured* and *believed* is the most
valuable thing in this repository; please keep it intact.
