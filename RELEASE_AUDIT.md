# SwiftSlate release audit

**Audit scope:** branch `arena/01a09365-swiftslate`, implementation commit `61a14ca`, and
release-readiness changes in `49d2802`, `d672d86`, `9be16a0`, `0d37bda`, `0a6ca98`,
`b6f6978`, and `ea994a7`.

**Decision: HOLD. No release was published.** A final stable APK has not yet been built and
installed in this sandbox because Java/Android SDK tooling and the production signing keystore
are not available here. The release workflow is intentionally tag-gated and must not be triggered
until the remaining operational checks below are complete.

## Findings and remediations

| Area | Result | Evidence / action |
| --- | --- | --- |
| Offline Text Styles | Implemented | `TextStyleTransformer` provides bold, italic, monospace, bubble, gothic, small caps, and normal. Built-in commands are local and do not call a provider. |
| Access paths | Implemented | Typed triggers are handled by `AssistantService`; `ACTION_PROCESS_TEXT` exposes the same styles in the selection menu. |
| Unicode preservation | Covered by tests and code | ASCII glyphs are transformed best-effort; whitespace, punctuation, emoji, accents, CJK, Arabic, Thai, and unsupported characters are retained. The app does not claim to change the host app's font. |
| Network policy | Hardened | Provider traffic and the GitHub update check use `ApiConnections`; HTTPS is required for fixed endpoints and cleartext is limited to validated private-LAN custom endpoints. Redirects are disabled. |
| API-key storage | Hardened | Provider-scoped AES-256-GCM storage uses Android Keystore. Encrypted export uses authenticated AES-GCM with PBKDF2 and never belongs to the standard backup. |
| Backup/privacy | Fixed in `49d2802` | Standard backup and Android 12+ cloud/device-transfer rules now exclude keys, settings, commands, stats, and the opt-in `history.xml`. |
| History | Opt-in | Local history is disabled by default, bounded, retention-limited, and clearable. Failed/refused commands are not recorded. |
| Permissions | Reviewed | INTERNET, VIBRATE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, and the explicitly user-enabled accessibility service have documented product purposes. No overlay permission is requested. |
| R8/shrinking | Configured | `release` enables minification and resource shrinking; the release workflow requires mapping output. Preview is separate and cannot replace stable. |
| Versioning | Fixed in `49d2802` | `version.properties` pins `1.0.0` / versionCode `1`; release tasks require a numeric stable version and positive code. The tag must match the file. |
| Signature/artifact | Workflow added in `49d2802` | `.github/workflows/release.yml` builds `assembleRelease`, verifies zip alignment and `apksigner`, checks package/version metadata, uploads checksums, and publishes only after those gates. |
| Rollback/documentation | Added | `RELEASE.md` documents key continuity, release verification, F-Droid `versionCode.txt`, and rollback with a higher versionCode and the same signing key. |

## Remaining release gates

1. Keep the `release` environment secrets configured, preserve an offline backup of the production
   keystore, and require maintainer approval. Environment-secret values cannot be read back by the
   audit agent, so verify the four names manually against `RELEASE.md`.
2. Run `Stable APK dry run` manually from the Actions page on `arena/01a09365-swiftslate`,
   download its signed dry-run artifact, and complete the real-device installation and upgrade
   checks. This workflow uploads an artifact only and cannot create a GitHub Release.
3. Merge pull request #4 only after review. Its current code checks are successful; do not use the
   Preview artifact as a stable release.
4. After the reviewed changes are on `main`, update `version.properties` and its matching changelog,
   create an annotated `v1.0.0` tag, and let the tag workflow run.
4. Inspect the workflow's stable artifact and `SHA256SUMS.txt`, then install the exact signed APK
   over the previous stable installation on a real API 23 device and a current API 36 device.
5. Complete the manual matrix: accessibility and selection-menu styles, offline styles, accents and
   Arabic/Chinese/Thai/emoji/combining marks, password fields, split-screen, rotation, safe areas,
   reduced motion, provider/key rotation, encrypted backup/restore, Quick Settings, boot/update
   recovery, offline AI failure, and rollback.
6. Record the final APK package ID, versionName, versionCode, signing certificate digest, SHA-256,
   test results, lint report, and install/upgrade result in the release record before publishing.

The corrected remote checks for `b6f6978` are Build APK run `35293627978` and Verify run
`35293627948`, both successful. They validate the Preview build, lint, and unit tests, but they are
**not** evidence of a signed stable APK until the tag workflow completes.
