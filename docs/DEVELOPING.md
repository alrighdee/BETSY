# Developing

## Building

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # or wherever your JDK 17 lives
cd app
./gradlew check          # ktlint + unit tests + Android lint
./gradlew assembleDebug  # -> app/build/outputs/apk/debug/BETSY-debug.apk
```

Gradle is pinned to 8.9, because AGP 8.5.2 is not compatible with Gradle 9.x. minSdk 26,
targetSdk 34, and no third-party runtime dependencies at all.

Every green build on `main` also uploads a debug APK as a CI artifact, so a tester never has to
wait for a release to get a build.

## Layout

| Path | Purpose |
|---|---|
| `docs/PROTOCOL.md` | The wire spec. Read before touching a decoder, the code cites its section numbers |
| `app/` | The Android app. Plain `android.app.Activity`, no AppCompat, no third-party libraries |
| `tools/probe_inf.py` | Read-only on-car probe, driven from a desktop over Bluetooth SPP |
| `tools/PROBE-PLAN.md` | What to capture from a car that has a stored fault |
| `captures/` | Raw on-car probe logs, kept as evidence for the claims in the spec |

The `captures/` logs are deliberately unedited. They are the evidence behind the protocol spec, so
they are left exactly as the probe wrote them.

## Versioning and releases

The version lives in exactly one place, `betsyVersionName` in `app/build.gradle.kts`.
`versionCode` is derived from it, so there is no second number to keep in step.

Releases are cut by release-please from Conventional Commits. You do not tag by hand. See
[CONTRIBUTING.md](../CONTRIBUTING.md) for how that works.

## A note on adapters

Any adapter that already works with a hybrid battery app will work here. The longest response this
app issues is about 50 bytes, shorter than the live battery-data reply that any working Gen2 app
already reassembles.

Do not filter the paired-device list by Bluetooth class of device. Cheap clones report it wrongly,
the adapter this was developed against advertises itself as a keyboard, so filtering on it hides
working hardware.
