# SwiftSlate release runbook

This repository has two intentionally different APK paths:

- **Preview** (`assemblePreview`) is a side-by-side, debug-signed test build with the
  `com.jcversa.swiftslate.preview` application ID. It is never a stable release.
- **Release** (`assembleRelease`) is the distribution build. It uses R8 and resource shrinking,
  the stable `com.jcversa.swiftslate` application ID, and the long-lived production keystore.

The stable workflow will not publish until the release APK has passed lint, unit tests, version
checks, zip alignment, R8 output checks, and `apksigner` verification.

## Versioning

`version.properties` is the source of truth for the next stable release:

```properties
versionName=1.0.0
versionCode=1
```

Use a numeric semantic `versionName` and increase `versionCode` for every distributable APK.
Never reuse a `versionCode`, even for a corrected build. Before tagging, update the file and add
the matching F-Droid changelog at
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## Configure signing once

Create one production keystore outside the repository and keep a protected copy offline. The
keystore and alias must remain the same for all updates, otherwise Android cannot update an
existing installation.

For a local release build, export these environment variables without putting their values in a
shell history, source file, log, issue, or commit:

```bash
export KEYSTORE_FILE=/secure/path/swiftslate-release.keystore
export KEYSTORE_PASSWORD='from-your-secret-manager'
export KEY_ALIAS='swiftslate'
export KEY_PASSWORD='from-your-secret-manager'
./gradlew :app:assembleRelease -PversionName=1.0.0 -PversionCode=1
```

The build fails when a release task has no keystore configuration. It is therefore not valid to
rename a Preview APK or an unsigned `app-release.apk` as a release. The local output is:
`app/build/outputs/apk/release/app-release.apk`.

For GitHub Actions, configure these **environment secrets** on the protected `release`
environment:

- `SWIFTSLATE_KEYSTORE_BASE64`
- `SWIFTSLATE_KEYSTORE_PASSWORD`
- `SWIFTSLATE_KEY_ALIAS`
- `SWIFTSLATE_KEY_PASSWORD`

The `Release stable APK` workflow decodes the keystore only under the runner's temporary
workspace, deletes it in an `always()` cleanup step, builds the tagged commit, and publishes only
a verified APK. Require maintainer approval for the `release` environment before enabling
publishing.

## Pre-release checks

Run the normal checks first, then use a real Android device for the accessibility and Process Text
flows:

```bash
./gradlew lint test
./gradlew :app:lintRelease :app:assembleRelease \
  -PversionName=1.0.0 -PversionCode=1

BUILD_TOOLS="$ANDROID_HOME/build-tools/$(ls -1 "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
"$BUILD_TOOLS/zipalign" -c -P 4 -v 4 app/build/outputs/apk/release/app-release.apk
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Also confirm the APK reports the stable application ID and exact version, contains R8 mapping
output, and installs over the previous stable APK without uninstalling it. Test at minimum:

- Android API 23 and a current API 36 device;
- normal and reduced-motion settings, safe areas, rotation, split-screen/multi-window;
- `?bold`, `?italic`, `?mono`, `?bubble`, `?gothic`, and `?smallcaps` in a standard editor and
  from the text-selection menu;
- accents, Arabic, Chinese, Thai, emoji, combining marks, and unsupported characters (none may
  disappear silently);
- password fields (no trigger processing), offline local styles, AI failure/offline behavior,
  provider switching, key rotation, encrypted export/import, Quick Settings, and rollback;
- the accessibility service after boot and after an app update.

Do not include API keys, history, screenshots containing secrets, or an unencrypted backup in a
release issue or artifact. Standard Android backup excludes the key, settings, command, stats,
and optional history stores. API keys can leave the device only through the explicit passphrase-
protected encrypted export.

## Tag and publish

After review, commit the metadata and changelog on `main`, then create an annotated tag whose
name exactly matches `versionName`:

```bash
git tag -a v1.0.0 -m "SwiftSlate v1.0.0"
git push origin v1.0.0
```

The tag workflow rejects tags that are not reachable from `main`, rejects a tag/version mismatch,
and refuses to publish if the protected signing inputs are missing. Verify the GitHub release asset
and its `SHA256SUMS.txt` before distributing it. Keep the workflow artifact and release notes with
the release record.

## Rollback

A rollback must preserve Android's signing lineage and monotonic version codes:

1. Stop linking or distributing the bad GitHub asset and record its tag, SHA-256, and impact.
2. Keep the previous signed release available. Do **not** replace it with a Preview APK, unsigned
   APK, or a build made with a new keystore.
3. For a GitHub-only rollback, point users to the previous verified release and, if necessary,
   mark the bad release as a pre-release or remove only its bad asset after preserving the audit
   record.
4. For a store rollout, halt the rollout and rebuild the last known-good source with the next
   unused `versionCode` (and the same production keystore), then release that corrective version.
   Android will reject a lower code as a downgrade.
5. Re-run the complete release verification, document the incident, and only then publish the
   corrective tag.

Never force-push a release tag or rewrite the production branch. If the signing key is lost or
exposed, stop distribution and follow the applicable Android signing-key recovery process rather
than silently creating a replacement key.
